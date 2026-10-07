package com.gouge.guaili.integration

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.*
import com.gouge.guaili.settings.*
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real market navigation and read-only HTTP integration, with isolated local fixtures. */
@RunWith(AndroidJUnit4::class)
class MarketPriceAlertsIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val preferences = MarketSignalPreferencesStore(context)
    private val cache = ServerSignalsSnapshotStore(context)
    private val tableCache = GuailiSnapshotStore(context)
    private val json = PriceAlertRepository.json
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private lateinit var originalSettings: GuailiSettings
    private lateinit var originalPreferences: MarketSignalPreferences
    private var originalSnapshot: ServerSignalsSnapshot? = null
    private var originalFailure: ServerSignalsFailure? = null
    private var originalTable: GuailiSnapshot? = null
    private lateinit var server: MockWebServer
    @Volatile private var generation = 1L
    @Volatile private var unavailable = false
    private val now = System.currentTimeMillis()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun setup() = runBlocking {
        originalSettings = settings.settings.first()
        originalPreferences = preferences.preferences.first()
        originalSnapshot = cache.read(); originalFailure = cache.readFailure(); originalTable = tableCache.read()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.requestUrl?.encodedPath
                    if (path == "/api/price-alerts" && unavailable) return MockResponse().setResponseCode(503)
                    val body = when (path) {
                        "/api/price-alerts" -> json.encodeToString(alerts())
                        "/api/price-alerts/market" -> """{"symbol":"SOXLUSDT","tvSymbol":"SOXLUSDT.P","tickSize":"0.01","status":"ready"}"""
                        "/api/alerts" -> "[]"
                        "/api/klines" -> klines(request.requestUrl?.queryParameter("intervals") ?: "60").toString()
                        "/api/signals" -> json.encodeToString(ServerSignalsResponse(true, "degraded", System.currentTimeMillis(),
                            results = listOf(ServerSymbolSignals("SOXLUSDT", "degraded", System.currentTimeMillis()))))
                        "/api/indicators/guaili" -> {
                            val intervals = request.requestUrl?.queryParameter("intervals")?.split(',').orEmpty()
                            json.encodeToString(GuailiResponse(listOf("SOXLUSDT"), intervals, 3, 500, false, serverTime = System.currentTimeMillis(),
                                results = listOf(GuailiSymbolResult("SOXLUSDT", intervals.filter { it != "10" }.mapIndexed { index, interval ->
                                    val point = GuailiPoint(value = if (index % 2 == 0) 5 else -4, guaili = 1.2, longTrend = index % 2 == 0,
                                        shortTrend = index % 2 != 0, isClosed = false, availability = "ready", rankFilter = true)
                                    GuailiSeries(interval, latest = point, data = listOf(point, point, point))
                                }))))
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        cache.clear(); tableCache.clear()
        preferences.update { MarketSignalPreferences(view = MarketView.Table, symbols = listOf("SOXLUSDT")) }
        settings.save(originalSettings.copy(baseUrl = server.url("/").toString(), symbols = listOf("SOXLUSDT"),
            intervals = DefaultIntervals, layoutMode = LayoutMode.Groups, groupLayoutSize = GroupLayoutSize.TenColumns, autoRefreshSeconds = 5))
    }

    @After fun cleanup() = runBlocking {
        settings.save(originalSettings); preferences.update { originalPreferences }
        originalSnapshot?.let { cache.save(it) } ?: cache.clear()
        cache.recordFailure(originalFailure)
        originalTable?.let { tableCache.save(it) } ?: tableCache.clear()
        server.shutdown()
    }

    @Test fun summaryViewingRecreationAndRearmPreserveUnreadMeaning() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("market-alerts-SOXLUSDT")
            compose.onNodeWithText("· 新触发 1").assertIsDisplayed()
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-60", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-30", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-10", useUnmergedTree = true).assertIsDisplayed() // No indicator data, but the price alert is active.
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-240", useUnmergedTree = true).assertDoesNotExist() // Paused.
            assertDotCorner("60")
            assertDotCorner("30")
            assertDotCorner("15")
            capture("market-price-alerts-groups")
            assertTrue(requests.filter { it.requestUrl?.encodedPath == "/api/price-alerts" }.all { it.requestUrl?.queryParameter("symbol") == null })

            compose.onNodeWithTag("market-alerts-SOXLUSDT").performClick()
            await("price-alert-list")
            compose.onNodeWithText("触发于", substring = true).assertIsDisplayed()
            capture("market-price-alerts-list")
            compose.onNodeWithContentDescription("关闭警报列表").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("· 新触发 1").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-30", useUnmergedTree = true).assertDoesNotExist()
            scenario.recreate()
            await("market-alerts-SOXLUSDT")
            compose.onNodeWithText("· 新触发 1").assertDoesNotExist()

            generation = 2
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(12_000) { compose.onAllNodesWithText("· 新触发 1").fetchSemanticsNodes().isNotEmpty() }
            unavailable = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(12_000) { compose.onAllNodesWithText("警报待更新").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-30", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(requests.filter { it.requestUrl?.encodedPath?.startsWith("/api/price-alerts") == true }.all { it.method == "GET" })
        }
    }

    @Test fun periodOpensMatchingChartAndMatrixKeepsCellDetails() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-period-alert-SOXLUSDT-60")
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("market-cell-SOXLUSDT-15") and
                hasContentDescription("value", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("market-cell-SOXLUSDT-15").performClick()
            await("cell-price-alerts")
            compose.onNodeWithText("等待行情", substring = true).assertIsDisplayed()
            device.pressBack()
            compose.onNodeWithTag("market-cell-SOXLUSDT-60").performClick()
            await("cell-price-alerts")
            compose.onNodeWithText("价格警报：已设置 2 条价格警报").assertIsDisplayed()
            compose.onNodeWithText("View K-line").performScrollTo().performClick()
            await("price-alert-selection")
            compose.onNodeWithText("一小时上穿二").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("draw-horizontal-alert") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("price-alert-chart").assertIsDisplayed()
            capture("market-price-alerts-chart")
            val chartRequest = requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!
            assertEquals("SOXLUSDT", chartRequest.queryParameter("symbol"))
            assertEquals("60", chartRequest.queryParameter("intervals"))
            device.pressBack(); device.pressBack()
            await("market-groups-list")
            runBlocking { settings.save(settings.settings.first().copy(layoutMode = LayoutMode.Table,
                intervals = listOf("60", "30", "240", "10", "15"))) }
            await("market-table-list")
            compose.onNodeWithTag("market-period-alert-SOXLUSDT-30", useUnmergedTree = true).assertIsDisplayed()
            assertDotCorner("60")
            assertDotCorner("30")
            assertDotCorner("15")
            capture("market-price-alerts-matrix")
            compose.onNodeWithTag("market-cell-SOXLUSDT-60").performClick()
            await("cell-price-alerts")
            compose.onNodeWithText("View K-line").performScrollTo().assertIsDisplayed()
            assertTrue(requests.filter { it.requestUrl?.encodedPath?.startsWith("/api/price-alerts") == true }.all { it.method == "GET" })
        }
    }

    private fun alerts(): List<PriceAlertDto> {
        fun alert(id: Long, interval: String, status: String, name: String) = PriceAlertDto(id, 1, if (id == 3L) generation else 1,
            "SOXLUSDT", interval, "SOXLUSDT.P", name,
            PriceAlertGeometry("horizontal_segment", PriceAlertPoint(now - 86_400_000, 110.0), PriceAlertPoint(now - 3_600_000, 110.0), "right"),
            "cross_up", "once", status, webhookUrl = "http://localhost/mock", messageTemplate = "{}", label = name,
            dataStatus = "live", triggeredAt = now.takeIf { status == "triggered" }, createdAt = now, updatedAt = now)
        return listOf(alert(1, "60", "active", "一小时上穿一"), alert(2, "60", "active", "一小时上穿二"),
            alert(3, "30", "triggered", "三十分钟穿越"), alert(4, "240", "disabled", "暂停警报"),
            alert(5, "480", "expired", "到期警报"), alert(6, "10", "active", "指标缺失仍监测"),
            alert(7, "15", "active", "等待警报行情").copy(dataStatus = "waiting_for_price"))
    }

    private fun klines(interval: String): JsonObject = buildJsonObject {
        val end = now / 3_600_000 * 3_600_000
        put("symbol", "SOXLUSDT"); put("intervals", buildJsonArray { add(interval) }); put("limit", 300); put("closedOnly", false)
        put("series", buildJsonArray { add(buildJsonObject {
            put("interval", interval); put("data", buildJsonArray {
                repeat(100) { index -> add(buildJsonObject {
                    put("symbol", "SOXLUSDT"); put("interval", interval); put("candle", buildJsonObject {
                        put("openTime", Instant.ofEpochMilli(end - (99 - index) * 3_600_000L).toString())
                        put("closeTime", Instant.ofEpochMilli(end - (98 - index) * 3_600_000L - 1).toString())
                        val price = 100 + kotlin.math.sin(index * .3) * 5
                        put("open", price); put("high", price + 2); put("low", price - 2); put("close", price + .5)
                        put("volume", 10); put("quoteVolume", 1000); put("tradeCount", 10); put("isClosed", index != 99)
                    })
                }) }
            })
        }) })
    }
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun assertDotCorner(interval: String) {
        val dot = compose.onNodeWithTag("market-period-alert-SOXLUSDT-$interval", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cell = compose.onNodeWithTag("market-cell-SOXLUSDT-$interval").fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertEquals(8 * density, dot.width, .75f)
        assertEquals(8 * density, dot.height, .75f)
        assertEquals(3 * density, dot.left - cell.left, .75f)
        assertEquals(3 * density, dot.top - cell.top, .75f)
        assertTrue(dot.center.x < cell.center.x && dot.center.y < cell.center.y)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        device.waitForIdle(1_000)
        val suffix = InstrumentationRegistry.getArguments().getString("visual_suffix", "phone")
        device.takeScreenshot(File(context.getExternalFilesDir(null), "$name-$suffix.png"))
    }
}
