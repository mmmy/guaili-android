package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalRun
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.GuailiSignalTrend
import com.gouge.guaili.domain.guailiIntervalDurationMillis

internal data class ServerWidgetSignal(val symbol: String, val signal: ServerSignalStructure)

internal data class ServerSignalsWidgetState(
    val status: String,
    val message: String,
    val detail: String,
    val signals: List<ServerWidgetSignal> = emptyList(),
    val warning: String? = null,
    val sampledAt: Long? = null,
    val fetchedAt: Long? = null,
    val nowMillis: Long? = null,
    val evaluationIntervalMs: Long = 5_000L,
)

internal fun serverSignalsWidgetState(
    snapshot: ServerSignalsSnapshot?,
    failure: ServerSignalsFailure?,
    config: WidgetConfig,
    baseUrl: String,
    deviceTime: GuailiDeviceTime,
): ServerSignalsWidgetState {
    val failureMessage = failure?.takeIf {
        normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(baseUrl) &&
            it.updatedAt >= (snapshot?.updatedAt ?: 0L)
    }?.message
    if (snapshot == null || !snapshot.belongsTo(baseUrl)) {
        return ServerSignalsWidgetState("unavailable", failureMessage ?: "等待服务器信号", "点击刷新获取信号 v2")
    }
    val response = snapshot.response
    if (response.status == "config_error") {
        return ServerSignalsWidgetState("config_error", "服务器信号配置有误", "检查服务器配置后刷新",
            fetchedAt = response.serverTime)
    }
    if (!response.enabled || response.status == "disabled") {
        return ServerSignalsWidgetState("disabled", "服务器信号计算已关闭", "开启服务器信号计算后再刷新",
            fetchedAt = response.serverTime)
    }
    val time = assessServerSignalsTime(snapshot, deviceTime)
    val now = time.nowMillis
    if (!time.available || now == null) {
        return ServerSignalsWidgetState("time_uncertain", time.unavailableReason ?: "时间暂不可确认", "刷新后重新校准时间",
            fetchedAt = time.fetchedAtMillis)
    }
    val lifetime = serverSignalsCacheLifetime(response)
    val evaluatedAt = response.evaluatedAt
    if (time.cacheAgeMillis == null || time.cacheAgeMillis > lifetime ||
        evaluatedAt != null && (evaluatedAt > now + 2_000L || now - evaluatedAt > lifetime)) {
        return ServerSignalsWidgetState("stale", "服务器快照已过期，请刷新", "桌面更新受系统限制，可手动刷新",
            sampledAt = evaluatedAt, fetchedAt = time.fetchedAtMillis, nowMillis = now)
    }
    if (response.candleMode != "live" || response.status !in setOf("ready", "degraded", "warming_up")) {
        return ServerSignalsWidgetState("unknown", "服务器状态暂不可判断", "刷新或检查服务器版本",
            fetchedAt = time.fetchedAtMillis, nowMillis = now)
    }
    val selected = config.symbols.distinct().mapNotNull { symbol -> response.results.firstOrNull { it.symbol == symbol } }
    val missing = config.symbols.filter { symbol -> selected.none { it.symbol == symbol } }
    val signals = if (evaluatedAt == null) emptyList() else selected.flatMap { row ->
        if (row.dataStatus !in setOf("ready", "degraded") || row.sampledAt <= 0L ||
            row.sampledAt > now + 2_000L || now - row.sampledAt > lifetime) emptyList()
        else serverWidgetCandidates(row.signals.filter { signal ->
            serverSignalKind(signal.kind) in config.enabledSignalKinds && validServerSignal(signal) &&
                signal.runs.flatMap { it.intervals }.all { interval ->
                    row.perIntervalQuality.any { it.interval == interval && it.availability == "ready" }
                }
        }.distinctBy { it.id }).map { ServerWidgetSignal(row.symbol, it) }
    }.sortedWith(compareByDescending<ServerWidgetSignal> { serverWidgetPriority(it.signal) }
        .thenBy { config.symbols.indexOf(it.symbol) })
    val incomplete = selected.any { it.dataStatus != "ready" } || missing.isNotEmpty()
    val warning = listOfNotNull(
        missing.takeIf { it.isNotEmpty() }?.let { "服务器未计算：${it.joinToString("、") { symbol -> symbol.removeSuffix("USDT") }}" },
        if (incomplete && selected.isNotEmpty()) "部分周期正在预热或暂不可用" else null,
        failureMessage,
    ).joinToString(" · ").takeIf { it.isNotBlank() }
    val warming = evaluatedAt == null || response.status == "warming_up" ||
        selected.isNotEmpty() && selected.all { it.dataStatus in setOf("warming_up", "recovering") }
    val message = when {
        config.symbols.isEmpty() -> "请先选择监控品种"
        config.enabledSignalKinds.isEmpty() -> "未启用信号类型"
        warming -> "服务器正在准备实时信号"
        selected.isEmpty() -> "所选品种暂无服务器数据"
        selected.all { it.dataStatus in setOf("stale", "recovering", "invalid") } -> "服务器行情暂不可判断"
        else -> "暂无符合条件的服务器信号"
    }
    return ServerSignalsWidgetState(
        status = if (warming) "warming_up" else if (incomplete) "degraded" else "ready",
        message = message, detail = "实时动态K · 服务器采样${response.evaluationIntervalMs / 1000}秒",
        signals = signals, warning = warning, sampledAt = evaluatedAt, fetchedAt = time.fetchedAtMillis,
        nowMillis = now, evaluationIntervalMs = response.evaluationIntervalMs,
    )
}

/** Select the same winning runs as the original widget, without recalculating indicators. */
internal fun serverWidgetCandidates(signals: List<ServerSignalStructure>): List<ServerSignalStructure> {
    val extremeComparator = compareBy<ServerSignalStructure> { it.runs.single().intervals.size }
        .thenBy { it.runs.single().minAbsValue }
        .thenBy { guailiIntervalDurationMillis(it.anchorInterval) }
    val compressionComparator = compareBy<ServerSignalStructure> { it.runs.single().intervals.size }
        .thenByDescending { it.runs.single().maxAbsGuaili ?: it.runs.single().maxAbsValue / 10.0 }
        .thenByDescending { it.runs.single().meanAbsGuaili ?: it.runs.single().meanAbsValue / 10.0 }
        .thenBy { guailiIntervalDurationMillis(it.anchorInterval) }
    val conflict = signals.filter { it.kind == "conflict" && it.runs.size == 2 }
        .maxWithOrNull(compareBy<ServerSignalStructure> { it.runs.sumOf { run -> run.intervals.size } }
            .thenBy { guailiIntervalDurationMillis(it.anchorInterval) })
    val extremes = signals.filter { it.kind == "extreme" && it.runs.size == 1 }
        .groupBy { it.runs.single().direction }.values.mapNotNull { it.maxWithOrNull(extremeComparator) }
    val compression = signals.filter { it.kind == "compression" && it.runs.size == 1 }
        .maxWithOrNull(compressionComparator)
    // The old visible() suppresses all extremes for a symbol with a visible conflict.
    return listOfNotNull(conflict) + (if (conflict == null) extremes else emptyList()) + listOfNotNull(compression)
}

private fun serverWidgetPriority(signal: ServerSignalStructure): Int =
    when (signal.kind) { "conflict" -> 400; "extreme" -> 200; else -> 100 } +
        signal.runs.sumOf { it.intervals.size }

internal fun serverSignalsCacheLifetime(response: ServerSignalsResponse): Long =
    maxOf(15_000L, response.evaluationIntervalMs.coerceIn(1_000L, 300_000L) * 3)

internal fun serverSignalKind(kind: String): GuailiSignalKind? = when (kind) {
    "extreme" -> GuailiSignalKind.Extreme
    "compression" -> GuailiSignalKind.Compression
    "conflict" -> GuailiSignalKind.Conflict
    else -> null
}

private fun validServerSignal(signal: ServerSignalStructure): Boolean =
    signal.id.isNotBlank() && signal.runs.isNotEmpty() && signal.levelCount > 0 &&
        guailiIntervalDurationMillis(signal.anchorInterval) in 1 until Long.MAX_VALUE &&
        signal.runs.all { run -> run.intervals.isNotEmpty() &&
            run.direction in setOf("positive", "negative", "neutral") &&
            run.intervals.all { guailiIntervalDurationMillis(it) in 1 until Long.MAX_VALUE } } &&
        signal.runs.any { signal.anchorInterval in it.intervals }

internal fun serverSignalTitle(signal: ServerSignalStructure): String = when (signal.kind) {
    "conflict" -> "长短周期分歧"
    "compression" -> "多周期近均线"
    else -> when (signal.direction) { "positive" -> "上方乖离共振"; "negative" -> "下方乖离共振"; else -> "乖离共振" }
}

internal fun serverSignalRange(signal: ServerSignalStructure): String = signal.runs.mapIndexed { index, run ->
    val range = "${serverDisplayInterval(run.intervals.first())}–${serverDisplayInterval(run.intervals.last())}"
    if (signal.kind == "conflict") "${if (index == 0) "短" else "长"}${if (run.direction == "positive") "正" else "负"} $range"
    else "$range · ${run.intervals.size}级${if (signal.kind == "compression") "近均线" else "共振"}"
}.joinToString(" · ")

internal fun serverSignalPhase(signal: ServerSignalStructure, nowMillis: Long?, intervalMillis: Long): String {
    val recent = intervalMillis.coerceIn(1000, 300000) * 2
    fun isRecent(at: Long?): Boolean = at != null && nowMillis != null && nowMillis - at in 0..recent
    return when {
        signal.lastChangedAt != signal.firstObservedAt && isRecent(signal.lastChangedAt) -> "动态 · 区间变化"
        isRecent(signal.formedAt) -> "动态 · 新出现"
        signal.formedAt == null && isRecent(signal.firstObservedAt) -> "动态 · 首次观测"
        else -> "动态 · 持续"
    }
}

private fun serverDisplayInterval(interval: String): String =
    if (interval.all(Char::isDigit)) "${interval}m" else interval

/** Adapt server evidence for the shared UI; this never recalculates a signal. */
internal fun serverWidgetPresentation(item: ServerWidgetSignal, response: ServerSignalsResponse,
    nowMillis: Long?): GuailiSignal? {
    val kind = serverSignalKind(item.signal.kind) ?: return null
    fun direction(value: String) = when (value) {
        "positive" -> GuailiSignalDirection.Positive
        "negative" -> GuailiSignalDirection.Negative
        else -> GuailiSignalDirection.Neutral
    }
    val signal = item.signal
    val runs = signal.runs.filter { it.intervals.isNotEmpty() }.map {
        GuailiSignalRun(direction(it.direction), it.intervals, it.minAbsValue,
            meanAbsGuaili = it.meanAbsGuaili ?: it.meanAbsValue / 10.0,
            maxAbsGuaili = it.maxAbsGuaili ?: it.maxAbsValue / 10.0)
    }
    if (runs.isEmpty()) return null
    val anchor = response.results.firstOrNull { it.symbol == item.symbol }?.perIntervalQuality
        ?.firstOrNull { it.interval == signal.anchorInterval && it.availability == "ready" }
    val trend = when {
        anchor?.longTrend == null || anchor.shortTrend == null -> GuailiSignalTrend.Unknown
        anchor.longTrend && anchor.shortTrend -> GuailiSignalTrend.Unknown
        anchor.longTrend -> GuailiSignalTrend.Up
        anchor.shortTrend -> GuailiSignalTrend.Down
        else -> GuailiSignalTrend.Flat
    }
    val phase = when (serverSignalPhase(signal, nowMillis, response.evaluationIntervalMs)) {
        "动态 · 区间变化" -> GuailiSignalPhase.RangeChanged
        "动态 · 新出现" -> GuailiSignalPhase.Formed
        "动态 · 首次观测" -> GuailiSignalPhase.FirstObserved
        else -> GuailiSignalPhase.Ongoing
    }
    return GuailiSignal(item.symbol, kind, runs, phase = phase, trend = trend, observedAt = signal.firstObservedAt)
}

internal fun serverMovingAverageLabel(response: ServerSignalsResponse): String {
    val indicator = response.indicatorConfig ?: return "均线（参数未知）"
    return if (indicator.maType.isNotBlank() && indicator.maLength > 0)
        "${indicator.maType.uppercase()}${indicator.maLength}" else "均线（参数未知）"
}

internal fun serverWidgetStatusLabel(state: ServerSignalsWidgetState, narrow: Boolean): String = when (state.status) {
    "ready" -> if (narrow) "✓" else "校时✓"
    "degraded" -> if (narrow) "不全" else "数据不全"
    "stale" -> if (narrow) "缓存旧" else "快照过期"
    "disabled" -> "已关闭"
    "warming_up" -> if (narrow) "预热" else "预热中"
    "time_uncertain" -> if (narrow) "待校时" else "待校时"
    else -> "详情"
}
