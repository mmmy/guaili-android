package com.gouge.xbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.SignalViewDto
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertResetResult
import com.gouge.xbot.data.XbotRepository
import com.gouge.xbot.domain.tickerLabel
import com.gouge.xbot.domain.normalizeTradingViewTicker
import com.gouge.xbot.widget.AlertDataCoordinator
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import retrofit2.HttpException

data class MainUiState(
    val serverUrl: String,
    val isAuthenticated: Boolean,
    val isLoading: Boolean = false,
    val signals: List<SignalViewDto> = emptyList(),
    val signalsUpdatedAtMillis: Long = 0,
    val alertsUpdatedAtMillis: Long = 0,
    val errorMessage: String? = null,
    val editingSignal: SignalViewDto? = null,
    val isSavingSettings: Boolean = false,
    val settingsErrorMessage: String? = null,
    val alertConfigs: List<TvAlertConfigDto> = emptyList(),
    val visibleAlertIds: Set<String> = emptySet(),
    val tvAlertsByCookieId: Map<String, List<TvAlertDto>> = emptyMap(),
    val hasLoadedAlerts: Boolean = false,
    val isLoadingAlerts: Boolean = false,
    val alertErrorMessage: String? = null,
    val quickAlertConfig: TvAlertConfigDto? = null,
    val isCreatingAlert: Boolean = false,
    val alertSetupErrorMessage: String? = null,
    val alertActionMessage: String? = null,
    val deletingTvAlert: TvAlertDeletionKey? = null,
    val resettingTvAlert: TvAlertDeletionKey? = null,
    val isRefreshingAlertCache: Boolean = false,
) {
    val isChangingAlerts: Boolean
        get() = isCreatingAlert || deletingTvAlert != null || resettingTvAlert != null || isRefreshingAlertCache
}

data class TvAlertDeletionKey(
    val cookieId: String,
    val alertId: Long,
)

class MainViewModel(
    private val repository: XbotRepository,
    private val serverConfigStore: ServerConfigStore,
    private val sessionStore: SessionStore,
    private val alertVisibilityStore: AlertVisibilityStore,
    private val alertSync: AlertDataCoordinator,
    private val onSignalsChanged: () -> Unit,
) : ViewModel() {
    private val requests = mutableSetOf<Job>()
    private var uiScope = sessionStore.currentScope()
    private val initialAuthenticated = !sessionStore.getAccessToken().isNullOrBlank()
    private val _uiState = MutableStateFlow(
        MainUiState(
            serverUrl = serverConfigStore.getBaseUrl(),
            isAuthenticated = initialAuthenticated,
            isLoading = initialAuthenticated,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        if (initialAuthenticated) refresh()
        viewModelScope.launch {
            alertSync.snapshots.collect { snapshot ->
                if (sessionStore.currentScope() != uiScope) synchronizeSession()
                if (!_uiState.value.isAuthenticated) return@collect
                if (sessionStore.getAccessToken().isNullOrBlank()) {
                    _uiState.value = MainUiState(serverConfigStore.getBaseUrl(), isAuthenticated = false)
                } else if (snapshot != null && snapshot.updatedAtMillis > 0) {
                    _uiState.update { it.copy(
                        alertConfigs = snapshot.configs,
                        visibleAlertIds = alertVisibilityStore.getVisibleIds().intersect(snapshot.configs.mapTo(hashSetOf()) { config -> config.id }),
                        tvAlertsByCookieId = snapshot.alertsByCookieId,
                        hasLoadedAlerts = true,
                        alertErrorMessage = snapshot.error,
                        alertsUpdatedAtMillis = snapshot.updatedAtMillis,
                    ) }
                }
            }
        }
    }

    fun login(serverUrl: String, username: String, password: String) {
        if (_uiState.value.isLoading) return
        requests.toList().forEach(Job::cancel)
        requests.clear()
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) {
                it.copy(serverUrl = serverUrl.trim(), isLoading = true, errorMessage = null)
            }
            try {
                repository.login(serverUrl, username, password) { requestGeneration = sessionStore.generation() }
                requestGeneration = sessionStore.generation()
                sessionStore.withCurrentGeneration(requestGeneration) { uiScope = sessionStore.currentScope() }
                val signals = repository.forSession(requestGeneration).getSignalViews()
                updateState(requestGeneration) {
                    MainUiState(serverUrl = serverConfigStore.getBaseUrl(), isAuthenticated = true,
                        signals = signals, signalsUpdatedAtMillis = System.currentTimeMillis())
                }
                onSignalsChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    invalidateSession(requestGeneration, error.toUserMessage())
                    return@launchRequest
                }
                updateState(requestGeneration) {
                    it.copy(
                        isAuthenticated = !sessionStore.getAccessToken().isNullOrBlank(),
                        isLoading = false,
                        errorMessage = error.toUserMessage(),
                    )
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isLoading && _uiState.value.signals.isNotEmpty()) return
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) { it.copy(isLoading = true, errorMessage = null) }
            try {
                val signals = repository.forSession(requestGeneration).getSignalViews()
                updateState(requestGeneration) {
                    it.copy(
                        isAuthenticated = true,
                        isLoading = false,
                        signals = signals,
                        signalsUpdatedAtMillis = System.currentTimeMillis(),
                    )
                }
                onSignalsChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    invalidateSession(requestGeneration, "登录已失效，请重新登录")
                } else {
                    updateState(requestGeneration) { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                }
            }
        }
    }

    fun loadAlerts(force: Boolean = false) {
        val current = _uiState.value
        if (!current.isAuthenticated || current.isChangingAlerts || current.isLoadingAlerts || (!force && current.hasLoadedAlerts)) return
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) { it.copy(isLoadingAlerts = true, alertErrorMessage = null) }
            try {
                val snapshot = alertSync.refresh(initializeHomeSelection = true)
                val configs = snapshot.configs
                val visibleIds = alertVisibilityStore.getVisibleIds().intersect(configs.mapTo(hashSetOf()) { it.id })
                val alerts = snapshot.alertsByCookieId
                updateState(requestGeneration) {
                    it.copy(
                        alertConfigs = configs,
                        visibleAlertIds = visibleIds,
                        tvAlertsByCookieId = alerts,
                        hasLoadedAlerts = true,
                        isLoadingAlerts = false,
                        alertsUpdatedAtMillis = snapshot.updatedAtMillis,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                handleAlertError(error, requestGeneration)
            }
        }
    }

    fun saveVisibleAlertIds(ids: Set<String>) {
        val requestGeneration = sessionStore.generation()
        val configs = _uiState.value.alertConfigs
        alertVisibilityStore.saveVisibleIds(ids, configs)
        updateState(requestGeneration) {
            it.copy(
                visibleAlertIds = ids.intersect(configs.mapTo(linkedSetOf()) { config -> config.id }),
                hasLoadedAlerts = false,
            )
        }
        alertSync.changed()
        loadAlerts(force = true)
    }

    fun openAlertSetup(config: TvAlertConfigDto) {
        val requestGeneration = sessionStore.generation()
        if (_uiState.value.isChangingAlerts) return
        updateState(requestGeneration) {
            it.copy(
                quickAlertConfig = config,
                alertSetupErrorMessage = null,
                alertActionMessage = null,
            )
        }
    }

    fun dismissAlertSetup() {
        val requestGeneration = sessionStore.generation()
        if (_uiState.value.isCreatingAlert) return
        updateState(requestGeneration) {
            it.copy(
                quickAlertConfig = null,
                alertSetupErrorMessage = null,
            )
        }
    }

    fun createTvAlert(tickerInput: String, periods: List<String>) {
        val config = _uiState.value.quickAlertConfig ?: return
        if (_uiState.value.isChangingAlerts) return
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) {
                it.copy(isCreatingAlert = true, alertSetupErrorMessage = null)
            }
            try {
                val ticker = normalizeTradingViewTicker(tickerInput)
                val result = repository.forSession(requestGeneration).addTvAlerts(config, ticker, periods) {
                    if (requestGeneration == sessionStore.generation()) updateState(requestGeneration) { it.copy(alertActionMessage = "已提交警报，等待创建完成") }
                }
                if (result.result) {
                    updateState(requestGeneration) {
                        it.copy(
                            quickAlertConfig = null,
                            isCreatingAlert = false,
                            alertSetupErrorMessage = null,
                            alertActionMessage = result.msg.ifBlank { "$ticker 警报设置成功" },
                        )
                    }
                } else {
                    updateState(requestGeneration) {
                        it.copy(
                            isCreatingAlert = false,
                            alertSetupErrorMessage = result.msg.ifBlank { "警报设置失败" },
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    handleAlertError(error, requestGeneration)
                } else {
                    updateState(requestGeneration) {
                        it.copy(
                            isCreatingAlert = false,
                            alertSetupErrorMessage = error.toUserMessage(),
                        )
                    }
                }
            } finally {
                if (requestGeneration == sessionStore.generation()) updateState(requestGeneration) { it.copy(isCreatingAlert = false) }
                if (requestGeneration == sessionStore.generation() && _uiState.value.isAuthenticated) loadAlerts(force = true)
            }
        }
    }

    fun deleteTvAlert(config: TvAlertConfigDto, alert: TvAlertDto) {
        if (_uiState.value.isChangingAlerts) return
        val deletionKey = TvAlertDeletionKey(config.cookieId, alert.alertId)
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) {
                it.copy(
                    deletingTvAlert = deletionKey,
                    alertErrorMessage = null,
                    alertActionMessage = null,
                )
            }
            try {
                val result = repository.forSession(requestGeneration).deleteTvAlert(config.cookieId, alert.alertId)
                if (result.result) {
                    alertSync.removeAlert(config.cookieId, alert.alertId, requestScope)
                    updateState(requestGeneration) { state ->
                        state.copy(
                            tvAlertsByCookieId = state.tvAlertsByCookieId + (
                                config.cookieId to state.tvAlertsByCookieId[config.cookieId]
                                    .orEmpty()
                                    .filterNot { it.alertId == alert.alertId }
                                ),
                            deletingTvAlert = null,
                            alertActionMessage = result.msg.ifBlank {
                                "已删除 ${alert.tickerLabel()} · ${alert.resolution}"
                            },
                        )
                    }
                } else {
                    updateState(requestGeneration) {
                        it.copy(
                            deletingTvAlert = null,
                            alertErrorMessage = result.msg.ifBlank { "删除警报失败" },
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    handleAlertError(error, requestGeneration)
                } else {
                    updateState(requestGeneration) {
                        it.copy(
                            deletingTvAlert = null,
                            alertErrorMessage = error.toUserMessage(),
                        )
                    }
                }
            }
        }
    }

    fun resetTvAlert(config: TvAlertConfigDto, alert: TvAlertDto) {
        val requestGeneration = sessionStore.generation()
        val current = _uiState.value
        if (!current.isAuthenticated || current.isChangingAlerts || current.isLoadingAlerts) return
        if (config !in current.alertConfigs || alert !in current.tvAlertsByCookieId[config.cookieId].orEmpty()) {
            updateState(requestGeneration) { it.copy(alertActionMessage = "警报或配置已更新，请刷新后重新选择") }
            return
        }
        val key = TvAlertDeletionKey(config.cookieId, alert.alertId)
        val knownIds = current.tvAlertsByCookieId[config.cookieId].orEmpty().mapTo(hashSetOf()) { it.alertId }
        val label = "${alert.tickerLabel()} · ${alert.resolution}"
        // Acquire the UI lock before launching so a second tap cannot submit again.
        updateState(requestGeneration) {
            it.copy(resettingTvAlert = key, alertErrorMessage = null, alertActionMessage = null)
        }
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            try {
                when (val result = repository.forSession(requestGeneration).resetTvAlert(config, alert, knownIds) {
                    if (requestGeneration == sessionStore.generation()) updateState(requestGeneration) { it.copy(alertActionMessage = "已提交再设 $label，等待设置完成") }
                }) {
                    is TvAlertResetResult.Completed -> {
                        alertSync.replaceAccount(config.cookieId, result.alerts, requestScope)
                        updateState(requestGeneration) { it.copy(
                            tvAlertsByCookieId = it.tvAlertsByCookieId + (config.cookieId to result.alerts),
                            alertActionMessage = "$label 已重新设置",
                        ) }
                    }
                    is TvAlertResetResult.Failed -> {
                        updateState(requestGeneration) { it.copy(alertActionMessage = "再设未完成：${result.message}") }
                    }
                    is TvAlertResetResult.Unconfirmed -> {
                        updateState(requestGeneration) { it.copy(alertActionMessage = result.message) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    handleAlertError(error, requestGeneration)
                } else {
                    updateState(requestGeneration) {
                        it.copy(alertActionMessage = "再设结果未确认：${error.toUserMessage()}，请刷新检查后再操作")
                    }
                }
            } finally {
                if (requestGeneration == sessionStore.generation()) updateState(requestGeneration) { it.copy(resettingTvAlert = null) }
            }
            // Reload the config too: the backend rebuilds using its current parameters.
            if (requestGeneration == sessionStore.generation() && _uiState.value.isAuthenticated) loadAlerts(force = true)
        }
    }

    fun refreshTradingViewCache() {
        val requestGeneration = sessionStore.generation()
        val current = _uiState.value
        if (current.isChangingAlerts || current.isLoadingAlerts) return
        val cookies = current.alertConfigs.filter { it.id in current.visibleAlertIds }.mapTo(hashSetOf()) { it.cookieId }
        if (cookies.isEmpty()) return
        updateState(requestGeneration) { it.copy(isRefreshingAlertCache = true, alertActionMessage = "正在刷新 TradingView 缓存…") }
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            try {
                repository.forSession(requestGeneration).refreshTradingViewCache(cookies)
                updateState(requestGeneration) { it.copy(alertActionMessage = "TradingView 缓存已刷新") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                handleAlertError(error, requestGeneration)
            } finally {
                if (requestGeneration == sessionStore.generation()) updateState(requestGeneration) { it.copy(isRefreshingAlertCache = false) }
            }
            if (requestGeneration == sessionStore.generation() && _uiState.value.isAuthenticated) loadAlerts(force = true)
        }
    }

    fun openSignalSettings(signal: SignalViewDto) {
        val requestGeneration = sessionStore.generation()
        updateState(requestGeneration) {
            it.copy(
                editingSignal = signal,
                settingsErrorMessage = null,
            )
        }
    }

    fun dismissSignalSettings() {
        val requestGeneration = sessionStore.generation()
        if (_uiState.value.isSavingSettings) return
        updateState(requestGeneration) {
            it.copy(
                editingSignal = null,
                settingsErrorMessage = null,
            )
        }
    }

    fun saveSignalSettings(periods: List<String>, expireAt: String?) {
        val signal = _uiState.value.editingSignal ?: return
        if (_uiState.value.isSavingSettings) return
        launchRequest {
            var requestGeneration = sessionStore.generation()
            val requestScope = alertSync.currentScope()
            updateState(requestGeneration) {
                it.copy(
                    isSavingSettings = true,
                    settingsErrorMessage = null,
                )
            }
            try {
                val updated = repository.forSession(requestGeneration).updateSignalSettings(signal.id, periods, expireAt)
                updateState(requestGeneration) { state ->
                    state.copy(
                        signals = state.signals.map { if (it.id == updated.id) updated else it },
                        editingSignal = null,
                        isSavingSettings = false,
                        settingsErrorMessage = null,
                    )
                }
                onSignalsChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != sessionStore.generation()) return@launchRequest
                if (error is HttpException && error.code() == 401) {
                    invalidateSession(requestGeneration, "登录已失效，请重新登录")
                } else {
                    updateState(requestGeneration) {
                        it.copy(
                            isSavingSettings = false,
                            settingsErrorMessage = error.toUserMessage(),
                        )
                    }
                }
            }
        }
    }

    fun logout() {
        requests.toList().forEach(Job::cancel)
        requests.clear()
        _uiState.value = MainUiState(serverConfigStore.getBaseUrl(), isAuthenticated = false)
        viewModelScope.launch {
            repository.logout()
            onSignalsChanged()
        }
    }

    /** Reconciles account changes made by a widget window or a background worker. */
    fun synchronizeSession() {
        val generation = sessionStore.generation()
        val currentScope = sessionStore.currentScope()
        if (currentScope == uiScope) return
        sessionStore.withCurrentGeneration(generation) {
            requests.toList().forEach(Job::cancel)
            requests.clear()
            uiScope = currentScope
            _uiState.value = MainUiState(serverConfigStore.getBaseUrl(),
                isAuthenticated = currentScope != null, isLoading = currentScope != null)
            if (currentScope != null) refresh()
        }
    }

    private fun invalidateSession(expectedGeneration: Long, message: String) {
        sessionStore.withCurrentGeneration(expectedGeneration) {
            sessionStore.clear()
            uiScope = null
            _uiState.value = MainUiState(serverConfigStore.getBaseUrl(), isAuthenticated = false, errorMessage = message)
            onSignalsChanged()
        }
    }

    private fun updateState(expectedGeneration: Long, transform: (MainUiState) -> MainUiState) {
        sessionStore.withCurrentGeneration(expectedGeneration) {
            uiScope = sessionStore.currentScope()
            _uiState.update(transform)
        }
    }

    private fun launchRequest(block: suspend CoroutineScope.() -> Unit) {
        val job = viewModelScope.launch(block = block)
        requests += job
        job.invokeOnCompletion { requests -= job }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            private val appContext = context.applicationContext
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MainViewModel::class.java))
                val server = ServerConfigStore(appContext)
                val session = SessionStore(appContext)
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(XbotRepository(server, session), server, session,
                    AlertVisibilityStore(appContext), AlertDataCoordinator(appContext),
                    onSignalsChanged = { com.gouge.xbot.widget.SignalWidgetScheduler.enqueueImmediate(appContext) }) as T
            }
        }
    }

    private fun handleAlertError(error: Exception, expectedGeneration: Long) {
        if (expectedGeneration != sessionStore.generation()) return
        if (error is HttpException && error.code() == 401) {
            invalidateSession(expectedGeneration, "登录已失效，请重新登录")
        } else {
            _uiState.update {
                it.copy(
                    isLoadingAlerts = false,
                    hasLoadedAlerts = true,
                    deletingTvAlert = null,
                    alertErrorMessage = error.toUserMessage(),
                )
            }
        }
    }
}

private fun Throwable.toUserMessage(): String = when (this) {
    is IllegalArgumentException -> message ?: "输入有误"
    is IllegalStateException -> message ?: "当前状态无效"
    is IOException -> "无法连接服务器，请检查地址和网络"
    is HttpException -> when (code()) {
        400 -> "用户名、密码或请求内容有误"
        401 -> "用户名或密码错误，或登录已失效"
        403 -> "用户名或密码错误，或当前账户没有访问权限"
        404 -> "服务器接口不存在，请检查服务器地址"
        else -> "服务器请求失败（HTTP ${code()}）"
    }
    else -> message ?: "请求失败"
}
