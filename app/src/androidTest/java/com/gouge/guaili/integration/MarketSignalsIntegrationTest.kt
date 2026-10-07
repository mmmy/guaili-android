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
import androidx.glance.appwidget.updateAll
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.*
import com.gouge.guaili.widget.GuailiWidget
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Exercise the real market destination. All fixture settings and caches are restored. */
@RunWith(AndroidJUnit4::class)
class MarketSignalsIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val preferences = MarketSignalPreferencesStore(context)
    private val cache = ServerSignalsSnapshotStore(context)
    private val tableCache = GuailiSnapshotStore(context)
    private var originalTableSnapshot: GuailiSnapshot? = null
    private lateinit var originalSettings: GuailiSettings
    private lateinit var originalPreferences: MarketSignalPreferences
    private var originalSnapshot: ServerSignalsSnapshot? = null
    private var originalFailure: ServerSignalsFailure? = null
    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile private var disabled = false
    @Volatile private var layoutFixture = false
    @Volatile private var expandedCoverage = false

    @Before fun isolateMarketData() = runBlocking {
        originalSettings = settings.settings.first()
        originalPreferences = preferences.preferences.first()
        originalSnapshot = cache.read()
        originalFailure = cache.readFailure()
        originalTableSnapshot = tableCache.read()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val body = when (request.requestUrl?.encodedPath) {
                        "/api/signals" -> Json.encodeToString(fixture())
                        "/api/klines" -> """{"symbol":"BTCUSDT","intervals":["8"],"limit":300,"closedOnly":false,"series":[]}"""
                        "/api/alerts" -> "[]"
                        else -> {
                            val symbols = request.requestUrl?.queryParameter("symbols")?.split(',').orEmpty()
                            val intervals = request.requestUrl?.queryParameter("intervals")?.split(',').orEmpty()
                            Json.encodeToString(GuailiResponse(symbols, intervals, 3, 500, false, serverTime = System.currentTimeMillis(),
                                results = symbols.map { symbol -> GuailiSymbolResult(symbol, intervals.map { interval ->
                                    val point = GuailiPoint(value = 14, guaili = 1.42, longTrend = true, shortTrend = false,
                                        isClosed = false, availability = "ready", rankFilter = true)
                                    GuailiSeries(interval, latest = point, data = listOf(point, point, point))
                                }) }))
                        }
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        cache.clear()
        preferences.update { MarketSignalPreferences(symbols = listOf("BTCUSDT", "XAUUSDT")) }
        settings.save(originalSettings.copy(baseUrl = server.url("/").toString(), symbols = listOf("BTCUSDT", "XAUUSDT"), autoRefreshSeconds = 5))
    }

    @After fun restoreMarketData() = runBlocking {
        settings.save(originalSettings)
        preferences.update { originalPreferences }
        originalSnapshot?.let { cache.save(it) } ?: cache.clear()
        cache.recordFailure(originalFailure)
        originalTableSnapshot?.let { tableCache.save(it) } ?: tableCache.clear()
        GuailiWidget().updateAll(context)
        server.shutdown()
    }

    @Test fun filtersDetailsKlineAndPersistentViewWorkInsideMarket() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("market-view-v2")
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-card-BTCUSDT-btc")
            compose.onNodeWithTag("nav-market").assertIsSelected()
            assertTrue(requests.filter { it.requestUrl?.encodedPath == "/api/signals" }.all { it.requestUrl?.queryParameter("symbols") == null })
            showFilters()
            compose.onNodeWithText("服务器计算 · 实时动态K · EMA20").assertIsDisplayed()
            compose.onNodeWithTag("market-v2-kind-Extreme").performClick()
            await("market-v2-empty")
            compose.onNodeWithTag("market-v2-kind-Extreme").performClick()
            await("market-v2-card-BTCUSDT-btc")
            compose.onNodeWithTag("market-v2-expand-BTCUSDT-btc").performClick()
            compose.onNodeWithText("参与周期与动态乖离").assertIsDisplayed()
            capture("market-v2-details")
            compose.onNodeWithTag("market-v2-expand-BTCUSDT-btc").performClick()
            compose.onNodeWithTag("market-v2-kline-BTCUSDT-btc").performTouchInput { longClick() }
            compose.onNodeWithText("参与周期与动态乖离").assertIsDisplayed()
            compose.onNodeWithTag("market-v2-expand-BTCUSDT-btc").performClick()
            compose.onNodeWithTag("market-v2-status").performClick()
            await("market-v2-status-details")
            capture("market-v2-status")
            compose.onNodeWithTag("market-v2-status").performClick()
            compose.onNodeWithTag("market-v2-kline-BTCUSDT-btc").performClick()
            compose.waitUntil(10_000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" } }
            val chart = requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!
            assertEquals("BTCUSDT", chart.queryParameter("symbol"))
            assertEquals("8", chart.queryParameter("intervals"))
            assertEquals("false", chart.queryParameter("closedOnly"))
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            await("market-v2-card-BTCUSDT-btc")
            scenario.recreate()
            await("market-v2-card-BTCUSDT-btc")
            compose.onNodeWithTag("market-view-v2").assertIsSelected()
            showFilters()
            compose.onNodeWithTag("market-v2-symbols").performClick()
            compose.onNodeWithTag("market-v2-symbol-BTCUSDT").performClick()
            compose.onNodeWithText("完成").performClick()
            await("market-v2-empty")
            assertEquals(listOf("BTCUSDT", "XAUUSDT"), runBlocking { settings.settings.first().symbols })
            compose.onNodeWithTag("market-view-table").performClick()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("market-signal-pane-wide").fetchSemanticsNodes().isEmpty())
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("market-signals-v2").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            compose.onNodeWithTag("nav-signals").performClick()
            compose.onNodeWithTag("market-signals-v2").assertDoesNotExist()
        }
    }

    @Test fun compactWidgetStyleShowsEverySignalKindWithoutLargeCards() {
        layoutFixture = true
        runBlocking { preferences.update { it.copy(symbols = listOf("BTCUSDT", "XAUUSDT", "QQQUSDT")) } }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-view-v2")
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-card-QQQUSDT-conflict")
            listOf("上方乖离共振", "下方乖离共振", "多周期近均线", "长短周期分歧").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            compose.onNodeWithText("短负 1m–8m · 5级").assertIsDisplayed()
            compose.onNodeWithText("长正 60m–240m · 5级").assertIsDisplayed()
            listOf("BTCUSDT-btc", "BTCUSDT-near", "XAUUSDT-negative").forEach {
                assertTrue("Ordinary cards must keep the compact widget height",
                    compose.onNodeWithTag("market-v2-card-$it").fetchSemanticsNode().boundsInRoot.height <= 64f * compose.density.density)
            }
            assertTrue("Conflict cards add only the run row",
                compose.onNodeWithTag("market-v2-card-QQQUSDT-conflict").fetchSemanticsNode().boundsInRoot.height <= 80f * compose.density.density)
            capture("market-v2-widget-style")
        }
    }

    @Test fun fullCacheDisabledStateAndHiddenPollingAreCorrect() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-view-v2")
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-card-BTCUSDT-btc")
            assertEquals(listOf("BTCUSDT", "XAUUSDT", "QQQUSDT"), runBlocking { cache.read()!!.response.results.map { it.symbol } })
            capture("market-v2-list")
            disabled = true
            Thread.sleep(1_100)
            compose.onNodeWithTag("market-v2-refresh").performClick()
            await("market-v2-empty")
            compose.onNodeWithText("服务器信号计算已关闭").assertIsDisplayed()
            capture("market-v2-disabled")
            compose.onNodeWithTag("market-view-table").performClick()
            compose.waitForIdle()
            // Expanded windows keep the live signal pane beside the table by design;
            // compact windows close it when switching back to the matrix.
            val expandedPane = compose.onAllNodesWithTag("market-signal-pane-wide").fetchSemanticsNodes().isNotEmpty()
            if (!expandedPane) {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("market-signals-v2").fetchSemanticsNodes().isEmpty() }
            }
            compose.waitForIdle()
            val count = requests.count { it.requestUrl?.encodedPath == "/api/signals" }
            Thread.sleep(5_500)
            assertTrue("Table summaries must independently poll V2", requests.count { it.requestUrl?.encodedPath == "/api/signals" } > count)
            compose.onNodeWithTag("nav-settings").performClick()
            compose.waitForIdle()
            val hiddenCount = requests.count { it.requestUrl?.encodedPath == "/api/signals" }
            Thread.sleep(5_500)
            assertEquals("Leaving market must stop both pollers", hiddenCount, requests.count { it.requestUrl?.encodedPath == "/api/signals" })
        }
    }

    @Test fun liveLinksRevealRefreshAndReturnWithoutChangingUserSettings() {
        runBlocking { settings.save(settings.settings.first().copy(intervals = listOf("D", "8", "5", "3", "2", "1"))) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("market-summary-BTCUSDT")
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-card-BTCUSDT-btc")
            compose.onNodeWithTag("market-v2-locate-BTCUSDT-btc").performClick()
            await("market-link-bar")
            compose.onNodeWithTag("market-view-table").assertIsSelected()
            compose.onNodeWithTag("market-link-label").assertTextEquals("BTC · 共+5级")
            capture("market-link-selected")
            compose.onNodeWithText("Long", useUnmergedTree = true).performClick()
            await("market-link-reveal")
            compose.onNodeWithTag("market-link-reveal").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("market-cell-BTCUSDT-8").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("D", "8", "5", "3", "2", "1"), runBlocking { settings.settings.first().intervals })
            expandedCoverage = true
            Thread.sleep(1_100)
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("BTC · 共+6级").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { requests.any { it.requestUrl?.queryParameter("intervals")?.split(',')?.contains("10") == true } }
            capture("market-link-expanded")
            scenario.recreate()
            await("market-link-bar")
            compose.onNodeWithTag("market-link-label").assertTextEquals("BTC · 共+6级")
            disabled = true
            Thread.sleep(1_100)
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("BTC · 服务器信号计算已关闭").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("market-link-kline").assertIsNotEnabled()
            compose.onNodeWithTag("market-link-return").performClick()
            compose.onNodeWithTag("market-view-v2").assertIsSelected()
            assertEquals(listOf("D", "8", "5", "3", "2", "1"), runBlocking { settings.settings.first().intervals })
        }
    }

    @Test fun signalPaneSelectsUnconfiguredSymbolAndKeepsScopeAndChartIndependent() {
        layoutFixture = true
        runBlocking { preferences.update { it.copy(symbols = listOf("BTCUSDT", "XAUUSDT", "QQQUSDT")) } }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-signal-pane-toggle")
            if (compose.onAllNodesWithTag("market-signal-pane-wide").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("market-signal-pane-toggle").performClick()
            await("market-v2-card-QQQUSDT-conflict")
            compose.onNodeWithTag("market-v2-kline-QQQUSDT-conflict").performClick()
            await("market-link-bar")
            compose.onNodeWithTag("market-link-label").assertTextEquals("QQQ · 分10级")
            compose.waitUntil(10_000) { requests.any { it.requestUrl?.queryParameter("symbols")?.contains("QQQUSDT") == true } }
            assertEquals(listOf("BTCUSDT", "XAUUSDT"), runBlocking { settings.settings.first().symbols })
            capture("market-link-pane")
            compose.onNodeWithTag("market-link-signals").performClick()
            await("market-all-signals")
            compose.onNodeWithTag("market-all-signals").performClick()
            compose.onNodeWithTag("market-link-kline").performClick()
            compose.waitUntil(10_000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" && it.requestUrl?.queryParameter("symbol") == "QQQUSDT" } }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            await("market-link-bar")
            compose.onNodeWithTag("market-link-clear").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("market-link-bar").fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun realBackendIsVisibleInMarketWithTheCompleteUniverse() {
        val backend = InstrumentationRegistry.getArguments().getString("backend_url")
        Assume.assumeTrue("Explicit real backend is required", backend != null)
        val response = runBlocking { (ServerSignalsRepository.create(backend!!).fetch() as GuailiResult.Success).value }
        val expectedSymbols = response.results.map { it.symbol }
        assertTrue(expectedSymbols.isNotEmpty())
        runBlocking {
            settings.save(originalSettings.copy(baseUrl = backend!!, symbols = expectedSymbols))
            preferences.update { MarketSignalPreferences(MarketView.SignalsV2, expectedSymbols) }
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-signals-v2")
            compose.waitUntil(15_000) { runBlocking { cache.read() }?.let {
                it.belongsTo(backend!!) && it.fullUniverse && it.response.results.map { row -> row.symbol }.toSet() == expectedSymbols.toSet()
            } == true }
            assertEquals(true, runBlocking { cache.read()!!.response.enabled })
            capture("market-v2-live")
            compose.onNodeWithTag("market-v2-status").performClick()
            await("market-v2-status-details")
            capture("market-v2-live-status")
        }
    }

    @Test fun marketRefreshSettingAppliesToV2AndSurvivesRecreation() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("market-view-v2")
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-card-BTCUSDT-btc")
            compose.onNodeWithTag("nav-settings").performClick()
            await("settings-market")
            compose.onNodeWithTag("settings-market").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("10s").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("10s").performClick()
            compose.onNodeWithText("Save").performClick()
            await("settings-market")
            assertEquals(10, runBlocking { settings.settings.first().autoRefreshSeconds })
            compose.onNodeWithTag("nav-market").performClick()
            await("market-signals-v2")
            compose.onNodeWithTag("market-v2-status").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("手机自动刷新：10秒（在行情设置中修改）").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("手机自动刷新：10秒（在行情设置中修改）").assertIsDisplayed()
            scenario.recreate()
            await("market-signals-v2")
            assertEquals(10, runBlocking { settings.settings.first().autoRefreshSeconds })
        }
    }

    private fun await(tag: String) = compose.waitUntil(12_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun showFilters() {
        if (compose.onAllNodesWithTag("market-v2-symbols").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("market-v2-edit").performClick()
            await("market-v2-symbols")
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val suffix = InstrumentationRegistry.getArguments().getString("visual_suffix", "phone")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null), "$name-$suffix.png"))
    }
    private fun fixture(): ServerSignalsResponse {
        val now = System.currentTimeMillis()
        val periods = listOf("1", "2", "3", "5", "8") + if (expandedCoverage) listOf("10") else emptyList()
        val symbols = listOf("BTCUSDT", "XAUUSDT", "QQQUSDT")
        return ServerSignalsResponse(!disabled, if (disabled) "disabled" else "ready", now,
            evaluatedAt = now, indicatorConfig = ServerSignalIndicatorConfig("EMA", 20),
            results = if (disabled) emptyList() else symbols.map { symbol ->
                val longPeriods = listOf("60", "90", "120", "180", "240")
                val nearPeriods = listOf("10", "15", "20", "30", "45")
                val signals = if (layoutFixture) when (symbol) {
                    "BTCUSDT" -> listOf(
                        ServerSignalStructure("btc", "extreme", "positive", listOf(ServerSignalRun("positive", periods)), 5, 5, "8", now - 60_000),
                        ServerSignalStructure("near", "compression", "neutral", listOf(ServerSignalRun("neutral", nearPeriods)), 5, 5, "45", now - 60_000),
                    )
                    "XAUUSDT" -> listOf(ServerSignalStructure("negative", "extreme", "negative", listOf(ServerSignalRun("negative", periods)), 5, 5, "8", now - 60_000))
                    else -> listOf(ServerSignalStructure("conflict", "conflict", "negative",
                        listOf(ServerSignalRun("negative", periods), ServerSignalRun("positive", longPeriods)), 5, 10, "240", now - 60_000))
                } else if (symbol != "BTCUSDT") emptyList() else listOf(ServerSignalStructure("btc", "extreme", "positive",
                    listOf(ServerSignalRun("positive", periods)), periods.size, periods.size, "8", now - 60_000, null, now - 60_000))
                ServerSymbolSignals(symbol, "ready", now,
                    signals = signals,
                    perIntervalQuality = (if (layoutFixture) periods + longPeriods + nearPeriods else periods).map { ServerIntervalEvidence(it, "ready", value = 14, guaili = 1.42,
                        longTrend = true, shortTrend = false, isClosed = false) })
            })
    }
}
