package com.gouge.guaili.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiWidgetTest {
    @Test
    fun singleSymbolPeriodsWrapIntoMainScreenStyleRows() {
        val intervals = listOf("10D", "W", "4D", "3D", "2D", "D", "720", "480", "360", "240")

        assertEquals(
            listOf(
                listOf("10D", "W", "4D", "3D"),
                listOf("2D", "D", "720", "480"),
                listOf("360", "240"),
            ),
            singleSymbolRows(intervals, columns = 4),
        )
    }

    @Test
    fun singleSymbolTitleMatchesMainScreenSymbolHeader() {
        assertEquals("BTC / USDT", singleSymbolTitle("BTCUSDT"))
        assertEquals("XAU / USD", singleSymbolTitle("XAUUSD"))
        assertEquals("DXY", singleSymbolTitle("DXY"))
    }

    @Test
    fun wideGridsUseAtMostFiveOuterWeightSlotsForGlance() {
        assertEquals(1, singleSymbolCellsPerSlot(columns = 5))
        assertEquals(5, singleSymbolSlotCount(columns = 5))
        assertEquals(2, singleSymbolCellsPerSlot(columns = 10))
        assertEquals(5, singleSymbolSlotCount(columns = 10))
    }

    @Test
    fun refreshFeedbackExplainsTheManualRefreshState() {
        assertEquals(null, refreshFeedbackText(WidgetRefreshStatus()))
        assertEquals(
            "刷新中…",
            refreshFeedbackText(WidgetRefreshStatus(WidgetRefreshPhase.Refreshing, changedAt = 1L)),
        )
        assertEquals(
            "刷新失败",
            refreshFeedbackText(WidgetRefreshStatus(WidgetRefreshPhase.Failure, changedAt = 1L)),
        )
    }
}
