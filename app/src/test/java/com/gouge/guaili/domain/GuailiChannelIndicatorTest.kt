package com.gouge.guaili.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuailiChannelIndicatorTest {
    @Test
    fun calculatesPineEma20AndAtr14Channel() {
        val candles = (1..25).map { value -> candle(value.toDouble()) }

        val rows = calculateGuailiChannel(candles)

        assertEquals(1.0, rows[0].channel.ema20!!, 0.000001)
        assertEquals(1.0 + 2.0 / 21.0, rows[1].channel.ema20!!, 0.000001)
        assertNull(rows[12].channel.atr14)
        assertEquals(2.0, rows[13].channel.atr14!!, 0.000001)
        assertEquals(
            rows[13].channel.ema20!! + 2.0,
            rows[13].channel.upper!!,
            0.000001,
        )
        assertEquals(
            rows[13].channel.ema20!! - 2.0,
            rows[13].channel.lower!!,
            0.000001,
        )
    }

    @Test
    fun colorsEmaAsLongAfterThreeStrongRisingSegments() {
        val rows = calculateGuailiChannel((1..25).map { candle(it.toDouble()) })

        assertEquals(ChannelTrend.Neutral, rows[12].channel.trend)
        assertEquals(ChannelTrend.Long, rows[13].channel.trend)
    }

    @Test
    fun returnsNoRowsForEmptyInput() {
        assertEquals(emptyList<KlineChartRow>(), calculateGuailiChannel(emptyList()))
    }

    private fun candle(close: Double) = Kline(
        openTimeMillis = close.toLong() * 60_000,
        closeTimeMillis = close.toLong() * 60_000 + 59_999,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 10.0,
        quoteVolume = close * 10.0,
        tradeCount = 1,
        isClosed = true,
    )
}
