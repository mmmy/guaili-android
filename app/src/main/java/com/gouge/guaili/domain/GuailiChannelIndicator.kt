package com.gouge.guaili.domain

import kotlin.math.abs
import kotlin.math.max

private const val EmaLength = 20
private const val AtrLength = 14
private const val ChannelMultiplier = 1.0
private const val SlopeMultiplier = 0.1

object GuailiChannelIndicator : KlineIndicator<GuailiChannelPoint> {
    override val id: String = "guaili-channel"
    override val name: String = "乖离通道"

    override fun calculate(candles: List<Kline>): List<GuailiChannelPoint> {
        if (candles.isEmpty()) return emptyList()

        val ema20 = pineEma(candles.map(Kline::close), EmaLength)
        val atr14 = pineRma(trueRanges(candles), AtrLength)

        return candles.indices.map { index ->
            val ema = ema20[index]
            val atr = atr14[index]
            GuailiChannelPoint(
                ema20 = ema,
                atr14 = atr,
                upper = if (ema != null && atr != null) ema + atr * ChannelMultiplier else null,
                lower = if (ema != null && atr != null) ema - atr * ChannelMultiplier else null,
                guaili = if (ema != null && atr != null && atr != 0.0) {
                    (candles[index].close - ema) / atr
                } else {
                    null
                },
                trend = channelTrend(index, ema20, atr14),
            )
        }
    }
}

fun calculateGuailiChannel(candles: List<Kline>): List<KlineChartRow> =
    candles.zip(GuailiChannelIndicator.calculate(candles)) { candle, channel ->
        KlineChartRow(candle = candle, channel = channel)
    }

private fun channelTrend(
    index: Int,
    emaValues: List<Double?>,
    atrValues: List<Double?>,
): ChannelTrend {
    if (index < 3) return ChannelTrend.Neutral
    val ema = emaValues[index] ?: return ChannelTrend.Neutral
    val previous = emaValues[index - 1] ?: return ChannelTrend.Neutral
    val previous2 = emaValues[index - 2] ?: return ChannelTrend.Neutral
    val previous3 = emaValues[index - 3] ?: return ChannelTrend.Neutral
    val atr = atrValues[index] ?: return ChannelTrend.Neutral
    val slopeIsStrong = abs(ema - previous3) > atr * SlopeMultiplier

    return when {
        slopeIsStrong && ema > previous && previous > previous2 && previous2 > previous3 ->
            ChannelTrend.Long
        slopeIsStrong && ema < previous && previous < previous2 && previous2 < previous3 ->
            ChannelTrend.Short
        else -> ChannelTrend.Neutral
    }
}

private fun trueRanges(candles: List<Kline>): List<Double> =
    candles.mapIndexed { index, candle ->
        if (index == 0) {
            candle.high - candle.low
        } else {
            val previousClose = candles[index - 1].close
            max(
                candle.high - candle.low,
                max(abs(candle.high - previousClose), abs(candle.low - previousClose)),
            )
        }
    }

private fun pineEma(values: List<Double>, length: Int): List<Double?> {
    val output = MutableList<Double?>(values.size) { null }
    if (values.isEmpty()) return output

    var ema = values.first()
    output[0] = ema
    val alpha = 2.0 / (length + 1.0)
    for (index in 1 until values.size) {
        ema = alpha * values[index] + (1.0 - alpha) * ema
        output[index] = ema
    }
    return output
}

private fun pineRma(values: List<Double>, length: Int): List<Double?> {
    val output = MutableList<Double?>(values.size) { null }
    if (values.size < length) return output

    var rma = values.take(length).average()
    output[length - 1] = rma
    for (index in length until values.size) {
        rma = (rma * (length - 1) + values[index]) / length
        output[index] = rma
    }
    return output
}
