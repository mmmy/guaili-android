package com.gouge.guaili.signals

import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignalPhase

/** Widths come from each platform's actual font metrics, including accessibility scaling. */
internal data class SignalCompactLayout(
    val inline: Boolean,
    val symbolWidth: Float,
    val showMeta: Boolean,
)

internal fun signalCompactLayout(width: Float, symbol: Float, ranges: List<Float>, metas: List<Float>, gap: Float): SignalCompactLayout {
    val widestRange = ranges.maxOrNull() ?: 0f
    val widestMeta = metas.maxOrNull() ?: 0f
    if (ranges.size == 1) {
        return SignalCompactLayout(symbol + widestRange + widestMeta + 2 * gap <= width, symbol, true)
    }
    val symbolWidth = symbol.coerceAtMost((width - widestRange - gap).coerceAtLeast(0f))
    return SignalCompactLayout(true, symbolWidth, symbolWidth + widestRange + widestMeta + 2 * gap <= width)
}

internal fun signalCompactLabel(signal: GuailiSignal): String {
    val kind = when (signal.kind) {
        GuailiSignalKind.Compression -> "近"
        GuailiSignalKind.Conflict -> "分歧"
        GuailiSignalKind.Extreme -> when (signal.primaryRun.direction) {
            GuailiSignalDirection.Positive -> "共振↑"
            GuailiSignalDirection.Negative -> "共振↓"
            GuailiSignalDirection.Neutral -> "共振"
        }
    }
    val phase = when (signal.phase) {
        GuailiSignalPhase.FirstObserved -> "首次"
        GuailiSignalPhase.Formed -> "新出现"
        GuailiSignalPhase.Ongoing -> "持续"
        GuailiSignalPhase.RangeChanged -> "变化"
        GuailiSignalPhase.Narrowing -> "收窄"
        GuailiSignalPhase.Widening -> "扩大"
        GuailiSignalPhase.UpwardDeparture -> "离开↑"
        GuailiSignalPhase.DownwardDeparture -> "离开↓"
        GuailiSignalPhase.DivergenceWidening -> "扩大"
        GuailiSignalPhase.DivergenceEasing -> "缓和"
        GuailiSignalPhase.AlignedPositive -> "同正"
        GuailiSignalPhase.AlignedNegative -> "同负"
        GuailiSignalPhase.Ended -> "解除"
    }
    return (if (signal.transitionOnly) "原 " else "") + "$kind · $phase · ${signal.totalLevelCount}级"
}

internal fun signalCompactRanges(signal: GuailiSignal): List<String> =
    if (signal.kind == GuailiSignalKind.Conflict) signal.runs.map { signalRunRangeLabel(it, signal.transitionOnly).replace("原 ", "原") }
    else listOf(signalRunRangeLabel(signal.primaryRun, false))
