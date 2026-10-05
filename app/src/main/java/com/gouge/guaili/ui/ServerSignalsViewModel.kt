package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.*
import com.gouge.guaili.signals.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class MarketSignalsUiState(
    val preferences: MarketSignalPreferences = MarketSignalPreferences(),
    val symbols: List<String> = emptyList(),
    val availableSymbols: List<String> = emptyList(),
    val snapshot: ServerSignalsSnapshot? = null,
    val presentation: ServerSignalsState = ServerSignalsState("unavailable", "等待服务器信号", "点击刷新获取信号 v2"),
    val isRefreshing: Boolean = false,
    val preferenceError: String? = null,
    val clockMessage: String = "刷新后校准时间",
    val refreshError: String? = null,
    val autoRefreshSeconds: Int = 5,
)

internal class ServerSignalsViewModel(
    private val settingsSource: GuailiSettingsSource,
    private val preferencesSource: MarketSignalPreferencesSource,
    private val snapshotSource: ServerSignalsSource,
    private val refresher: ServerSignalsRefreshUseCase = ServerSignalsRefreshUseCase(snapshotSink = snapshotSource),
    private val onRefreshed: suspend (GuailiSettings, GuailiResult<ServerSignalsSnapshot>) -> Unit = { _, _ -> },
) : ViewModel() {
    private val mutableState = MutableStateFlow(MarketSignalsUiState())
    val state = mutableState.asStateFlow()
    private var settings = GuailiSettings.defaults()
    private var preferences = MarketSignalPreferences()
    private var snapshot: ServerSignalsSnapshot? = null
    private var failure: ServerSignalsFailure? = null
    private var foreground = false
    private var polling: Job? = null
    private var expiry: Job? = null
    private var request: Job? = null
    private var requestGeneration = 0

    init {
        viewModelScope.launch {
            combine(settingsSource.settings, preferencesSource.preferences,
                snapshotSource.snapshots, snapshotSource.failures) { config, prefs, value, error ->
                Observed(config, prefs, value, error)
            }.collect { observed ->
                val sourceChanged = normalizeServerSignalsBaseUrl(settings.baseUrl) !=
                    normalizeServerSignalsBaseUrl(observed.settings.baseUrl)
                val refreshIntervalChanged = settings.autoRefreshSeconds != observed.settings.autoRefreshSeconds
                settings = observed.settings
                preferences = observed.preferences
                snapshot = observed.snapshot?.takeIf { it.belongsTo(settings.baseUrl) }
                failure = observed.failure
                rebuild()
                if (sourceChanged) {
                    request?.cancel()
                    if (foreground) restartPolling()
                } else if (refreshIntervalChanged && foreground) {
                    restartPolling(refreshImmediately = false)
                }
            }
        }
    }

    fun setView(view: MarketView) = updatePreferences { it.copy(view = view) }
    fun toggleSymbol(symbol: String) = updatePreferences { prefs ->
        val selected = prefs.symbols ?: settings.symbols
        prefs.copy(symbols = if (symbol in selected) selected - symbol else selected + symbol)
    }
    fun selectAllSymbols() = updatePreferences { it.copy(symbols = mutableState.value.availableSymbols) }
    fun toggleKind(kind: GuailiSignalKind) = updatePreferences { prefs ->
        prefs.copy(kinds = if (kind in prefs.kinds) prefs.kinds - kind else prefs.kinds + kind)
    }

    private fun updatePreferences(transform: (MarketSignalPreferences) -> MarketSignalPreferences) {
        viewModelScope.launch {
            try {
                preferencesSource.update(transform)
                mutableState.value = mutableState.value.copy(preferenceError = null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(preferenceError = "无法保存显示设置，请重试")
            }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        polling?.cancel()
        expiry?.cancel()
        request?.cancel()
        if (active) {
            rebuild()
            restartPolling()
            // Expiry is checked even when every network request fails or hangs.
            expiry = viewModelScope.launch { while (foreground) { delay(1_000); rebuild() } }
        }
    }

    private fun refreshIntervalMillis() = settings.autoRefreshSeconds.coerceAtLeast(1).toLong() * 1_000L

    private fun restartPolling(refreshImmediately: Boolean = true) {
        polling?.cancel()
        polling = viewModelScope.launch {
            if (!refreshImmediately) {
                request?.join()
                delay(refreshIntervalMillis())
            }
            while (foreground) {
                refresh()
                request?.join()
                delay(refreshIntervalMillis())
            }
        }
    }

    fun refresh() {
        if (request?.isActive == true) return
        val requestedSettings = settings
        val generation = ++requestGeneration
        request = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshing = true)
            try {
                val result = refresher.refresh(requestedSettings.baseUrl, reuseWithinMillis = 1_000L)
                if (normalizeServerSignalsBaseUrl(settings.baseUrl) !=
                    normalizeServerSignalsBaseUrl(requestedSettings.baseUrl)) return@launch
                when (result) {
                    is GuailiResult.Success -> { snapshot = result.value; failure = null }
                    is GuailiResult.Failure -> failure = ServerSignalsFailure(result.message,
                        System.currentTimeMillis(), requestedSettings.baseUrl)
                }
                rebuild()
                try {
                    onRefreshed(requestedSettings, result)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A launcher rendering failure does not invalidate the foreground snapshot.
                }
            } finally {
                if (generation == requestGeneration) mutableState.value = mutableState.value.copy(isRefreshing = false)
            }
        }
    }

    private fun rebuild() {
        val symbols = preferences.symbols ?: settings.symbols
        val device = snapshotSource.currentDeviceTime()
        val display = if (device == null) ServerSignalsState("time_uncertain", "时间暂不可确认，请刷新", "刷新后重新校准时间")
            else serverSignalsState(snapshot, failure, ServerSignalDisplayConfig(symbols, preferences.kinds), settings.baseUrl, device)
        mutableState.value = mutableState.value.copy(
            preferences = preferences, symbols = symbols, snapshot = snapshot,
            availableSymbols = (settings.symbols + snapshot?.response?.results.orEmpty().map { it.symbol } + symbols).distinct(),
            presentation = display,
            autoRefreshSeconds = settings.autoRefreshSeconds.coerceAtLeast(1),
            refreshError = failure?.takeIf {
                normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(settings.baseUrl) &&
                    it.updatedAt >= (snapshot?.updatedAt ?: 0L)
            }?.message,
            clockMessage = if (snapshot != null && device != null) assessServerSignalsTime(snapshot!!, device).let {
                it.unavailableReason ?: it.correctionMessage ?: "已按服务器时间校准"
            } else "刷新后校准时间",
        )
    }

    private data class Observed(val settings: GuailiSettings, val preferences: MarketSignalPreferences,
        val snapshot: ServerSignalsSnapshot?, val failure: ServerSignalsFailure?)
}
