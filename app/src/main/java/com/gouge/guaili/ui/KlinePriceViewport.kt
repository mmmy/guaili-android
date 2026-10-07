package com.gouge.guaili.ui

import com.gouge.guaili.data.PriceAlertGeometry
import com.gouge.guaili.domain.KlineChartRow
import kotlin.math.abs
import kotlin.math.exp

/** One price transform for candles, cursor, drawing and alert hit testing. */
internal data class PriceBounds(val priceMin: Double, val priceMax: Double) {
    val priceRange: Double get() = priceMax - priceMin

    fun scaled(factor: Double, anchorFraction: Double = .5): PriceBounds {
        if (!factor.isFinite() || factor <= 0.0 || !anchorFraction.isFinite()) return this
        val fraction = anchorFraction.coerceIn(0.0, 1.0)
        val anchor = priceMax - priceRange * fraction
        val span = (priceRange * factor).coerceAtLeast(minimumPriceSpan(anchor, priceRange))
        return validPriceBounds(anchor - span * (1.0 - fraction), anchor + span * fraction) ?: this
    }

    fun translated(delta: Double): PriceBounds =
        validPriceBounds(priceMin + delta, priceMax + delta) ?: this
}

private fun minimumPriceSpan(price: Double, span: Double = 0.0): Double =
    (maxOf(abs(price), abs(span)) * 1e-9).coerceAtLeast(1e-300)

private fun validPriceBounds(minimum: Double, maximum: Double): PriceBounds? =
    if (minimum.isFinite() && maximum.isFinite() && maximum > minimum && (maximum - minimum).isFinite()) {
        PriceBounds(minimum, maximum)
    } else null

/** Downward axis dragging compresses the chart; use DIP so sensitivity is density-independent. */
internal fun scalePriceBoundsFromDrag(bounds: PriceBounds, deltaY: Float, density: Float): PriceBounds {
    if (!deltaY.isFinite() || !density.isFinite() || density <= 0f) return bounds
    return bounds.scaled(exp((deltaY / density / 100f).toDouble().coerceIn(-20.0, 20.0)))
}

/** Visible extrema are accumulated without allocating a list of boxed prices on each touch. */
internal fun calculatePriceBounds(
    rows: List<KlineChartRow>,
    viewport: KlineViewport,
    showChannel: Boolean,
    selectedGeometry: PriceAlertGeometry? = null,
): PriceBounds {
    var minimum = Double.POSITIVE_INFINITY
    var maximum = Double.NEGATIVE_INFINITY
    fun include(price: Double?) {
        if (price != null && price.isFinite()) {
            minimum = minOf(minimum, price)
            maximum = maxOf(maximum, price)
        }
    }
    for (index in viewport.drawStart until viewport.endExclusive) {
        val row = rows[index]
        include(row.candle.low)
        include(row.candle.high)
        if (showChannel) {
            include(row.channel.lower)
            include(row.channel.upper)
        }
    }
    if (viewport.drawStart < viewport.endExclusive && selectedGeometry != null) {
        val firstTime = rows[viewport.drawStart].candle.openTimeMillis
        val lastTime = rows[viewport.endExclusive - 1].candle.openTimeMillis
        include(selectedGeometry.priceAt(firstTime))
        include(selectedGeometry.priceAt(lastTime))
        if (selectedGeometry.first.timeMs in firstTime..lastTime) include(selectedGeometry.first.price)
        if (selectedGeometry.second.timeMs in firstTime..lastTime) include(selectedGeometry.second.price)
    }
    if (!minimum.isFinite() || !maximum.isFinite()) return PriceBounds(0.0, 1.0)
    val center = minimum / 2.0 + maximum / 2.0
    val minSpan = minimumPriceSpan(center)
    val span = (maximum - minimum).coerceAtLeast(minSpan)
    val padding = (span * .06).coerceAtLeast(minSpan)
    val fitted = span + padding * 2.0
    return validPriceBounds(center - fitted / 2.0, center + fitted / 2.0) ?: PriceBounds(0.0, 1.0)
}

/** A historical time anchor stays on the same bars when a live candle is appended. */
internal fun reconcileKlineRightOffset(previousLastTime: Long?, times: LongArray, visibleBars: Float, offset: Float): Float {
    if (times.isEmpty()) return 0f
    val previousIndex = previousLastTime?.let { times.binarySearch(it) } ?: -1
    val appended = if (previousIndex >= 0) times.lastIndex - previousIndex else 0
    val adjusted = if (offset > 0f) offset + appended else offset
    return adjusted.coerceIn(-(visibleBars - 3f).coerceAtLeast(0f), (times.size - visibleBars).coerceAtLeast(0f))
}
