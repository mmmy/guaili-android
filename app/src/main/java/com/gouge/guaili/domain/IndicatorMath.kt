package com.gouge.guaili.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal const val KlineEmaLength = 20
internal const val KlineAtrLength = 14

/** Primitive series shared by every indicator calculated for one exact candle history. */
internal class KlineIndicatorSeries(candles: List<Kline>) {
    val trueRanges = DoubleArray(candles.size)
    val ema20 = DoubleArray(candles.size)
    // The first KlineAtrLength - 1 slots are uninitialized warm-up values, not real zeroes.
    val atr14 = DoubleArray(candles.size)

    init {
        val alpha = 2.0 / (KlineEmaLength + 1.0)
        var atrSeedSum = 0.0
        for (index in candles.indices) {
            val candle = candles[index]
            val range = if (index == 0) {
                candle.high - candle.low
            } else {
                val previousClose = candles[index - 1].close
                max(
                    candle.high - candle.low,
                    max(abs(candle.high - previousClose), abs(candle.low - previousClose)),
                )
            }
            trueRanges[index] = range
            ema20[index] = if (index == 0) candle.close else
                alpha * candle.close + (1.0 - alpha) * ema20[index - 1]

            // Keep the original first-window arithmetic mean seed and RMA operation order.
            if (index < KlineAtrLength) atrSeedSum += range
            when {
                index == KlineAtrLength - 1 -> atr14[index] = atrSeedSum / KlineAtrLength
                index >= KlineAtrLength -> atr14[index] =
                    (atr14[index - 1] * (KlineAtrLength - 1) + range) / KlineAtrLength
            }
        }
    }
}

/** Warm-up slots must be read only once [length] values are available. */
internal fun rollingNearestRankPercentile(
    values: DoubleArray,
    length: Int,
    percentile: Double,
): DoubleArray {
    val safeLength = length.coerceAtLeast(1)
    val rank = ceil(percentile.coerceIn(0.0, 100.0) / 100.0 * safeLength)
        .toInt()
        .coerceIn(1, safeLength)

    val output = DoubleArray(values.size)
    if (values.size < safeLength) return output
    // Keep one primitive sorted window instead of allocating and sorting each candle's window.
    val window = values.copyOf(safeLength)
    java.util.Arrays.sort(window)
    output[safeLength - 1] = window[rank - 1]
    for (index in safeLength until values.size) {
        val outgoing = java.util.Arrays.binarySearch(window, values[index - safeLength])
        window.copyInto(window, outgoing, outgoing + 1, safeLength)
        val search = java.util.Arrays.binarySearch(window, 0, safeLength - 1, values[index])
        val insertion = if (search >= 0) search else -search - 1
        window.copyInto(window, insertion + 1, insertion, safeLength - 1)
        window[insertion] = values[index]
        output[index] = window[rank - 1]
    }
    return output
}

internal fun rollingNearestRankPercentile(
    values: List<Double>,
    length: Int,
    percentile: Double,
): List<Double?> {
    val output = rollingNearestRankPercentile(values.toDoubleArray(), length, percentile)
    val firstValidIndex = length.coerceAtLeast(1) - 1
    return List(values.size) { index -> if (index < firstValidIndex) null else output[index] }
}
