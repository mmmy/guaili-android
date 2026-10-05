package com.gouge.guaili.signals

import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.GuailiSignalRun
import com.gouge.guaili.domain.GuailiSignalTrend

internal fun signalTitle(signal: GuailiSignal): String {
    val name = when (signal.kind) {
        GuailiSignalKind.Conflict -> "长短周期分歧"
        GuailiSignalKind.Compression -> "多周期近均线"
        GuailiSignalKind.Extreme -> when (signal.primaryRun.direction) {
            GuailiSignalDirection.Positive -> "上方乖离共振"
            GuailiSignalDirection.Negative -> "下方乖离共振"
            GuailiSignalDirection.Neutral -> "乖离共振"
        }
    }
    return if (signal.transitionOnly) "$name · 变化" else name
}

internal fun signalSummary(signal: GuailiSignal, maLabel: String): String {
    val range = when (signal.kind) {
        GuailiSignalKind.Conflict -> signal.runs.mapIndexed { index, run ->
            val sign = if (run.direction == GuailiSignalDirection.Positive) "正" else "负"
            "${if (index == 0) "短" else "长"}$sign ${runRange(run)}（${run.levelCount}级）"
        }.joinToString(" · ")
        GuailiSignalKind.Extreme -> "${runRange(signal.primaryRun)} · ${signal.primaryRun.levelCount}级共振"
        GuailiSignalKind.Compression -> "${runRange(signal.primaryRun)} · ${signal.primaryRun.levelCount}级接近$maLabel"
    }
    return (if (signal.transitionOnly) "原区间：" else "") + range
}

internal fun signalChangeText(signal: GuailiSignal): String = "观察 · " + when (signal.phase) {
    GuailiSignalPhase.FirstObserved -> "首次观测"
    GuailiSignalPhase.Formed -> "新出现"
    GuailiSignalPhase.Ongoing -> if (signal.kind == GuailiSignalKind.Compression) "持续近零" else "持续"
    GuailiSignalPhase.RangeChanged -> "覆盖区间变化"
    GuailiSignalPhase.Narrowing -> "乖离收窄"
    GuailiSignalPhase.Widening -> "乖离扩大"
    GuailiSignalPhase.UpwardDeparture -> "短端向上离开近零区"
    GuailiSignalPhase.DownwardDeparture -> "短端向下离开近零区"
    GuailiSignalPhase.DivergenceWidening -> "分歧扩大"
    GuailiSignalPhase.DivergenceEasing -> "分歧缓和"
    GuailiSignalPhase.AlignedPositive -> "原区间转为同向正乖离"
    GuailiSignalPhase.AlignedNegative -> "原区间转为同向负乖离"
    GuailiSignalPhase.Ended -> "条件已不满足"
}

internal fun signalTrendText(signal: GuailiSignal, maLabel: String): String {
    val anchor = displaySignalInterval(signal.anchorInterval)
    return when (signal.trend) {
        GuailiSignalTrend.Up -> "$anchor $maLabel 上行" + if (
            !signal.transitionOnly && signal.primaryRun.direction == GuailiSignalDirection.Negative
        ) " · 回调结构观察" else ""
        GuailiSignalTrend.Down -> "$anchor $maLabel 下行" + if (
            !signal.transitionOnly && signal.primaryRun.direction == GuailiSignalDirection.Positive
        ) " · 反弹结构观察" else ""
        GuailiSignalTrend.Flat -> "$anchor $maLabel 未形成连续方向"
        GuailiSignalTrend.Unknown -> "$anchor 趋势数据不足"
    }
}

internal fun signalPhaseLabel(signal: GuailiSignal): String = when (signal.phase) {
    GuailiSignalPhase.FirstObserved -> "首次观测"
    GuailiSignalPhase.Formed -> "新出现"
    GuailiSignalPhase.Ongoing -> if (signal.kind == GuailiSignalKind.Compression) "持续近零" else "持续"
    GuailiSignalPhase.RangeChanged -> "区间变化"
    GuailiSignalPhase.Narrowing -> "乖离收窄"
    GuailiSignalPhase.Widening -> "乖离扩大"
    GuailiSignalPhase.UpwardDeparture -> "向上离开"
    GuailiSignalPhase.DownwardDeparture -> "向下离开"
    GuailiSignalPhase.DivergenceWidening -> "分歧扩大"
    GuailiSignalPhase.DivergenceEasing -> "分歧缓和"
    GuailiSignalPhase.AlignedPositive -> "同向为正"
    GuailiSignalPhase.AlignedNegative -> "同向为负"
    GuailiSignalPhase.Ended -> "条件解除"
}

internal fun signalRangeLabel(signal: GuailiSignal): String =
    (if (signal.transitionOnly) "原 " else "") + runRange(signal.primaryRun)

internal fun signalRunLabel(run: GuailiSignalRun, short: Boolean, original: Boolean): String {
    val direction = if (run.direction == GuailiSignalDirection.Positive) "正" else "负"
    return "${if (original) "原" else ""}${if (short) "短" else "长"}$direction ${runRange(run)} · ${run.levelCount}级"
}

internal fun signalCompactTrend(signal: GuailiSignal, maLabel: String): String {
    val direction = when (signal.trend) {
        GuailiSignalTrend.Up -> "↑"
        GuailiSignalTrend.Down -> "↓"
        GuailiSignalTrend.Flat -> "未定"
        GuailiSignalTrend.Unknown -> "未知"
    }
    val context = if (signal.kind != GuailiSignalKind.Conflict || signal.transitionOnly) "" else when {
        signal.trend == GuailiSignalTrend.Up && signal.primaryRun.direction == GuailiSignalDirection.Negative -> " · 回调观察"
        signal.trend == GuailiSignalTrend.Down && signal.primaryRun.direction == GuailiSignalDirection.Positive -> " · 反弹观察"
        else -> ""
    }
    val compactMa = if (maLabel.contains("参数未知")) "均线?" else maLabel
    return "$compactMa $direction$context"
}

private fun runRange(run: GuailiSignalRun): String =
    "${displaySignalInterval(run.startInterval)}–${displaySignalInterval(run.endInterval)}"

private fun displaySignalInterval(interval: String): String =
    if (interval.all(Char::isDigit)) "${interval}m" else interval
