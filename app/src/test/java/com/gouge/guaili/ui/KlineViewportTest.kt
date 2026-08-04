package com.gouge.guaili.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KlineViewportTest {
    @Test
    fun fractionalOffsetMovesCandlesBySubBarDistance() {
        val viewport = calculateKlineViewport(
            rowCount = 100,
            visibleBars = 20f,
            rightOffsetBars = .5f,
        )

        assertEquals(80, viewport.coreStart)
        assertEquals(79, viewport.drawStart)
        assertEquals(10f, viewport.xForIndex(80, plotLeft = 0f, slotWidth = 10f), .001f)
    }

    @Test
    fun positionStaysContinuousWhenOffsetCrossesWholeBar() {
        val before = calculateKlineViewport(100, 20f, .99f)
        val after = calculateKlineViewport(100, 20f, 1.01f)

        val beforeX = before.xForIndex(79, plotLeft = 0f, slotWidth = 10f)
        val afterX = after.xForIndex(79, plotLeft = 0f, slotWidth = 10f)

        assertEquals(4.9f, beforeX, .001f)
        assertEquals(5.1f, afterX, .001f)
    }

    @Test
    fun tapSelectionUsesFractionalViewportPosition() {
        val viewport = calculateKlineViewport(100, 20f, .5f)

        assertEquals(79, viewport.indexAtX(x = 0f, plotLeft = 0f, slotWidth = 10f))
        assertEquals(80, viewport.indexAtX(x = 10f, plotLeft = 0f, slotWidth = 10f))
    }

    @Test
    fun shortSeriesUsesAvailableRowsAsVisibleSpan() {
        val viewport = calculateKlineViewport(5, 12f, 0f)

        assertEquals(5f, viewport.visibleSpan, .001f)
        assertEquals(0, viewport.coreStart)
        assertEquals(5, viewport.endExclusive)
    }
}
