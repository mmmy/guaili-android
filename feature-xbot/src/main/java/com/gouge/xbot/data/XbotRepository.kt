package com.gouge.xbot.data

import com.gouge.xbot.domain.sortSignalPeriods
import com.gouge.xbot.domain.normalizeTradingViewTicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class XbotRepository(
    private val serverConfigStore: ServerConfigStore,
    private val sessionStore: SessionStore,
    private val expectedGeneration: Long? = null,
) {
    fun forSession(generation: Long): XbotRepository = XbotRepository(serverConfigStore, sessionStore, generation)
    suspend fun login(baseUrl: String, username: String, password: String, onSessionReset: () -> Unit = {}) {
        require(username.isNotBlank()) { "请输入用户名" }
        require(password.isNotBlank()) { "请输入密码" }

        val normalizedUrl = normalizeBaseUrl(baseUrl)
        sessionStore.clear()
        val generation = sessionStore.generation()
        onSessionReset()
        val api = ApiClientFactory.create(normalizedUrl) { null }
        val result = api.login(LoginRequest(username.trim(), password))
        if (generation != sessionStore.generation()) throw CancellationException("账户已切换")
        require(result.accessToken.isNotBlank()) { "后端未返回 access_token" }
        if (!sessionStore.saveAccessTokenIfCurrent(result.accessToken, generation) {
            serverConfigStore.saveBaseUrl(normalizedUrl)
        }) throw CancellationException("账户已切换")
    }

    suspend fun getSignalViews(): List<SignalViewDto> {
        return guardedRequest { it.getSignalViews().sortedBy { signal -> signal.sort } }
    }

    suspend fun getSignalView(signalId: String): SignalViewDto {
        return guardedRequest { it.getSignalView(signalId) }
    }

    suspend fun getTvAlertConfigs(): List<TvAlertConfigDto> {
        return guardedRequest { api -> api.getTvAlertConfigs().sortedWith(
            compareBy<TvAlertConfigDto> { it.sort ?: Double.MAX_VALUE }
                .thenBy { it.title },
        ) }
    }

    suspend fun getTvAlerts(cookieIds: Set<String>): Map<String, List<TvAlertDto>> = guardedRequest { api -> coroutineScope {
        cookieIds
            .filter { it.isNotBlank() }
            .map { cookieId ->
                async {
                    cookieId to api.getTvAlerts(
                        TvAlertListRequest(cookieId = cookieId, namePre = "_"),
                    )
                }
            }
            .awaitAll()
            .toMap()
    } }

    suspend fun addTvAlerts(
        config: TvAlertConfigDto,
        ticker: String,
        periods: List<String>,
        onSubmitted: () -> Unit = {},
    ): OperationResultDto {
        require(periods.isNotEmpty()) { "请至少选择一个级别" }
        return guardedRequest { api ->
        val previousIds = api.getTvAlerts(TvAlertListRequest(config.cookieId, "_")).mapTo(hashSetOf()) { it.alertId }
        val request = AddTvAlertRequest(
                alertId = config.id,
                symbols = normalizeTradingViewTicker(ticker),
                periods = sortSignalPeriods(periods).joinToString(" "),
            )
        api.addTvAlertsAndWait(
            request,
            onSubmitted = onSubmitted,
            verifyCreated = { count -> api.awaitCreatedAlerts(config, request, previousIds, count) },
        )
        }
    }

    suspend fun refreshTradingViewCache(cookieIds: Set<String>) {
        guardedRequest { api ->
        cookieIds.filter(String::isNotBlank).forEach { cookieId ->
            val result = api.refreshTradingViewCache(RefreshAlertCacheRequest(cookieId))
            check(result.result) { result.msg.ifBlank { "刷新 TradingView 缓存失败" } }
        }
        }
    }

    suspend fun deleteTvAlert(cookieId: String, alertId: Long): OperationResultDto {
        require(cookieId.isNotBlank()) { "警报账户无效" }
        return guardedRequest { api -> api.deleteTvAlertById(
            DeleteTvAlertByIdRequest(cookieId = cookieId, alertId = alertId),
        ) }
    }

    suspend fun resetTvAlert(
        config: TvAlertConfigDto,
        alert: TvAlertDto,
        knownAlertIds: Set<Long>,
        onSubmitted: () -> Unit,
    ): TvAlertResetResult {
        return guardedRequest { api -> TvAlertResetter(api).reset(config, alert, knownAlertIds, onSubmitted) }
    }

    suspend fun updateSignalSettings(
        signalId: String,
        periods: List<String>,
        expireAt: String?,
    ): SignalViewDto {
        return guardedRequest { api -> api.updateSignalSettings(
            id = signalId,
            request = UpdateSignalSettingsRequest(
                periods = sortSignalPeriods(periods),
                expireAt = expireAt,
            ),
        ) }
    }

    suspend fun logout() {
        val token = sessionStore.getAccessToken()
        val baseUrl = serverConfigStore.getBaseUrl()
        // Invalidate local state before a slow or unavailable logout endpoint responds.
        sessionStore.clear()
        try {
            if (!token.isNullOrBlank()) ApiClientFactory.create(baseUrl) { token }.logout()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Local logout must still succeed when the server is unreachable.
        }
    }

    private suspend fun <T> guardedRequest(block: suspend (XbotApiService) -> T): T {
        val generation = sessionStore.generation()
        if (expectedGeneration != null && expectedGeneration != generation) throw CancellationException("账户已切换")
        val token = sessionStore.getAccessToken()
        check(!token.isNullOrBlank()) { "请先登录" }
        val baseUrl = serverConfigStore.getBaseUrl()
        val current = { generation == sessionStore.generation() && baseUrl == serverConfigStore.getBaseUrl() }
        val api = ApiClientFactory.create(baseUrl, isSessionCurrent = current) { token }
        try {
            val result = block(api)
            if (!current()) throw CancellationException("账户已切换")
            return result
        } catch (error: Exception) {
            if (!current()) throw CancellationException("账户已切换")
            throw error
        }
    }

}
