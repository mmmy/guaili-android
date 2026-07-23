package com.gouge.guaili.ui

import androidx.compose.ui.graphics.Color

internal val LongTrendTextColor = Color(0xFF69F0AE)
internal val ShortTrendTextColor = Color(0xFFFF8A80)
internal val ConflictTrendTextColor = Color(0xFFFFD740)
internal val NeutralTrendTextColor = Color(0xFFD1D5DB)

internal enum class TrendState(val label: String) {
    Long("Long"),
    Short("Short"),
    Conflict("Conflict"),
    Neutral("Neutral"),
}

internal fun trendState(longTrend: Boolean?, shortTrend: Boolean?): TrendState = when {
    longTrend == true && shortTrend == true -> TrendState.Conflict
    longTrend == true -> TrendState.Long
    shortTrend == true -> TrendState.Short
    else -> TrendState.Neutral
}

internal fun trendTextColor(state: TrendState): Color = when (state) {
    TrendState.Long -> LongTrendTextColor
    TrendState.Short -> ShortTrendTextColor
    TrendState.Conflict -> ConflictTrendTextColor
    TrendState.Neutral -> NeutralTrendTextColor
}
