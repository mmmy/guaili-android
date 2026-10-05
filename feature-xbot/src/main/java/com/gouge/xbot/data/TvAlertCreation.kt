package com.gouge.xbot.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.gouge.xbot.domain.matches
import com.gouge.xbot.domain.tickerId
import java.util.Locale

internal suspend fun XbotApiService.addTvAlertsAndWait(
    request: AddTvAlertRequest,
    onSubmitted: () -> Unit = {},
    pollAttempts: Int = 60,
    pollIntervalMillis: Long = 1_000,
    verifyCreated: suspend (Int) -> Boolean = { true },
): OperationResultDto {
    val previous = getTvAlertProcesses().find { it.alertId == request.alertId }
    if (previous?.status == "running") return OperationResultDto(false, "该配置正在设置警报，请稍后再试")
    val submitted = addTvAlerts(request)
    if (!submitted.result) return submitted
    onSubmitted()
    return withTimeoutOrNull(120_000) {
        repeat(pollAttempts) { attempt ->
            val process = getTvAlertProcesses().find { it.alertId == request.alertId && it.startDate != previous?.startDate }
            when (process?.status) {
                "finished" -> if (process.maxAlerts > 0 && process.maxAlerts == process.finishedAlerts) {
                    return@withTimeoutOrNull if (verifyCreated(process.finishedAlerts)) {
                        OperationResultDto(true, "已设置 ${process.finishedAlerts} 条警报")
                    } else OperationResultDto(false, "警报已创建，列表暂未同步，请刷新查看")
                }
                "error" -> return@withTimeoutOrNull OperationResultDto(false, process.msg.ifBlank { "警报设置失败" })
            }
            if (attempt < pollAttempts - 1) delay(pollIntervalMillis)
        }
        null
    } ?: OperationResultDto(false, "请求已提交，暂未确认完成，请刷新查看结果")
}

internal suspend fun XbotApiService.awaitCreatedAlerts(
    config: TvAlertConfigDto,
    request: AddTvAlertRequest,
    previousIds: Set<Long>,
    createdCount: Int,
    pollAttempts: Int = 60,
    pollIntervalMillis: Long = 1_000,
): Boolean {
    val periods = request.periods.split(' ').toSet()
    repeat(pollAttempts) { attempt ->
        val alerts = getTvAlerts(TvAlertListRequest(config.cookieId, "_"))
        val fresh = alerts.count {
            it.alertId !in previousIds && it.active && config.matches(it) && it.tickerId() == request.symbols &&
                (config.type == "CROSS" || it.resolution.trim().uppercase(Locale.ROOT) in periods)
        }
        if (fresh >= createdCount) return true
        if (attempt < pollAttempts - 1) delay(pollIntervalMillis)
    }
    return false
}
