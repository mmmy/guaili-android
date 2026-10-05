package com.gouge.guaili.signals

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalRun
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.GuailiSignalTrend
import com.gouge.guaili.domain.guailiIntervalDurationMillis

internal data class ServerSignalDisplayConfig(
    val symbols: List<String>,
    val enabledSignalKinds: Set<GuailiSignalKind> = GuailiSignalKind.entries.toSet(),
)

internal data class ServerSignalItem(val symbol: String, val signal: ServerSignalStructure)

internal data class ServerSignalsState(
    val status: String,
    val message: String,
    val detail: String,
    val signals: List<ServerSignalItem> = emptyList(),
    val warning: String? = null,
    val sampledAt: Long? = null,
    val fetchedAt: Long? = null,
    val nowMillis: Long? = null,
    val evaluationIntervalMs: Long = 5_000L,
    val qualityLabel: String? = null,
    val compactQualityLabel: String? = null,
)

internal enum class ServerSignalDataIssue(val label: String, val compactLabel: String,
    val description: String, val emptyMessage: String) {
    Recovering("连接恢复中", "恢复中", "行情连接恢复中", "行情连接恢复中，暂无法判断信号"),
    SamplingStale("采样超时", "采样旧", "服务器采样结果过期", "服务器采样结果已过期，暂无法判断信号"),
    MarketStale("行情过期", "行情旧", "实时行情过期", "实时行情已过期，暂无法判断信号"),
    Invalid("数据异常", "异常", "行情或计算数据异常", "行情或计算数据异常，暂无法判断信号"),
    Gap("历史断档", "断档", "连续历史存在缺口", "连续历史存在缺口，暂无法判断信号"),
    Missing("动态K缺失", "缺K", "当前动态K缺失", "当前动态K缺失，暂无法判断信号"),
    WaitingMarket("等待行情", "待行情", "等待实时行情快照", "尚未收到实时行情，暂无法判断信号"),
    HistoryWarmup("历史预热", "预热", "连续历史不足", "连续历史不足，暂无法判断信号"),
    IndicatorWarmup("指标预热", "预热", "指标尚未完成预热", "指标尚未完成预热，暂无法判断信号"),
    Unknown("等待数据", "待数据", "数据状态待确认", "数据状态待确认，暂无法判断信号"),
}

internal fun serverSignalDataIssue(evidence: ServerIntervalEvidence): ServerSignalDataIssue? =
    when (evidence.availability) {
        "ready", "filtered" -> null
        "recovering" -> ServerSignalDataIssue.Recovering
        "stale" -> if (evidence.reason == "signal sampling result is stale")
            ServerSignalDataIssue.SamplingStale else ServerSignalDataIssue.MarketStale
        "invalid" -> ServerSignalDataIssue.Invalid
        "gap" -> ServerSignalDataIssue.Gap
        "missing" -> ServerSignalDataIssue.Missing
        "warming_up" -> when (evidence.reason) {
            "insufficient contiguous closed history" -> ServerSignalDataIssue.HistoryWarmup
            "volatility rank is unavailable" -> ServerSignalDataIssue.IndicatorWarmup
            "waiting for a live trade snapshot" -> ServerSignalDataIssue.WaitingMarket
            else -> ServerSignalDataIssue.Unknown
        }
        else -> ServerSignalDataIssue.Unknown
    }

internal fun serverSignalQualityGroups(row: ServerSymbolSignals): Map<ServerSignalDataIssue, List<ServerIntervalEvidence>> {
    val groups = row.perIntervalQuality.mapNotNull { evidence ->
        serverSignalDataIssue(evidence)?.let { it to evidence }
    }.groupBy({ it.first }, { it.second }).toSortedMap(compareBy { it.ordinal })
    if (groups.isEmpty() && row.dataStatus !in setOf("ready", "degraded")) {
        val fallback = serverSignalDataIssue(ServerIntervalEvidence("", row.dataStatus))
        if (fallback != null) groups[fallback] = emptyList()
    } else if (groups.isEmpty() && row.dataStatus == "degraded") {
        groups[ServerSignalDataIssue.Unknown] = emptyList()
    }
    return groups
}

internal fun serverSignalQualityLines(row: ServerSymbolSignals): List<String> =
    serverSignalQualityGroups(row).map { (issue, evidence) ->
        val periods = evidence.joinToString("、") {
            serverDisplayInterval(it.interval) + if (issue == ServerSignalDataIssue.HistoryWarmup)
                "（连续历史${it.historyCount}根）" else ""
        }
        val reason = when (issue) {
            ServerSignalDataIssue.Recovering -> "等待行情连接和数据恢复"
            ServerSignalDataIssue.SamplingStale -> "等待服务器恢复采样"
            ServerSignalDataIssue.MarketStale -> "等待新的实时行情"
            ServerSignalDataIssue.WaitingMarket -> "尚未收到实时行情快照"
            ServerSignalDataIssue.Missing -> if (evidence.any {
                it.reason == "current candle does not cover this market time"
            }) "动态K尚未覆盖最新行情时间" else null
            else -> null
        }
        listOfNotNull(issue.description + if (periods.isNotBlank()) "：$periods" else "", reason)
            .joinToString("；")
    }

internal fun serverSignalsState(
    snapshot: ServerSignalsSnapshot?,
    failure: ServerSignalsFailure?,
    config: ServerSignalDisplayConfig,
    baseUrl: String,
    deviceTime: GuailiDeviceTime,
): ServerSignalsState {
    val failureMessage = failure?.takeIf {
        normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(baseUrl) &&
            it.updatedAt >= (snapshot?.updatedAt ?: 0L)
    }?.message
    if (snapshot == null || !snapshot.belongsTo(baseUrl)) {
        return ServerSignalsState("unavailable", failureMessage ?: "等待服务器信号", "点击刷新获取信号 v2")
    }
    val response = snapshot.response
    if (response.status == "config_error") {
        return ServerSignalsState("config_error", "服务器信号配置有误", "检查服务器配置后刷新",
            fetchedAt = response.serverTime)
    }
    if (!response.enabled || response.status == "disabled") {
        return ServerSignalsState("disabled", "服务器信号计算已关闭", "开启服务器信号计算后再刷新",
            fetchedAt = response.serverTime)
    }
    val time = assessServerSignalsTime(snapshot, deviceTime)
    val now = time.nowMillis
    if (!time.available || now == null) {
        return ServerSignalsState("time_uncertain", time.unavailableReason ?: "时间暂不可确认", "刷新后重新校准时间",
            fetchedAt = time.fetchedAtMillis)
    }
    val lifetime = serverSignalsCacheLifetime(response)
    val evaluatedAt = response.evaluatedAt
    if (time.cacheAgeMillis == null || time.cacheAgeMillis > lifetime ||
        evaluatedAt != null && (evaluatedAt > now + 2_000L || now - evaluatedAt > lifetime)) {
        val cached = time.cacheAgeMillis == null || time.cacheAgeMillis > lifetime
        val future = evaluatedAt != null && evaluatedAt > now + 2_000L
        return ServerSignalsState("stale",
            when { future -> "服务器采样时间异常，暂无法判断信号";
                cached -> "手机快照已过期，请刷新"; else -> "服务器采样结果已过期，暂无法判断信号" },
            when { future -> "采样时间晚于服务器校准时间";
                cached -> "刷新获取最新快照"; else -> "等待服务器恢复采样" },
            warning = failureMessage, sampledAt = evaluatedAt, fetchedAt = time.fetchedAtMillis, nowMillis = now,
            qualityLabel = when { future -> "时间异常"; cached -> "快照过期"; else -> "采样超时" },
            compactQualityLabel = when { future -> "时间异常"; cached -> "缓存旧"; else -> "采样旧" })
    }
    if (response.candleMode != "live" || response.status !in setOf("ready", "degraded", "warming_up")) {
        return ServerSignalsState("unknown", "服务器状态暂不可判断", "刷新或检查服务器版本",
            fetchedAt = time.fetchedAtMillis, nowMillis = now)
    }
    val selected = config.symbols.distinct().mapNotNull { symbol -> response.results.firstOrNull { it.symbol == symbol } }
    val missing = config.symbols.filter { symbol -> selected.none { it.symbol == symbol } }
    val signals = if (evaluatedAt == null) emptyList() else selected.flatMap { row ->
        if (row.dataStatus !in setOf("ready", "degraded") || row.sampledAt <= 0L ||
            row.sampledAt > now + 2_000L || now - row.sampledAt > lifetime) emptyList()
        else serverSignalCandidates(row.signals.filter { signal ->
            serverSignalKind(signal.kind) in config.enabledSignalKinds && validServerSignal(signal) &&
                signal.runs.flatMap { it.intervals }.all { interval ->
                    row.perIntervalQuality.any { it.interval == interval && it.availability == "ready" }
                }
        }.distinctBy { it.id }).map { ServerSignalItem(row.symbol, it) }
    }.sortedWith(compareByDescending<ServerSignalItem> { serverSignalPriority(it.signal) }
        .thenBy { config.symbols.indexOf(it.symbol) })
    val qualityGroups = selected.map { serverSignalQualityGroups(it) }
    val issues = qualityGroups.flatMap { it.keys }.distinct().sortedBy { it.ordinal }
    val hasReady = selected.any { row -> row.perIntervalQuality.any { it.availability == "ready" } }
    val incomplete = selected.any { it.dataStatus != "ready" } || issues.isNotEmpty() || missing.isNotEmpty()
    val qualitySummary = issues.map { issue ->
        val count = qualityGroups.sumOf { it[issue]?.size ?: 0 }
        val affectedSymbols = qualityGroups.count { issue in it }
        (if (count > 0) "${count}个周期" else "${affectedSymbols}个品种") + issue.description
    }
    val warning = listOfNotNull(
        missing.takeIf { it.isNotEmpty() }?.let { "服务器未计算：${it.joinToString("、") { symbol -> symbol.removeSuffix("USDT") }}" },
        qualitySummary.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        failureMessage,
    ).joinToString(" · ").takeIf { it.isNotBlank() }
    val warming = evaluatedAt == null || response.status == "warming_up" ||
        selected.isNotEmpty() && selected.all { it.dataStatus in setOf("warming_up", "recovering") }
    val message = when {
        config.symbols.isEmpty() -> "请先选择监控品种"
        config.enabledSignalKinds.isEmpty() -> "未启用信号类型"
        evaluatedAt == null -> "服务器尚未完成首次采样"
        selected.isEmpty() -> "所选品种暂无服务器数据"
        !hasReady && issues.isNotEmpty() -> issues.first().emptyMessage
        incomplete -> "可用周期暂无符合条件的信号"
        else -> "暂无符合条件的服务器信号"
    }
    return ServerSignalsState(
        status = if (warming) "warming_up" else if (incomplete) "degraded" else "ready",
        message = message, detail = "实时动态K · 服务器采样${response.evaluationIntervalMs / 1000}秒",
        signals = signals, warning = warning, sampledAt = evaluatedAt, fetchedAt = time.fetchedAtMillis,
        nowMillis = now, evaluationIntervalMs = response.evaluationIntervalMs,
        qualityLabel = when {
            missing.isNotEmpty() -> "品种未计算"
            hasReady && issues.firstOrNull() == ServerSignalDataIssue.MarketStale -> "部分过期"
            hasReady && issues.firstOrNull() == ServerSignalDataIssue.HistoryWarmup -> "部分预热"
            else -> issues.firstOrNull()?.label
        },
        compactQualityLabel = if (missing.isNotEmpty()) "缺品种" else issues.firstOrNull()?.compactLabel,
    )
}

/** Select the same winning runs as the original widget, without recalculating indicators. */
internal fun serverSignalCandidates(signals: List<ServerSignalStructure>): List<ServerSignalStructure> {
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

private fun serverSignalPriority(signal: ServerSignalStructure): Int =
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

internal fun validServerSignal(signal: ServerSignalStructure): Boolean =
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

internal fun serverDisplayInterval(interval: String): String =
    if (interval.all(Char::isDigit)) "${interval}m" else interval

/** Adapt server evidence for the shared UI; this never recalculates a signal. */
internal fun serverSignalPresentation(item: ServerSignalItem, response: ServerSignalsResponse,
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

internal fun serverSignalStatusLabel(state: ServerSignalsState, narrow: Boolean): String =
    (if (narrow) state.compactQualityLabel else state.qualityLabel) ?: when (state.status) {
    "ready" -> if (narrow) "✓" else "校时✓"
    "degraded" -> if (narrow) "待数据" else "数据待确认"
    "stale" -> if (narrow) "缓存旧" else "快照过期"
    "disabled" -> "已关闭"
    "warming_up" -> if (narrow) "待数据" else "等待数据"
    "time_uncertain" -> if (narrow) "待校时" else "待校时"
    else -> "详情"
}
