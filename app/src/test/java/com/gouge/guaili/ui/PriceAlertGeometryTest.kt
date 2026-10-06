package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import org.junit.Assert.*
import org.junit.Test

class PriceAlertGeometryTest {
    private fun mapping() = AlertChartTransform(longArrayOf(0, 60_000, 240_000), calculateKlineViewport(3, 3f, 0f), 0f, 300f, 0f, 200f, 90.0, 110.0, 60_000)
    @Test fun timeMappingHandlesHistoryGapsAndFutureSpace() {
        val map = mapping()
        assertEquals(150_000, map.timeAt(200f))
        assertEquals(200f, map.xAt(150_000), .001f)
        assertEquals(300_000, map.timeAt(350f))
        assertEquals(350f, map.xAt(300_000), .001f)
    }
    @Test fun utcTrendLineUsesCorrectGapBoundary() {
        val g = PriceAlertGeometry("trend_segment", PriceAlertPoint(0, 100.0), PriceAlertPoint(240_000, 108.0), "none")
        val lines = mapping().segments(g)
        assertEquals(2, lines.size)
        assertEquals(150f, lines[0].x2, .001f)
        assertEquals(80f, lines[0].y2, .001f)
        assertEquals(lines[0].y2, lines[1].y1, .001f)
    }
    @Test fun bodyDragPreservesSlopeAndEndpointDragKeepsHorizontalPrice() {
        val g = samplePriceAlert().geometry
        val moved = movedAlertGeometry(g, 0, PriceAlertPoint(1000, 100.0), PriceAlertPoint(2000, 105.0))!!
        assertEquals(2000, moved.first.timeMs)
        assertEquals(105.0, moved.first.price, 0.0)
        assertEquals(105.0, moved.second.price, 0.0)
        val endpoint = movedAlertGeometry(g, 2, g.second, PriceAlertPoint(121000, 110.0))!!
        assertEquals(121000, endpoint.second.timeMs)
        assertEquals(100.0, endpoint.second.price, 0.0)
    }
    @Test fun reversedEndpointsNormalizeAndEqualTimesAreRejected() {
        val g = samplePriceAlert().geometry
        assertNull(movedAlertGeometry(g, 2, g.second, g.first))
        assertTrue(movedAlertGeometry(g, 1, g.first, PriceAlertPoint(122000, 100.0))!!.valid())
    }
    @Test fun tickRoundingIsDecimalAndHasNoInventedFallback() {
        assertEquals(100.1, alignAlertPrice(100.05, "0.1".toBigDecimal()), 0.0)
        assertEquals(100.05, alignAlertPrice(100.05, null), 0.0)
        assertNull(PriceAlertMarket("BTCUSDT", "BTCUSDT.P", "0.1", "unavailable").step())
    }
    @Test fun messageRejectsStringBooleanAndPreservesValidTemplate() {
        assertNull(validateAlertTemplate(PriceAlertTemplate))
        assertNotNull(validateAlertTemplate("""{"noConfirm":"false"}"""))
        assertNotNull(validateAlertTemplate("[]"))
    }
}
