package com.gouge.guaili.domain

import kotlin.math.abs

private const val ChannelMultiplier = 1.0
private const val SlopeMultiplier = 0.1

object GuailiChannelIndicator : KlineIndicator<GuailiChannelPoint> {
    override val id: String = "guaili-channel"
    override val name: String = "乖离通道"

    override fun calculate(candles: List<Kline>): List<GuailiChannelPoint> {
        if (candles.isEmpty()) return emptyList()

        val series = KlineIndicatorSeries(candles)
        return List(candles.size) { index -> channelPoint(candles[index], index, series) }
    }
}

fun calculateGuailiChannel(candles: List<Kline>): List<KlineChartRow> {
    if (candles.isEmpty()) return emptyList()
    val series = KlineIndicatorSeries(candles)
    return List(candles.size) { index ->
        val candle = candles[index]
        KlineChartRow(candle = candle, channel = channelPoint(candle, index, series))
    }
}

internal fun channelPoint(candle: Kline, index: Int, series: KlineIndicatorSeries): GuailiChannelPoint {
    val ema = series.ema20[index]
    val atr = if (index >= KlineAtrLength - 1) series.atr14[index] else null
    return GuailiChannelPoint(
        ema20 = ema,
        atr14 = atr,
        upper = if (atr != null) ema + atr * ChannelMultiplier else null,
        lower = if (atr != null) ema - atr * ChannelMultiplier else null,
        guaili = if (atr != null && atr != 0.0) (candle.close - ema) / atr else null,
        trend = channelTrend(index, series),
    )
}

private fun channelTrend(index: Int, series: KlineIndicatorSeries): ChannelTrend {
    if (index < maxOf(3, KlineAtrLength - 1)) return ChannelTrend.Neutral
    val emaValues = series.ema20
    val ema = emaValues[index]
    val previous = emaValues[index - 1]
    val previous2 = emaValues[index - 2]
    val previous3 = emaValues[index - 3]
    val slopeIsStrong = abs(ema - previous3) > series.atr14[index] * SlopeMultiplier

    return when {
        slopeIsStrong && ema > previous && previous > previous2 && previous2 > previous3 ->
            ChannelTrend.Long
        slopeIsStrong && ema < previous && previous < previous2 && previous2 < previous3 ->
            ChannelTrend.Short
        else -> ChannelTrend.Neutral
    }
}
