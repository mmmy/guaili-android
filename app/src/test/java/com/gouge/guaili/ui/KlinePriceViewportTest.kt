package com.gouge.guaili.ui

import com.gouge.guaili.data.PriceAlertGeometry
import com.gouge.guaili.data.PriceAlertPoint
import com.gouge.guaili.domain.ChannelTrend
import com.gouge.guaili.domain.GuailiChannelPoint
import com.gouge.guaili.domain.Kline
import com.gouge.guaili.domain.KlineChartRow
import org.junit.Assert.*
import org.junit.Test

class KlinePriceViewportTest {
    private val rows = (0 until 100).map { i ->
        val time = i * 180_000L
        KlineChartRow(Kline(time, time + 179_999, 84000.0, 84100.0, 83900.0, 84000.0, 1.0, 84000.0, 1, true),
            GuailiChannelPoint(null, null, null, null, null, ChannelTrend.Neutral))
    }
    private val alert = PriceAlertGeometry("horizontal_segment", PriceAlertPoint(50 * 180_000L, 80000.0),
        PriceAlertPoint(80 * 180_000L, 80000.0), "right")

    @Test fun axisCompressionCanReach80000FromANarrow84000Range() {
        val bounds = calculatePriceBounds(rows, calculateKlineViewport(rows.size, 72f, 0f), false)
        assertTrue(bounds.priceMin > 80000.0)
        val compressed = scalePriceBoundsFromDrag(bounds, 500f, 1f)
        assertTrue(compressed.priceMin < 80000.0)
        assertTrue(compressed.priceMax > 84000.0)
        assertEquals(84000.0, (compressed.priceMin + compressed.priceMax) / 2.0, 1e-8)
        assertEquals(compressed, scalePriceBoundsFromDrag(bounds, 1500f, 3f))
    }

    @Test fun compressionAndExpansionPreserveTheMousePriceAndCanReverse() {
        val bounds = PriceBounds(83800.0, 84400.0)
        for (fraction in listOf(0.0, .25, .5, .75, 1.0)) {
            val price = bounds.priceMax - bounds.priceRange * fraction
            val expanded = bounds.scaled(.25, fraction)
            assertEquals(price, expanded.priceMax - expanded.priceRange * fraction, 1e-8)
            assertEquals(bounds, expanded.scaled(4.0, fraction))
        }
    }

    @Test fun selectedAlertUsesTheSameBoundsForDrawingCursorAndHitTesting() {
        val viewport = calculateKlineViewport(rows.size, 72f, 0f)
        val bounds = calculatePriceBounds(rows, viewport, false, alert)
        assertTrue(bounds.priceMin < 80000.0)
        val mapping = AlertChartTransform(LongArray(rows.size) { rows[it].candle.openTimeMillis }, viewport,
            8f, 300f, 10f, 500f, bounds.priceMin, bounds.priceMax, 180_000L)
        val y = mapping.yAt(80000.0)
        assertTrue(y in mapping.top..mapping.bottom)
        assertEquals(80000.0, mapping.priceAt(y), .001)
        assertTrue(mapping.segments(alert).any { it.distanceSquared(mapping.xAt(alert.second.timeMs), y) < .001f })
        val panned = bounds.translated(-1000.0)
        assertEquals(bounds.priceRange, panned.priceRange, 1e-8)
        assertEquals(80000.0, alert.first.price, 0.0)
    }

    @Test fun tinyAndFlatPricesRetainUsableFinitePrecision() {
        val bounds = PriceBounds(1e-12, 2e-12)
        val expanded = bounds.scaled(1e-200)
        assertTrue(expanded.priceMin.isFinite() && expanded.priceMax.isFinite())
        assertTrue(expanded.priceRange > 0.0 && expanded.priceRange < 1e-12)
        for (factor in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(bounds, bounds.scaled(factor))
        assertEquals(bounds, bounds.translated(Double.POSITIVE_INFINITY))
        assertEquals(bounds, scalePriceBoundsFromDrag(bounds, Float.NaN, 1f))
        val flat = rows.map { it.copy(candle = it.candle.copy(high = 84000.0, low = 84000.0)) }
        assertTrue(calculatePriceBounds(flat, calculateKlineViewport(flat.size, 72f, 0f), false).priceRange > 0.0)
    }

    @Test fun appendAndPrependKeepHistoricalOffsetWhileLatestKeepsFollowing() {
        val before = longArrayOf(100, 200, 300, 400, 500)
        val after = longArrayOf(0, 100, 200, 300, 400, 500, 600)
        assertEquals(0f, reconcileKlineRightOffset(before.last(), after, 3f, 0f), 0f)
        assertEquals(2f, reconcileKlineRightOffset(before.last(), after, 3f, 1f), 0f)
        assertEquals(1f, reconcileKlineRightOffset(before.last(), longArrayOf(0) + before, 3f, 1f), 0f)
    }
}
