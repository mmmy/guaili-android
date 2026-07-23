package com.gouge.guaili.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.settings.GroupLayoutSize
import com.gouge.guaili.settings.LayoutMode
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
        assertEquals(14.sp, compact.valueFontSize)
        assertEquals(60.dp, comfortable.cellWidth)
        assertEquals(48.dp, comfortable.cellHeight)
    }

    @Test
    fun trendPresentationHandlesEveryFlagCombination() {
        assertEquals(TrendState.Neutral, trendState(longTrend = null, shortTrend = null))
        assertEquals(TrendState.Neutral, trendState(longTrend = false, shortTrend = false))
        assertEquals(TrendState.Long, trendState(longTrend = true, shortTrend = false))
        assertEquals(TrendState.Short, trendState(longTrend = false, shortTrend = true))
        assertEquals(TrendState.Conflict, trendState(longTrend = true, shortTrend = true))

        assertEquals(LongTrendTextColor, trendTextColor(TrendState.Long))
        assertEquals(ShortTrendTextColor, trendTextColor(TrendState.Short))
        assertEquals(ConflictTrendTextColor, trendTextColor(TrendState.Conflict))
        assertEquals(NeutralTrendTextColor, trendTextColor(TrendState.Neutral))
    }

    @Test
    fun formatIntervalCompactsMinuteIntervals() {
        assertEquals("1m", formatInterval("1"))
        assertEquals("15m", formatInterval("15"))
        assertEquals("1h", formatInterval("60"))
        assertEquals("2h", formatInterval("120"))
        assertEquals("1d", formatInterval("1d"))
        assertEquals("45S", formatInterval("45S"))
    }

    @Test
    fun intervalGroupsKeepOriginalOrdering() {
        val intervals = listOf("10D", "W", "480", "240", "20", "15", "45S", "10S")

        assertEquals(listOf("15", "45S", "10S"), filterIntervals(intervals, IntervalGroup.Short))
        assertEquals(listOf("240", "20"), filterIntervals(intervals, IntervalGroup.Medium))
        assertEquals(listOf("10D", "W", "480"), filterIntervals(intervals, IntervalGroup.Long))
        assertEquals(intervals, filterIntervals(intervals, IntervalGroup.All))
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

    @Test
    fun automaticLayoutUsesGroupsInPortraitAndTableInLandscape() {
        assertEquals(TableLayout.Groups, resolveTableLayout(LayoutMode.Auto, isLandscape = false))
        assertEquals(TableLayout.Table, resolveTableLayout(LayoutMode.Auto, isLandscape = true))
        assertEquals(TableLayout.Table, resolveTableLayout(LayoutMode.Table, isLandscape = false))
        assertEquals(TableLayout.Groups, resolveTableLayout(LayoutMode.Groups, isLandscape = true))
    }

    @Test
    fun groupedLayoutColumnCountRespondsToWidthAndDensity() {
        assertEquals(
            3,
            groupedColumnCount(360, GroupLayoutSize.Standard, TableDensity.Compact),
        )
        assertEquals(
            6,
            groupedColumnCount(360, GroupLayoutSize.Compact, TableDensity.Compact),
        )
        assertEquals(
            10,
            groupedColumnCount(320, GroupLayoutSize.TenColumns, TableDensity.Comfortable),
        )
    }

    @Test
    fun tenColumnLayoutUsesShortCellsAndNoGaps() {
        val dimensions = groupedLayoutDimensions(
            widthDp = 360,
            size = GroupLayoutSize.TenColumns,
            density = TableDensity.Compact,
        )

        assertEquals(10, dimensions.columns)
        assertEquals(18.dp, dimensions.periodHeaderHeight)
        assertEquals(28.dp, dimensions.table.cellHeight)
        assertEquals(12.sp, dimensions.table.valueFontSize)
        assertEquals(0.dp, dimensions.columnSpacing)
    }
}
