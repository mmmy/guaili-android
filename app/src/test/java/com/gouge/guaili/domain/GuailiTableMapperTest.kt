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
    fun mapsLatestPointsIntoSymbolIntervalMatrix() {
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
                            latest = GuailiPoint(value = 12, guaili = 1.2, longTrend = true),
                        ),
                    ),
                ),
            ),
        )

        val table = response.toTable(
            requestedSymbols = listOf("BTCUSDT"),
            requestedIntervals = listOf("1", "5"),
        )

        assertEquals(12, table.cells["BTCUSDT"]?.get("1")?.value)
        assertNull(table.cells["BTCUSDT"]?.get("5"))
    }
}
