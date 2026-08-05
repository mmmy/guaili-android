package com.gouge.guaili.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplitudeSignalIndicatorTest {
    @Test
    fun marksStrongTopAndKeepsWeakTopEnabledOnFollowingReverseCandle() {
        val candles = baselineCandles() + listOf(
            candle(open = 105.0, high = 112.0, low = 104.0, close = 110.0, index = 20),
            candle(open = 109.0, high = 113.0, low = 105.0, close = 108.0, index = 21),
        )

        val points = AmplitudeSignalIndicator.calculate(candles)

        assertTrue(points[20].weakTop)
        assertTrue(points[21].weakTop)
        assertFalse(points[20].weakBottom)
        assertTrue(points[20].normalizedRange!! > points[20].threshold!!)
        assertTrue(points[21].guaili!! in 1.0..10.0)
    }

    @Test
    fun marksStrongBottomBelowEma20() {
        val candles = baselineCandles() +
            candle(open = 95.0, high = 96.0, low = 88.0, close = 90.0, index = 20)

        val points = AmplitudeSignalIndicator.calculate(candles)

        assertTrue(points.last().weakBottom)
        assertFalse(points.last().weakTop)
        assertTrue(points.last().guaili!! in 1.0..10.0)
    }

    @Test
    fun waitsForFullPercentileWindowBeforeSignaling() {
        val points = AmplitudeSignalIndicator.calculate(baselineCandles(count = 19))

        assertNull(points.last().threshold)
        assertFalse(points.last().weakTop)
        assertFalse(points.last().weakBottom)
    }

    @Test
    fun nearestRankUsesPineStyleCeilingRank() {
        val values = (1..21).map(Int::toDouble)

        val ranks = rollingNearestRankPercentile(values, length = 20, percentile = 90.0)

        assertEquals(18.0, ranks[19]!!, 0.000001)
        assertEquals(19.0, ranks[20]!!, 0.000001)
    }

    private fun baselineCandles(count: Int = 20): List<Kline> =
        (0 until count).map { index ->
            candle(open = 100.0, high = 101.0, low = 99.0, close = 100.0, index = index)
        }

    private fun candle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        index: Int,
    ) = Kline(
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
