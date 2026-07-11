package com.gouge.guaili.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.SymbolColumnWidthMode
import com.gouge.guaili.settings.SymbolDisplayMode
import com.gouge.guaili.settings.TableDensity
import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiTableTest {
    @Test
    fun tableDensityUsesCompactAndComfortableDimensions() {
        val compact = tableDimensions(TableDensity.Compact)
        val comfortable = tableDimensions(TableDensity.Comfortable)

        assertEquals(52.dp, compact.cellWidth)
        assertEquals(40.dp, compact.cellHeight)
        assertEquals(8.sp, compact.trendMarkerFontSize)
        assertEquals(60.dp, comfortable.cellWidth)
        assertEquals(48.dp, comfortable.cellHeight)
        assertEquals(10.sp, comfortable.trendMarkerFontSize)
    }

    @Test
    fun formatIntervalCompactsMinuteIntervals() {
        assertEquals("1m", formatInterval("1"))
        assertEquals("15m", formatInterval("15"))
        assertEquals("1h", formatInterval("60"))
        assertEquals("2h", formatInterval("120"))
        assertEquals("1d", formatInterval("1d"))
    }

    @Test
    fun intervalGroupsKeepOriginalOrdering() {
        val intervals = listOf("1", "15", "20", "240", "480", "D", "W")

        assertEquals(listOf("1", "15"), filterIntervals(intervals, IntervalGroup.Short))
        assertEquals(listOf("20", "240"), filterIntervals(intervals, IntervalGroup.Medium))
        assertEquals(listOf("480", "D", "W"), filterIntervals(intervals, IntervalGroup.Long))
        assertEquals(intervals, filterIntervals(intervals, IntervalGroup.All))
    }

    @Test
    fun unfinishedCandleMarkerIsAggregatedByInterval() {
        val openCell = GuailiCell(
            symbol = "BTCUSDT",
            interval = "1",
            value = 5,
            guaili = null,
            ma = null,
            atr14 = null,
            atrRank = null,
            rankFilter = null,
            longTrend = null,
            shortTrend = null,
            isClosed = false,
            openTime = null,
            closeTime = null,
        )
        val state = GuailiTableState(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1", "5"),
            cells = mapOf("BTCUSDT" to mapOf("1" to openCell)),
        )

        assertEquals(true, hasUnfinishedCandle(state, "1"))
        assertEquals(false, hasUnfinishedCandle(state, "5"))
    }

    @Test
    fun automaticSymbolPresentationHidesCommonQuoteAndShrinksColumn() {
        val presentation = buildSymbolPresentation(
            symbols = listOf("BTCUSDT", "XAUUSDT", "SKHYNIXUSDT"),
            displayMode = SymbolDisplayMode.Auto,
            widthMode = SymbolColumnWidthMode.Auto,
        )

        assertEquals("USDT", presentation.commonQuote)
        assertEquals("BTC", presentation.displayNames["BTCUSDT"])
        assertEquals("SKHYNIX", presentation.displayNames["SKHYNIXUSDT"])
        assertEquals(76, presentation.widthDp)
    }

    @Test
    fun fullSymbolModeKeepsCodesAndHonorsWidthPreset() {
        val presentation = buildSymbolPresentation(
            symbols = listOf("BTCUSDT", "SKHYNIXUSDT"),
            displayMode = SymbolDisplayMode.Full,
            widthMode = SymbolColumnWidthMode.Standard,
        )

        assertEquals(null, presentation.commonQuote)
        assertEquals("SKHYNIXUSDT", presentation.displayNames["SKHYNIXUSDT"])
        assertEquals(96, presentation.widthDp)
    }
}
