package com.gouge.guaili.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.gouge.guaili.data.*
import com.gouge.guaili.domain.*
import com.gouge.guaili.ui.theme.GuailiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch input on a native chart; callbacks are isolated from the network. */
@RunWith(AndroidJUnit4::class)
class KlinePriceNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = context.resources.displayMetrics.density
    private var rows by mutableStateOf(sampleRows())
    private var selected by mutableStateOf<Long?>(null)
    private var cursorMode by mutableStateOf(false)
    private var cursorPrice by mutableStateOf<Double?>(null)
    private var cursorX by mutableFloatStateOf(0f)
    private var cursorIndex by mutableIntStateOf(-1)
    private var selectionCalls = 0
    private val moves = mutableListOf<PriceAlertGeometry>()
    private val alert = PriceAlertDto(
        id = 99, revision = 1, armGeneration = 1, symbol = "BTCUSDT", interval = "3", tvSymbol = "BTCUSDT.P",
        name = "固定样本 · 80000", geometry = PriceAlertGeometry("horizontal_segment",
            PriceAlertPoint(START + 50 * 180_000L, 80000.0), PriceAlertPoint(START + 80 * 180_000L, 80000.0), "right"),
        direction = "cross_any", frequency = "once", status = "active", webhookUrl = "http://localhost/mock",
        messageTemplate = PriceAlertTemplate, label = "固定样本", dataStatus = "live", createdAt = 1, updatedAt = 1,
    )

    @Before fun setup() {
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let {
            assertEquals(it, context.resources.configuration.fontScale, .01f)
        }
        compose.setContent {
            GuailiTheme {
                Surface {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Text("固定样本：BTCUSDT · 3分钟", Modifier.padding(bottom = 8.dp))
                        KlineChart(
                            rows = rows, alerts = listOf(alert), pending = emptyMap(), drawingKind = null,
                            drawingExtend = "right", tickSize = "0.1".toBigDecimal(), interval = "3", cancelGesture = 0,
                            selectedAlertId = selected, cursorMode = cursorMode, cursorIndex = cursorIndex,
                            cursorPrice = cursorPrice, cursorX = cursorX, showChannel = false,
                            showAmplitudeSignal = false, selectedIndex = rows.lastIndex, onSelectedIndex = {},
                            onAlertSelected = { selected = it; selectionCalls++; cursorMode = false },
                            onCursorModeChange = { cursorMode = it },
                            onCursorPosition = { i, price, x -> cursorIndex = i; cursorPrice = price; cursorX = x },
                            onAlertDragEnd = { _, geometry -> moves += geometry }, onDrawingStart = {},
                            onDrawComplete = { error("Navigation must not create an alert") },
                            onAddAlert = { error("Navigation must not create an alert") }, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun chart() = compose.onNodeWithTag("price-alert-chart")
    private fun assertAuto(auto: Boolean) {
        compose.onNodeWithTag("price-axis-auto").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, if (auto) "价格轴自动" else "价格轴手动，点击恢复自动",
        ))
    }
    private fun mapping(bounds: PriceBounds): AlertChartTransform {
        val rect = chart().fetchSemanticsNode().boundsInRoot
        return AlertChartTransform(LongArray(rows.size) { rows[it].candle.openTimeMillis },
            calculateKlineViewport(rows.size, 72f, 0f), 8 * density, rect.width - 68 * density,
            10 * density, rect.height * .70f, bounds.priceMin, bounds.priceMax, 180_000L)
    }
    private fun autoBounds(selectedGeometry: PriceAlertGeometry? = null) =
        calculatePriceBounds(rows, calculateKlineViewport(rows.size, 72f, 0f), false, selectedGeometry)
    private fun compress(): PriceBounds {
        val original = autoBounds()
        chart().performTouchInput {
            val x = width - 34 * density
            swipe(Offset(x, 24 * density), Offset(x, 424 * density), 600)
        }
        assertAuto(false)
        return scalePriceBoundsFromDrag(original, 400 * density, density)
    }
    private fun select80000(bounds: PriceBounds) {
        val map = mapping(bounds)
        chart().performTouchInput { click(Offset(map.left + (map.right - map.left) * .6f, map.yAt(80000.0))) }
        compose.runOnIdle { assertEquals(99L, selected) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        val scenario = InstrumentationRegistry.getArguments().getString("priceAxisScenario") ?: "phone"
        val target = File(context.getExternalFilesDir(null), "price-axis-$name-$scenario.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(target))
        File(context.getExternalFilesDir(null), "price-axis-$name-$scenario.metrics.txt").writeText(
            "fontScale=${context.resources.configuration.fontScale}\ndensity=$density\n"
        )
    }

    @Test fun compressPanRefreshAndRestoreReachDistantAlertWithoutChangingGeometry() {
        assertAuto(true)
        val compressed = compress()
        assertTrue(compressed.priceMin < 80000.0)
        select80000(compressed)
        screenshot("compressed")
        compose.runOnIdle { selected = null }
        val map = mapping(compressed)
        chart().performTouchInput {
            val x = map.left + (map.right - map.left) * .25f
            swipe(Offset(x, 180 * density), Offset(x, 80 * density), 600)
        }
        val panned = compressed.translated(-100 * density / (map.bottom - map.top) * compressed.priceRange)
        select80000(panned)
        compose.runOnIdle {
            selected = null
            val last = rows.last()
            rows = rows + last.copy(candle = last.candle.copy(
                openTimeMillis = last.candle.openTimeMillis + 180_000,
                closeTimeMillis = last.candle.closeTimeMillis + 180_000,
            ))
        }
        select80000(panned)
        assertAuto(false)
        screenshot("panned-refresh")
        compose.runOnIdle { assertTrue(moves.isEmpty()); assertEquals(80000.0, alert.geometry.first.price, 0.0) }
        compose.onNodeWithTag("price-axis-auto").performClick()
        assertAuto(true)
        compose.runOnIdle { selected = null }
        screenshot("auto")
    }

    @Test fun selectedAlertFitsAndFingerDragUsesTheDisplayedPriceScale() {
        compose.runOnIdle { selected = 99 }
        val fitted = autoBounds(alert.geometry)
        val map = mapping(fitted)
        val x = map.left + (map.right - map.left) * .6f
        val y = map.yAt(80000.0)
        chart().performTouchInput { click(Offset(x, y)) }
        compose.runOnIdle { assertTrue("Drawn line must be hittable", selectionCalls > 0) }
        chart().performTouchInput { swipe(Offset(x, y), Offset(x, y - 40 * density), 600) }
        compose.runOnIdle {
            assertEquals(1, moves.size)
            val expected = alignAlertPrice(map.priceAt(y - 40 * density), "0.1".toBigDecimal())
            assertEquals(expected, moves.single().first.price, .11)
            assertEquals(moves.single().first.price, moves.single().second.price, 0.0)
        }
        screenshot("selected-hit")
    }

    @Test fun priceAxisStillScalesInCursorModeAndDoubleTapRestoresAuto() {
        compose.runOnIdle { cursorMode = true; cursorPrice = 84000.0; cursorIndex = rows.lastIndex; cursorX = 100f }
        compress()
        chart().performTouchInput { doubleClick(Offset(width - 34 * density, 24 * density)) }
        assertAuto(true)
        compose.runOnIdle { cursorMode = false; cursorPrice = null }
        chart().performTouchInput {
            down(0, Offset(width * .3f, height * .3f)); down(1, Offset(width * .6f, height * .3f))
            moveTo(0, Offset(width * .2f, height * .3f)); moveTo(1, Offset(width * .75f, height * .3f))
            up(0); up(1)
        }
        assertAuto(true)
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    companion object {
        private const val START = 1_791_331_200_000L
        private fun sampleRows() = (0 until 100).map { i ->
            val time = START + i * 180_000L
            KlineChartRow(Kline(time, time + 179_999, 84000.0, 84100.0, 83900.0, 84000.0,
                100.0, 8_400_000.0, 1, true), GuailiChannelPoint(null, null, null, null, null, ChannelTrend.Neutral))
        }
    }
}
