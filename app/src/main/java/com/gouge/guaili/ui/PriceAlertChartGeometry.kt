package com.gouge.guaili.ui

import com.gouge.guaili.data.PriceAlertGeometry
import com.gouge.guaili.data.PriceAlertPoint
import kotlin.math.floor

/** Maps UTC geometry through the chart's actual time index, including gaps and future space. */
internal class AlertChartTransform(
    val times: LongArray,
    val viewport: KlineViewport,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val minimum: Double,
    val maximum: Double,
    val intervalMs: Long,
) {
    val slot: Float = (right - left) / viewport.visibleSpan
    fun timeAt(x: Float): Long {
        val index = viewport.coreStart + (x - left) / slot - .5f - viewport.fractionalOffset
        val before = floor(index.toDouble()).toInt()
        val fraction = index - before
        val first = when {
            before < 0 -> times.first() + before.toLong() * intervalMs
            before >= times.lastIndex -> times.last() + (before - times.lastIndex).toLong() * intervalMs
            else -> times[before]
        }
        val span = if (before in 0 until times.lastIndex) times[before + 1] - times[before] else intervalMs
        return (first + span * fraction.toDouble()).toLong().coerceAtLeast(0)
    }
    fun xAt(time: Long): Float {
        val found = times.binarySearch(time)
        val index = if (found >= 0) found.toDouble() else {
            val insertion = -found - 1
            when (insertion) {
                0 -> (time.toDouble() - times.first()) / intervalMs
                times.size -> times.lastIndex + (time.toDouble() - times.last()) / intervalMs
                else -> insertion - 1 + (time.toDouble() - times[insertion - 1]) / (times[insertion].toDouble() - times[insertion - 1])
            }
        }
        return left + slot * (index - viewport.coreStart + .5 + viewport.fractionalOffset).toFloat()
    }
    fun yAt(price: Double): Float = bottom - ((price - minimum) / (maximum - minimum) * (bottom - top)).toFloat()
    fun priceAt(y: Float): Double = maximum - (y - top) / (bottom - top) * (maximum - minimum)
    fun point(x: Float, y: Float) = PriceAlertPoint(timeAt(x), priceAt(y))
    fun segments(geometry: PriceAlertGeometry): List<AlertScreenSegment> {
        if (!geometry.valid()) return emptyList()
        val first = if (geometry.extend == "both") timeAt(left) else maxOf(timeAt(left), geometry.first.timeMs)
        val last = if (geometry.extend == "none") minOf(timeAt(right), geometry.second.timeMs) else timeAt(right)
        if (last <= first) return emptyList()
        // A UTC straight line bends in screen space at gaps in the bar index.
        val boundaries = if (geometry.kind == "horizontal_segment") listOf(first, last) else buildList {
            add(first)
            for (index in viewport.drawStart until viewport.endExclusive) {
                val time = times[index]
                if (time > first && time < last) add(time)
            }
            add(last)
        }
        return boundaries.zipWithNext { a, b ->
            val pa = geometry.priceAt(a)
            val pb = geometry.priceAt(b)
            if (pa == null || pb == null) null else AlertScreenSegment(xAt(a), yAt(pa), xAt(b), yAt(pb))
        }.filterNotNull()
    }
}

internal data class AlertScreenSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun distanceSquared(x: Float, y: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / length).coerceIn(0f, 1f)
        val ex = x - x1 - t * dx; val ey = y - y1 - t * dy
        return ex * ex + ey * ey
    }
}

internal fun movedAlertGeometry(original: PriceAlertGeometry, part: Int, down: PriceAlertPoint, current: PriceAlertPoint): PriceAlertGeometry? {
    val geometry = if (part == 0) {
        val dt = current.timeMs - down.timeMs
        val dp = current.price - down.price
        original.copy(first = PriceAlertPoint(original.first.timeMs + dt, original.first.price + dp),
            second = PriceAlertPoint(original.second.timeMs + dt, original.second.price + dp))
    } else {
        val p = current.copy(price = if (original.kind == "horizontal_segment") original.first.price else current.price)
        if (part == 1) original.copy(first = p) else original.copy(second = p)
    }
    return geometry.normalized().takeIf { it.valid() }
}

internal fun alertIntervalMillis(interval: String): Long = when {
    interval.endsWith("S", true) -> (interval.dropLast(1).toLongOrNull() ?: 1) * 1_000L
    interval.endsWith("D", true) -> (interval.dropLast(1).toLongOrNull() ?: 1) * 86_400_000L
    interval.endsWith("W", true) -> (interval.dropLast(1).toLongOrNull() ?: 1) * 604_800_000L
    else -> (interval.toLongOrNull() ?: 1) * 60_000L
}
