package com.gouge.guaili.domain

import com.gouge.guaili.data.GuailiPoint
import com.gouge.guaili.data.GuailiResponse
import com.gouge.guaili.data.GuailiSeries
import com.gouge.guaili.data.GuailiSymbolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuailiTableMapperTest {
    @Test
    fun mapsLatestValuesAndPreviousCandleTrendsIntoSymbolIntervalMatrix() {
        val previous = GuailiPoint(
            openTime = "2026-07-26T10:00:00Z",
            value = 8,
            guaili = 0.8,
            longTrend = true,
            shortTrend = false,
        )
        val latest = GuailiPoint(
            openTime = "2026-07-26T10:01:00Z",
            value = 0,
            guaili = 0.0,
            longTrend = false,
            shortTrend = false,
        )
        val response = GuailiResponse(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1", "5"),
            limit = 1,
            calcLimit = 500,
            closedOnly = false,
            results = listOf(
                GuailiSymbolResult(
                    symbol = "BTCUSDT",
                    series = listOf(
                        GuailiSeries(
                            interval = "1",
                            latest = latest,
                            data = listOf(previous, latest),
                        ),
                    ),
                ),
            ),
        )

        val table = response.toTable(
            requestedSymbols = listOf("BTCUSDT"),
            requestedIntervals = listOf("1", "5"),
        )

        val cell = table.cells["BTCUSDT"]?.get("1")
        assertEquals(0, cell?.value)
        assertEquals(0.0, cell?.guaili)
        assertEquals(true, cell?.longTrend)
        assertEquals(false, cell?.shortTrend)
        assertNull(table.cells["BTCUSDT"]?.get("5"))
    }

    @Test
    fun usesNeutralTrendWhenPreviousCandleIsUnavailable() {
        val latest = GuailiPoint(value = 0, guaili = 0.0, longTrend = true)
        val response = GuailiResponse(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
            limit = 1,
            calcLimit = 500,
            closedOnly = false,
            results = listOf(
                GuailiSymbolResult(
                    symbol = "BTCUSDT",
                    series = listOf(
                        GuailiSeries(interval = "1", latest = latest, data = listOf(latest)),
                    ),
                ),
            ),
        )

        val cell = response.toTable(listOf("BTCUSDT"), listOf("1"))
            .cells["BTCUSDT"]?.get("1")

        assertNull(cell?.longTrend)
        assertNull(cell?.shortTrend)
    }

    @Test
    fun keepsMostRecentClosedPointSeparateFromLiveLatestPoint() {
        val closed = GuailiPoint(
            openTime = "2026-07-26T10:00:00Z",
            value = -12,
            rankFilter = true,
            isClosed = true,
        )
        val live = GuailiPoint(
            openTime = "2026-07-26T10:01:00Z",
            value = 4,
            rankFilter = true,
            isClosed = false,
        )
        val response = GuailiResponse(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
            limit = 2,
            calcLimit = 500,
            closedOnly = false,
            results = listOf(
                GuailiSymbolResult(
                    symbol = "BTCUSDT",
                    series = listOf(
                        GuailiSeries(interval = "1", latest = live, data = listOf(closed, live)),
                    ),
                ),
            ),
        )

        val table = response.toTable(listOf("BTCUSDT"), listOf("1"))

        assertEquals(4, table.cells["BTCUSDT"]?.get("1")?.value)
        assertEquals(-12, table.closedCells["BTCUSDT"]?.get("1")?.value)
        assertEquals(true, table.closedCells["BTCUSDT"]?.get("1")?.isClosed)
    }
}
