package com.gouge.guaili.pullback

import com.gouge.guaili.data.*
import java.time.Instant

internal fun priceAlertGroup(intent: String): String = when (intent) {
    "open_long", "close_long" -> "Long"
    "open_short", "close_short" -> "Short"
    else -> "Unclassified"
}
internal fun priceAlertPrimaryKey(url: String, symbol: String, group: String) = "${url.trim().trimEnd('/')}|$symbol|$group"
internal fun PriceAlertDto.isEnabledAt(now: Long) = status == "active" && (expiresAt == null || expiresAt > now)
internal fun PriceAlertDto.canExtend(now: Long) = isEnabledAt(now) && geometry.valid() && geometry.extend == "none" && geometry.second.timeMs < now
internal fun PriceAlertDto.pullbackStatus(now: Long, stale: Boolean = false): String = when {
    stale -> "待更新"
    status != "active" || !isEnabledAt(now) -> stateLabel(now)
    geometry.extend != "both" && geometry.first.timeMs > now -> "等起点"
    geometry.extend == "none" && geometry.second.timeMs < now -> "范围结束"
    dataStatus == "live" -> "监测"
    dataStatus == "waiting_for_range" -> "等起点"
    dataStatus == "range_ended" -> "待更新"
    dataStatus in listOf("waiting_for_metadata", "unavailable") -> "等精度"
    dataStatus == "disconnected" -> "行情断开"
    dataStatus == "invalid_line" -> "线价无效"
    else -> "等行情"
}
internal fun groupPullbackAlerts(alerts: List<PriceAlertDto>, now: Long): Map<String, Map<String, List<PriceAlertDto>>> =
    alerts.asSequence().filter { it.isEnabledAt(now) }.sortedBy { it.id }.groupBy { it.symbol }
        .mapValues { (_, values) -> values.groupBy { priceAlertGroup(it.intent) } }

internal fun primaryPullbackAlert(alerts: List<PriceAlertDto>, chosen: Long?): PriceAlertDto? =
    alerts.firstOrNull { it.id == chosen } ?: alerts.firstOrNull()

data class PriceAlertExtensionPlan(val alert: PriceAlertDto, val bars: Int,
    val geometry: PriceAlertGeometry, val serverTime: Long, val latestOpen: Long) {
    val waitsForStart get() = geometry.first.timeMs > serverTime
}

internal fun extensionPlan(alert: PriceAlertDto, bars: Int, response: KlineEnvelope): PriceAlertExtensionPlan {
    require(bars in 1..1000) { "延期 K 线数须为 1–1000" }
    val period = requireNotNull(pullbackPeriodMillis(alert.interval)) { "警报周期无效" }
    val serverTime = requireNotNull(response.serverTime) { "最新 K 线缺少服务时间" }
    require(alert.canExtend(serverTime)) { "仅能延期启用且范围结束的有限警报" }
    require(response.symbol == alert.symbol && !response.closedOnly && response.intervals.size == 1 &&
        response.intervals.single() == alert.interval && response.series.size == 1) { "最新 K 线品种或周期不匹配" }
    val series = response.series.single()
    require(series.interval == alert.interval && series.count == 1 && series.data.size == 1) { "最新 K 线响应无效" }
    val row = series.data.single()
    require(row.symbol == alert.symbol && row.interval == alert.interval && !row.candle.isClosed) { "最新 K 线身份无效" }
    val open = Instant.parse(row.candle.openTime).toEpochMilli()
    require(open >= 0 && serverTime >= open && Math.subtractExact(serverTime, open) < period) { "最新 K 线已过期，请刷新重试" }
    val end = Math.addExact(open, Math.multiplyExact(bars.toLong(), period))
    require(end > serverTime) { "延期终点须晚于当前时间" }
    val delta = Math.subtractExact(end, alert.geometry.second.timeMs)
    val geometry = alert.geometry.copy(first = alert.geometry.first.copy(timeMs = Math.addExact(alert.geometry.first.timeMs, delta)),
        second = alert.geometry.second.copy(timeMs = end))
    require(geometry.valid()) { "延期时间超出范围" }
    return PriceAlertExtensionPlan(alert, bars, geometry, serverTime, open)
}
