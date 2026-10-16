package com.gouge.guaili.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class KlineIndicatorParityTest {
    @Test
    fun sharedAndStandaloneCalculationsMatchReferenceAcrossWarmupBoundaries() {
        val candles = variedCandles(32)
        for (size in 0..32) assertReferenceParity("size=$size", candles.take(size))
    }

    @Test
    fun flatAndZeroRangeCandlesRetainNullGuailiAndOriginalSeeds() {
        for (price in listOf(0.0, -0.0, 100.0)) {
            val flat = List(80) { index -> candle(index, price, price, price, price) }
            assertReferenceParity("flat=$price", flat)
        }
    }

    @Test
    fun mixedGapsDirectionsAndRepeatedExtremaMatchReference() {
        for (seed in 0..5) assertReferenceParity("seed=$seed", variedCandles(400, seed))
    }

    @Test
    fun slidingHistoryIsRecalculatedFromEachWindowSeed() {
        val history = variedCandles(340)
        for (start in 0..40) {
            assertReferenceParity("windowStart=$start", history.subList(start, start + 300))
        }
    }

    @Test
    fun revisedLiveAndHistoricalCandlesDoNotReusePriorSeries() {
        val original = variedCandles(300)
        assertReferenceParity("original", original)
        for (revisedIndex in listOf(0, 13, 19, 150, 299)) {
            val revised = original.toMutableList()
            val old = revised[revisedIndex]
            revised[revisedIndex] = old.copy(
                close = old.close + 15.0,
                high = old.high + 20.0,
                low = old.low - 5.0,
                isClosed = revisedIndex != 299,
            )
            assertReferenceParity("revised=$revisedIndex", revised)
        }
    }

    @Test
    fun nonFiniteAndSignedZeroValuesKeepOriginalPropagationAndWindowSemantics() {
        val history = variedCandles(100).toMutableList()
        history[0] = candle(0, -0.0, -0.0, 0.0, -0.0)
        history[2] = candle(2, 0.0, 1.0, -1.0, 0.0)
        history[20] = history[20].copy(high = Double.NaN)
        history[41] = history[41].copy(low = Double.NaN)
        history[63] = history[63].copy(high = Double.POSITIVE_INFINITY)
        history[84] = history[84].copy(low = Double.NEGATIVE_INFINITY)
        assertReferenceParity("specialValues", history)
    }

    private fun assertReferenceParity(label: String, candles: List<Kline>) {
        val channels = referenceChannels(candles)
        val amplitude = referenceAmplitude(candles)
        val expectedRows = candles.indices.map { index ->
            KlineChartRow(candles[index], channels[index], amplitude[index])
        }
        assertEquals("$label standalone channels", channels, GuailiChannelIndicator.calculate(candles))
        assertEquals("$label standalone amplitude", amplitude, AmplitudeSignalIndicator.calculate(candles))
        assertEquals("$label combined rows", expectedRows, calculateKlineChartRows(candles))
        assertEquals(
            "$label channel-only rows",
            candles.indices.map { index -> KlineChartRow(candles[index], channels[index]) },
            calculateGuailiChannel(candles),
        )
    }

    // Frozen list-based reference math: deliberately independent of production series and queues.
    private fun referenceChannels(candles: List<Kline>): List<GuailiChannelPoint> {
        val ema20 = referenceEma(candles.map(Kline::close), 20)
        val atr14 = referenceRma(referenceRanges(candles), 14)
        return candles.indices.map { index ->
            val ema = ema20[index]
            val atr = atr14[index]
            val trend = if (index < 3 || atr == null) {
                ChannelTrend.Neutral
            } else {
                val previous = ema20[index - 1]
                val previous2 = ema20[index - 2]
                val previous3 = ema20[index - 3]
                val strong = abs(ema - previous3) > atr * 0.1
                when {
                    strong && ema > previous && previous > previous2 && previous2 > previous3 -> ChannelTrend.Long
                    strong && ema < previous && previous < previous2 && previous2 < previous3 -> ChannelTrend.Short
                    else -> ChannelTrend.Neutral
                }
            }
            GuailiChannelPoint(
                ema20 = ema,
                atr14 = atr,
                upper = atr?.let { ema + it * 1.0 },
                lower = atr?.let { ema - it * 1.0 },
                guaili = if (atr != null && atr != 0.0) (candles[index].close - ema) / atr else null,
                trend = trend,
            )
        }
    }

    private fun referenceAmplitude(candles: List<Kline>): List<AmplitudeSignalPoint> {
        val ranges = referenceRanges(candles)
        val atr = referenceRma(ranges, 1)
        val atr14 = referenceRma(ranges, 14)
        val ema20 = referenceEma(candles.map(Kline::close), 20)
        val normalized = candles.indices.map { index ->
            val denominator = candles[index].high + candles[index].low
            val currentAtr = atr[index]
            if (currentAtr == null || denominator == 0.0) 0.0 else currentAtr / denominator * 2.0
        }
        val percentile = (1.0 - 2.0 / 20) * 100.0
        val rank = ceil(percentile / 100.0 * 20).toInt().coerceIn(1, 20)
        var topSince: Int? = null
        var bottomSince: Int? = null
        return candles.indices.map { index ->
            val candle = candles[index]
            val threshold = if (index + 1 < 20) null else
                normalized.subList(index + 1 - 20, index + 1).sorted()[rank - 1]
            val window = candles.subList((index + 1 - 20).coerceAtLeast(0), index + 1)
            val highestNow = candle.high >= window.maxOf(Kline::high)
            val lowestNow = candle.low <= window.minOf(Kline::low)
            val strongTop = threshold != null && normalized[index] > threshold &&
                candle.close > candle.open && highestNow
            val strongBottom = threshold != null && normalized[index] > threshold &&
                candle.close < candle.open && lowestNow
            topSince = if (strongTop) 0 else topSince?.plus(1)
            bottomSince = if (strongBottom) 0 else bottomSince?.plus(1)
            val ema = ema20[index]
            val guailiAtr = atr14[index]
            val guaili = if (guailiAtr != null && guailiAtr != 0.0) {
                when {
                    candle.low > ema -> (candle.low - ema) / guailiAtr
                    candle.high < ema -> (ema - candle.high) / guailiAtr
                    else -> 0.0
                }
            } else null
            val passesGuaili = guaili != null && guaili in 1.0..10.0
            AmplitudeSignalPoint(
                normalizedRange = normalized[index],
                threshold = threshold,
                guaili = guaili,
                weakTop = topSince?.let { it < 4 } == true && highestNow && passesGuaili,
                weakBottom = bottomSince?.let { it < 4 } == true && lowestNow && passesGuaili,
            )
        }
    }

    private fun referenceRanges(candles: List<Kline>): List<Double> =
        candles.mapIndexed { index, candle ->
            if (index == 0) candle.high - candle.low else {
                val previousClose = candles[index - 1].close
                max(candle.high - candle.low, max(abs(candle.high - previousClose), abs(candle.low - previousClose)))
            }
        }

    private fun referenceEma(values: List<Double>, length: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val output = MutableList(values.size) { 0.0 }
        var ema = values.first()
        output[0] = ema
        val alpha = 2.0 / (length + 1.0)
        for (index in 1 until values.size) {
            ema = alpha * values[index] + (1.0 - alpha) * ema
            output[index] = ema
        }
        return output
    }

    private fun referenceRma(values: List<Double>, length: Int): List<Double?> {
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

    private fun variedCandles(count: Int, seed: Int = 717): List<Kline> {
        val random = Random(seed)
        var previous = 100.0
        return List(count) { index ->
            val open = previous + random.nextInt(-4, 5)
            val close = open + random.nextInt(-4, 5)
            val high = maxOf(open, close) + random.nextInt(0, 4)
            val low = minOf(open, close) - random.nextInt(0, 4)
            previous = close
            candle(index, open, high, low, close)
        }
    }

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double) = Kline(
        openTimeMillis = index * 60_000L,
        closeTimeMillis = index * 60_000L + 59_999,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 10.0,
        quoteVolume = close * 10.0,
        tradeCount = 1,
        isClosed = true,
    )
}
