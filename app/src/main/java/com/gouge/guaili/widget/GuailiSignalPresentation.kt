package com.gouge.guaili.widget

import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.GuailiSignalRun
import com.gouge.guaili.domain.GuailiSignalTrend

internal fun widgetSignals(snapshot: GuailiSnapshot, config: WidgetConfig, nowMillis: Long): List<GuailiSignal> {
    val signals = if (snapshot.signalRuleVersion == GuailiSignalEvolution.RuleVersion) snapshot.signals else {
        GuailiSignalEvolution.evaluate(snapshot.table, nowMillis, snapshot.timezone)
    }
    return GuailiSignalEvolution.visible(signals, snapshot.table, config.symbols, config.enabledSignalKinds,
        nowMillis, snapshot.timezone)
}

// Shared wording keeps the app and desktop cards identical.
internal fun signalTitle(signal: GuailiSignal) = com.gouge.guaili.signals.signalTitle(signal)
internal fun signalSummary(signal: GuailiSignal, maLabel: String) = com.gouge.guaili.signals.signalSummary(signal, maLabel)
internal fun signalChangeText(signal: GuailiSignal) = com.gouge.guaili.signals.signalChangeText(signal)
internal fun signalTrendText(signal: GuailiSignal, maLabel: String) = com.gouge.guaili.signals.signalTrendText(signal, maLabel)
internal fun signalPhaseLabel(signal: GuailiSignal) = com.gouge.guaili.signals.signalPhaseLabel(signal)
internal fun signalRangeLabel(signal: GuailiSignal) = com.gouge.guaili.signals.signalRangeLabel(signal)
internal fun signalRunLabel(run: GuailiSignalRun, short: Boolean, original: Boolean) = com.gouge.guaili.signals.signalRunLabel(run, short, original)
internal fun signalCompactTrend(signal: GuailiSignal, maLabel: String) = com.gouge.guaili.signals.signalCompactTrend(signal, maLabel)

internal fun widgetStatusLabel(status: WidgetDataStatus?): String = when {
    status == null -> "详情"
    status.timeUncertain -> "待校时"
    status.snapshotStale -> "缓存过期"
    status.future > 0 -> "时间异常"
    status.stale > 0 -> "行情过期"
    status.noDataSymbols.isNotEmpty() -> "${status.noDataSymbols.size}品种缺失"
    status.missing > 0 -> "${status.missing}级待确认"
    else -> "校时✓"
}

internal fun singleLineStatusLabel(status: WidgetDataStatus?, narrow: Boolean): String {
    if (!narrow) return widgetStatusLabel(status)
    return when {
        status == null -> "详情"
        status.timeUncertain -> "待校时"
        status.snapshotStale -> "缓存旧"
        status.future > 0 -> "异常"
        status.stale > 0 -> "过期"
        status.noDataSymbols.isNotEmpty() -> "缺${status.noDataSymbols.size}"
        status.missing > 0 -> "待确认"
        else -> "✓"
    }
}

internal fun singleLineRefreshLabel(status: WidgetRefreshStatus, snapshot: GuailiSnapshot?,
    dataStatus: WidgetDataStatus?, seconds: Boolean, narrow: Boolean): String {
    val feedback = refreshFeedbackText(status, snapshotUpdatedAt = snapshot?.updatedAt)
    when (feedback) {
        "刷新中…" -> return "刷新中"
        "刷新超时，重试" -> return if (narrow) "超时" else "刷新超时"
        "刷新失败，请重试" -> return if (narrow) "失败" else "刷新失败"
        "保存失败，请重试" -> return if (narrow) "失败" else "保存失败"
    }
    val fetched = dataStatus?.fetchedAtMillis ?: snapshot?.updatedAt ?: return "待刷新"
    return java.time.format.DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm")
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(fetched))
}
