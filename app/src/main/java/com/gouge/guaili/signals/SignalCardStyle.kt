package com.gouge.guaili.signals

import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalKind

/** Shared layout tokens and widget day/night colors. The app maps colors from its MaterialTheme. */
internal object SignalCardStyle {
    const val SymbolWidthDp = 52
    const val HorizontalPaddingDp = 8
    const val VerticalPaddingDp = 4
    const val CardSpacingDp = 5
    const val TitleFontSp = 12
    const val BodyFontSp = 10
    const val HeaderFontSp = 13
    val Light = SignalCardPalette(0xFFF9FAFB, 0xFFF1F3F5, 0xFFE3E7EC, 0xFF17191D,
        0xFF62666D, 0xFF06722D, 0xFFB0003A, 0xFF315EFB, 0xFFB45309)
    val Dark = SignalCardPalette(0xFF17191D, 0xFF24272D, 0xFF343B45, 0xFFF3F4F6,
        0xFFB7BBC3, 0xFF69F0AE, 0xFFFF8A80, 0xFF9DB2FF, 0xFFFBBF24)
}

internal data class SignalCardPalette(val background: Long, val card: Long, val badge: Long,
    val primary: Long, val secondary: Long, val positive: Long, val negative: Long, val accent: Long, val warning: Long) {
    fun titleColor(signal: GuailiSignal): Long = when (signal.kind) {
        GuailiSignalKind.Compression -> accent
        GuailiSignalKind.Conflict -> warning
        GuailiSignalKind.Extreme -> when (signal.primaryRun.direction) {
            GuailiSignalDirection.Positive -> positive
            GuailiSignalDirection.Negative -> negative
            GuailiSignalDirection.Neutral -> secondary
        }
    }
}
