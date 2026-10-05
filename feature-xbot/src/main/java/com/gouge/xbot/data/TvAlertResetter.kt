package com.gouge.xbot.data

import com.gouge.xbot.domain.matches
import com.gouge.xbot.domain.normalizeTradingViewTicker
import com.gouge.xbot.domain.tickerId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val resetResolutionPattern = Regex("^(?:[1-9][0-9]*[SDWM]?|[SDWM])$")

fun TvAlertDto.resetRequest(config: TvAlertConfigDto): AddTvAlertRequest {
    require(config.id.isNotBlank() && config.cookieId.isNotBlank() && config.matches(this)) {
        "警报配置无效，无法再设"
    }
    val ticker = tickerId()
    require(ticker.isNotBlank()) { "警报品种无效，无法再设" }
    val period = resolution.trim().uppercase(Locale.ROOT)
    require(resetResolutionPattern.matches(period)) { "警报周期无效，无法再设" }
    return AddTvAlertRequest(config.id, normalizeTradingViewTicker(ticker), period, overwrite = true)
}

sealed interface TvAlertResetResult {
    data class Completed(val alerts: List<TvAlertDto>) : TvAlertResetResult
    data class Failed(val message: String) : TvAlertResetResult
    data class Unconfirmed(val message: String) : TvAlertResetResult
}

internal class TvAlertResetter(
    private val api: XbotApiService,
    private val maxPollAttempts: Int = 60,
    private val pollIntervalMillis: Long = 1_000,
) {
    suspend fun reset(
        config: TvAlertConfigDto,
        alert: TvAlertDto,
        knownAlertIds: Set<Long>,
        onSubmitted: () -> Unit,
    ): TvAlertResetResult {
        val request = alert.resetRequest(config)
        val previousProcess = api.getTvAlertProcesses().find { it.alertId == config.id }
        if (previousProcess?.status == "running") {
            return TvAlertResetResult.Failed("该配置正在设置警报，请稍后再设")
        }
        // The backend performs overwrite; this flow never calls the delete endpoint.
        val submitted = api.addTvAlerts(request)
        if (!submitted.result) {
            return TvAlertResetResult.Failed(submitted.msg.ifBlank { "再设请求未被接受" })
        }
        onSubmitted()
        val oldIds = knownAlertIds + alert.alertId
        return withTimeoutOrNull(120_000) {
            repeat(maxPollAttempts) { attempt ->
                val process = api.getTvAlertProcesses().find {
                    it.alertId == config.id && it.startDate != previousProcess?.startDate
                }
                if (process?.status == "error") {
                    return@withTimeoutOrNull TvAlertResetResult.Failed(
                        process.msg.ifBlank { "后端设置失败，请刷新检查警报" },
                    )
                }
                if (process?.status == "finished" && process.maxAlerts > 0 &&
                    process.finishedAlerts == process.maxAlerts
                ) {
                    val alerts = api.getTvAlerts(TvAlertListRequest(config.cookieId, "_"))
                    val hasNewAlert = alerts.any {
                        it.alertId !in oldIds && it.active && config.matches(it) &&
                            it.tickerId() == request.symbols &&
                            it.resolution.trim().uppercase(Locale.ROOT) == request.periods
                    }
                    if (hasNewAlert) return@withTimeoutOrNull TvAlertResetResult.Completed(alerts)
                }
                if (attempt < maxPollAttempts - 1) delay(pollIntervalMillis)
            }
            null
        } ?: TvAlertResetResult.Unconfirmed("已提交再设，暂未确认完成，请刷新查看结果")
    }
}
