/*
 * Derived from the Pine Script indicator "波幅信号-副图".
 * Copyright (c) gouge99. SPDX-License-Identifier: MPL-2.0
 */
package com.gouge.guaili.domain

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
        return calculateAmplitudeSignalPoints(candles, KlineIndicatorSeries(candles)) { _, point -> point }
    }
}

private inline fun <T> calculateAmplitudeSignalPoints(
    candles: List<Kline>,
    series: KlineIndicatorSeries,
    createResult: (Int, AmplitudeSignalPoint) -> T,
): List<T> {
    val normalizedRanges = DoubleArray(candles.size)
    var signalAtr = 0.0
    for (index in candles.indices) {
        // RMA(1) needs no result array. Retain its exact seed/recurrence even for non-finite inputs.
        signalAtr = if (index == 0) signalAtr + series.trueRanges[index] else
            signalAtr * 0 + series.trueRanges[index]
        val denominator = candles[index].high + candles[index].low
        normalizedRanges[index] = if (denominator == 0.0) 0.0 else signalAtr / denominator * 2.0
    }
    val percentile = (1.0 - SignalPickCount.toDouble() / SignalPercentileLength) * 100.0
    val thresholds = rollingNearestRankPercentile(
        normalizedRanges,
        SignalPercentileLength,
        percentile,
    )

    var barsSinceStrongTop = -1
    var barsSinceStrongBottom = -1
    // Monotonic queues inspect each candle at most twice rather than scanning two windows per bar.
    val highestIndices = IntArray(candles.size)
    val lowestIndices = IntArray(candles.size)
    var highestHead = 0
    var highestTail = 0
    var lowestHead = 0
    var lowestTail = 0
    var lastHighNaN = -1
    var lastLowNaN = -1
    val output = ArrayList<T>(candles.size)

    for (index in candles.indices) {
        val candle = candles[index]
        val windowStart = (index + 1 - SignalPercentileLength).coerceAtLeast(0)
        while (highestHead < highestTail && highestIndices[highestHead] < windowStart) highestHead++
        while (lowestHead < lowestTail && lowestIndices[lowestHead] < windowStart) lowestHead++
        if (candle.high.isNaN()) {
            lastHighNaN = index
        } else {
            while (highestHead < highestTail && candles[highestIndices[highestTail - 1]].high <= candle.high) {
                highestTail--
            }
            highestIndices[highestTail++] = index
        }
        if (candle.low.isNaN()) {
            lastLowNaN = index
        } else {
            while (lowestHead < lowestTail && candles[lowestIndices[lowestTail - 1]].low >= candle.low) {
                lowestTail--
            }
            lowestIndices[lowestTail++] = index
        }
        // min/max over a window containing NaN also produced NaN in the original calculation.
        val highestNow = lastHighNaN < windowStart &&
            candle.high >= candles[highestIndices[highestHead]].high
        val lowestNow = lastLowNaN < windowStart &&
            candle.low <= candles[lowestIndices[lowestHead]].low
        val threshold = if (index >= SignalPercentileLength - 1) thresholds[index] else null
        val currentRange = normalizedRanges[index]
        val strongTop = threshold != null && currentRange > threshold &&
            candle.close > candle.open && highestNow
        val strongBottom = threshold != null && currentRange > threshold &&
            candle.close < candle.open && lowestNow

        barsSinceStrongTop = when {
            strongTop -> 0
            barsSinceStrongTop >= 0 -> barsSinceStrongTop + 1
            else -> -1
        }
        barsSinceStrongBottom = when {
            strongBottom -> 0
            barsSinceStrongBottom >= 0 -> barsSinceStrongBottom + 1
            else -> -1
        }

        val ema = series.ema20[index]
        val guailiAtr = series.atr14[index]
        val guaili = if (index >= KlineAtrLength - 1 && guailiAtr != 0.0) {
            when {
                candle.low > ema -> (candle.low - ema) / guailiAtr
                candle.high < ema -> (ema - candle.high) / guailiAtr
                else -> 0.0
            }
        } else {
            null
        }
        val passesGuaili = guaili != null && guaili in SignalGuailiMin..SignalGuailiMax

        output.add(createResult(
            index,
            AmplitudeSignalPoint(
                normalizedRange = currentRange,
                threshold = threshold,
                guaili = guaili,
                weakTop = barsSinceStrongTop in 0 until WeakSignalWindow &&
                    highestNow && passesGuaili,
                weakBottom = barsSinceStrongBottom in 0 until WeakSignalWindow &&
                    lowestNow && passesGuaili,
            ),
        ))
    }
    return output
}

fun calculateKlineChartRows(candles: List<Kline>): List<KlineChartRow> {
    if (candles.isEmpty()) return emptyList()
    val series = KlineIndicatorSeries(candles)
    return calculateAmplitudeSignalPoints(candles, series) { index, amplitudeSignal ->
        KlineChartRow(
            candle = candles[index],
            channel = channelPoint(candles[index], index, series),
            amplitudeSignal = amplitudeSignal,
        )
    }
}
