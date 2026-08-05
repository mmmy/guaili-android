package com.gouge.guaili.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal fun trueRanges(candles: List<Kline>): List<Double> =
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

internal fun pineEma(values: List<Double>, length: Int): List<Double?> {
    val output = MutableList<Double?>(values.size) { null }
    if (values.isEmpty()) return output

    var ema = values.first()
    output[0] = ema
    val alpha = 2.0 / (length.coerceAtLeast(1) + 1.0)
    for (index in 1 until values.size) {
        ema = alpha * values[index] + (1.0 - alpha) * ema
        output[index] = ema
    }
    return output
}

internal fun pineRma(values: List<Double>, length: Int): List<Double?> {
    val safeLength = length.coerceAtLeast(1)
    val output = MutableList<Double?>(values.size) { null }
    if (values.size < safeLength) return output

    var rma = values.take(safeLength).average()
    output[safeLength - 1] = rma
    for (index in safeLength until values.size) {
        rma = (rma * (safeLength - 1) + values[index]) / safeLength
        output[index] = rma
    }
    return output
}

internal fun rollingNearestRankPercentile(
    values: List<Double>,
    length: Int,
    percentile: Double,
): List<Double?> {
    val safeLength = length.coerceAtLeast(1)
    val rank = ceil(percentile.coerceIn(0.0, 100.0) / 100.0 * safeLength)
        .toInt()
        .coerceIn(1, safeLength)

    return values.indices.map { index ->
        if (index + 1 < safeLength) {
            null
        } else {
            values.subList(index + 1 - safeLength, index + 1).sorted()[rank - 1]
        }
    }
}
