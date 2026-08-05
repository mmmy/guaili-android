/*
 * Derived from the Pine Script indicator "波幅信号-副图".
 * Copyright (c) gouge99. SPDX-License-Identifier: MPL-2.0
 */
package com.gouge.guaili.domain

private const val SignalEmaLength = 20
private const val SignalAtrLength = 1
private const val SignalGuailiAtrLength = 14
private const val SignalPercentileLength = 20
private const val SignalPickCount = 2
private const val SignalGuailiMin = 1.0
private const val SignalGuailiMax = 10.0
private const val WeakSignalWindow = 4

object AmplitudeSignalIndicator : KlineIndicator<AmplitudeSignalPoint> {
    override val id: String = "amplitude-signal"
    override val name: String = "波幅信号"

    override fun calculate(candles: List<Kline>): List<AmplitudeSignalPoint> {
        if (candles.isEmpty()) return emptyList()

        val trueRanges = trueRanges(candles)
        val atr = pineRma(trueRanges, SignalAtrLength)
        val atr14 = pineRma(trueRanges, SignalGuailiAtrLength)
        val ema20 = pineEma(candles.map(Kline::close), SignalEmaLength)
        val normalizedRanges = candles.indices.map { index ->
            val denominator = candles[index].high + candles[index].low
            val currentAtr = atr[index]
            if (currentAtr == null || denominator == 0.0) 0.0 else currentAtr / denominator * 2.0
        }
        val percentile = (1.0 - SignalPickCount.toDouble() / SignalPercentileLength) * 100.0
        val thresholds = rollingNearestRankPercentile(
            normalizedRanges,
            SignalPercentileLength,
            percentile,
        )

        var barsSinceStrongTop: Int? = null
        var barsSinceStrongBottom: Int? = null

        return candles.indices.map { index ->
            val candle = candles[index]
            val threshold = thresholds[index]
            val currentRange = normalizedRanges[index]
            val highestNow = candle.high >= candles
                .subList((index + 1 - SignalPercentileLength).coerceAtLeast(0), index + 1)
                .maxOf(Kline::high)
            val lowestNow = candle.low <= candles
                .subList((index + 1 - SignalPercentileLength).coerceAtLeast(0), index + 1)
                .minOf(Kline::low)
            val strongTop = threshold != null && currentRange > threshold &&
                candle.close > candle.open && highestNow
            val strongBottom = threshold != null && currentRange > threshold &&
                candle.close < candle.open && lowestNow

            barsSinceStrongTop = when {
                strongTop -> 0
                barsSinceStrongTop != null -> checkNotNull(barsSinceStrongTop) + 1
                else -> null
            }
            barsSinceStrongBottom = when {
                strongBottom -> 0
                barsSinceStrongBottom != null -> checkNotNull(barsSinceStrongBottom) + 1
                else -> null
            }

            val ema = ema20[index]
            val guailiAtr = atr14[index]
            val guaili = if (ema != null && guailiAtr != null && guailiAtr != 0.0) {
                when {
                    candle.low > ema -> (candle.low - ema) / guailiAtr
                    candle.high < ema -> (ema - candle.high) / guailiAtr
                    else -> 0.0
                }
            } else {
                null
            }
            val passesGuaili = guaili != null && guaili in SignalGuailiMin..SignalGuailiMax

            AmplitudeSignalPoint(
                normalizedRange = currentRange,
                threshold = threshold,
                guaili = guaili,
                weakTop = barsSinceStrongTop?.let { it < WeakSignalWindow } == true &&
                    highestNow && passesGuaili,
                weakBottom = barsSinceStrongBottom?.let { it < WeakSignalWindow } == true &&
                    lowestNow && passesGuaili,
            )
        }
    }
}

fun calculateKlineChartRows(candles: List<Kline>): List<KlineChartRow> {
    val channels = GuailiChannelIndicator.calculate(candles)
    val amplitudeSignals = AmplitudeSignalIndicator.calculate(candles)
    return candles.indices.map { index ->
        KlineChartRow(
            candle = candles[index],
            channel = channels[index],
            amplitudeSignal = amplitudeSignals[index],
        )
    }
}
