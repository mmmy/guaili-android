package com.gouge.guaili.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.Kline
import com.gouge.guaili.domain.calculateKlineChartRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KlinePlotRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyChartHasNoAxisIndicesToRead() {
        val viewport = KlineViewport(0, 0, 0, 1f, 0f)
        compose.setContent {
            Box(Modifier.size(320.dp, 400.dp)) {
                KlinePricePlot(emptyList(), viewport, PriceBounds(0.0, 1.0), true, true, 8f, 68f)
            }
        }
        compose.waitForIdle()
    }

    @Test fun cursorInvalidationDoesNotRedrawTheCandleLayer() {
        val cursor = mutableFloatStateOf(20f)
        val rows = calculateKlineChartRows(List(40) { i ->
            Kline(i * 60_000L, (i + 1) * 60_000L - 1, 100.0, 102.0, 99.0,
                101.0, 10.0, 1_000.0, 1, true)
        })
        val viewport = calculateKlineViewport(rows.size, 30f, 0f)
        val bounds = calculatePriceBounds(rows, viewport, true)
        var baseDraws = 0
        var overlayDraws = 0
        val countBaseDraws = Modifier.drawBehind { baseDraws++ }
        compose.setContent {
            // Cursor coordinates are read by the screen during composition in the real chart.
            val overlayX = cursor.floatValue
            Box(Modifier.size(320.dp, 400.dp)) {
                KlinePricePlot(rows, viewport, bounds, true, true, 8f, 68f,
                    countBaseDraws)
                Canvas(Modifier.size(320.dp, 400.dp)) {
                    overlayDraws++
                    drawLine(Color.White, Offset(overlayX, 0f), Offset(overlayX, size.height))
                }
            }
        }
        compose.waitForIdle()
        val before = baseDraws
        val overlayBefore = overlayDraws
        assertTrue(before > 0)
        compose.runOnIdle { cursor.floatValue = 80f }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(overlayDraws > overlayBefore)
            assertEquals(before, baseDraws)
        }
    }
}
