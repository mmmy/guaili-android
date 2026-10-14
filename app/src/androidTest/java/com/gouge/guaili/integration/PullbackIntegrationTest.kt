package com.gouge.guaili.integration

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real app + HTTP DTO/persistence/lifecycle/navigation; fixture state is restored afterwards. */
@RunWith(AndroidJUnit4::class)
class PullbackIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val prefs = MarketSignalPreferencesStore(context)
    private val cache = ServerSignalsSnapshotStore(context)
    private val table = GuailiSnapshotStore(context)
    private lateinit var savedSettings: GuailiSettings
    private lateinit var savedPrefs: MarketSignalPreferences
    private var savedSnapshot: ServerSignalsSnapshot? = null
    private var savedFailure: ServerSignalsFailure? = null
    private var savedTable: GuailiSnapshot? = null
    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile private var mode = "live"

    @Before fun prepare() = runBlocking {
        savedSettings = settings.settings.first(); savedPrefs = prefs.preferences.first()
        savedSnapshot = cache.read(); savedFailure = cache.readFailure(); savedTable = table.read()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.requestUrl?.encodedPath
                    if (path == "/api/signals" && mode == "error") return MockResponse().setResponseCode(503)
                    val body = when (path) {
                        "/api/signals" -> Json.encodeToString(fixture())
                        "/api/klines" -> """{"symbol":"BTCUSDT","intervals":["240"],"limit":300,"closedOnly":false,"series":[]}"""
                        "/api/alerts" -> "[]"
                        else -> Json.encodeToString(GuailiResponse(emptyList(), emptyList(), 1, 500, false, results = emptyList()))
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }; start()
        }
        cache.clear()
        prefs.update { MarketSignalPreferences(view = MarketView.Pullback) }
        settings.save(savedSettings.copy(baseUrl = server.url("/").toString(), symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT"), autoRefreshSeconds = 60))
    }
    @After fun restore() = runBlocking {
        settings.save(savedSettings); prefs.update { savedPrefs }
        savedSnapshot?.let { cache.save(it) } ?: cache.clear()
        cache.recordFailure(savedFailure); savedTable?.let { table.save(it) } ?: table.clear()
        server.shutdown()
    }

    @Test fun allControlsEvidenceCancellationKlinePersistenceAndDataStates() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("pullback-target-BTCUSDT-Long")
            compose.onNodeWithTag("pullback-period-BTCUSDT-Long", useUnmergedTree = true).assertTextEquals("4H")
            assertSingleRowToolbar()
            compose.onNodeWithTag("pullback-toolbar-levels", useUnmergedTree = true).assertTextEquals("5级")
            compose.onNodeWithTag("pullback-sound").assertIsOff().performClick()
            awaitToggle("pullback-sound", true)
            compose.onNodeWithTag("pullback-sound").performClick()
            awaitToggle("pullback-sound", false)
            capture("pullback-all")
            compose.onNodeWithTag("pullback-only-candidates").performClick()
            awaitToggle("pullback-only-candidates", true)
            compose.waitForIdle()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Short").assertDoesNotExist()
            compose.onNodeWithTag("pullback-symbol-AVAXUSDT").assertDoesNotExist()
            capture("pullback-candidates")
            compose.onNodeWithTag("pullback-only-candidates").performClick()
            awaitToggle("pullback-only-candidates", false)
            compose.onNodeWithTag("pullback-target-BTCUSDT-Short").assertExists()
            compose.onNodeWithTag("pullback-list").performScrollToNode(hasTestTag("pullback-symbol-AVAXUSDT"))
            compose.onNodeWithTag("pullback-symbol-AVAXUSDT").assertExists()
            compose.onNodeWithTag("pullback-list").performScrollToNode(hasTestTag("pullback-symbol-BTCUSDT"))
            compose.onNodeWithTag("pullback-only-candidates").performClick()
            awaitToggle("pullback-only-candidates", true)
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").performClick()
            await("pullback-evidence"); capture("pullback-evidence")
            compose.onNodeWithTag("pullback-evidence-close").performClick()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").assertIsNotSelected()
            refresh()
            compose.onNodeWithTag("pullback-evidence").assertDoesNotExist()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").assertIsNotSelected()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").performClick()
            await("pullback-evidence"); compose.waitForIdle()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("pullback-evidence").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").performClick()
            await("pullback-evidence")
            compose.onNodeWithTag("pullback-kline").performScrollTo().performClick()
            compose.waitUntil(15000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" } }
            val query = requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!
            assertEquals("BTCUSDT", query.queryParameter("symbol")); assertEquals("240", query.queryParameter("intervals"))
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); await("pullback-page")
            compose.onNodeWithTag("pullback-only-candidates").assertIsOn()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").assertIsNotSelected()
            compose.onNodeWithTag("pullback-parameters").performClick(); await("pullback-parameters-sheet")
            capture("pullback-parameters")
            compose.onNodeWithTag("pullback-levels").performTextReplacement("1")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick()
            await("pullback-parameter-error")
            compose.onNodeWithTag("pullback-levels").performScrollTo().performTextReplacement("6")
            compose.onNodeWithTag("pullback-threshold").performTextReplacement("11")
            compose.onNodeWithTag("pullback-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("pullback-minimum").performScrollTo().performTextReplacement("1")
            compose.onNodeWithTag("pullback-reverse").performScrollTo().performTextReplacement("3")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick()
            compose.onNodeWithTag("pullback-sound").performClick()
            compose.waitUntil(5000) { runBlocking { prefs.preferences.first().pullback.let { it.rules.levels == 6 && it.rules.threshold == 11 && it.soundEnabled } } }
            scenario.recreate(); await("pullback-page")
            compose.onNodeWithTag("pullback-only-candidates").assertIsOn(); compose.onNodeWithTag("pullback-sound").assertIsOn()
            compose.onNodeWithTag("pullback-toolbar-levels", useUnmergedTree = true).assertTextEquals("6级")
            assertSingleRowToolbar()
            capture("pullback-toolbar-on")
            mode = "empty"; refresh(); await("pullback-empty"); capture("pullback-empty")
            mode = "stale"; refresh(); await("pullback-status"); capture("pullback-stale")
            mode = "error"; refresh(); capture("pullback-error")
            compose.onNodeWithTag("market-view-v2").performClick(); await("market-v2-empty")
            compose.onNodeWithTag("market-view-table").performClick(); compose.onNodeWithTag("pullback-page").assertDoesNotExist()
            assertTrue(requests.filter { it.requestUrl?.encodedPath == "/api/signals" }.all { it.requestUrl?.queryParameter("symbols") == null })
        }
    }

    @Test fun shortHistoryGuailiShowsTargetsAndEvidenceWhileV2StaysEmpty() {
        mode = "short_history"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("pullback-target-BTCUSDT-Long")
            compose.onNodeWithTag("pullback-period-BTCUSDT-Long", useUnmergedTree = true).assertTextEquals("4H")
            compose.onAllNodesWithText("部分级别不可用", substring = true).assertCountEquals(0)
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").performClick()
            await("pullback-evidence")
            compose.onNodeWithText("+8", useUnmergedTree = true).assertExists()
            capture("pullback-short-history-evidence")
            compose.onNodeWithTag("pullback-evidence-close").performClick()
            compose.onNodeWithTag("market-view-v2").performClick()
            await("market-v2-empty")
            assertTrue(requests.any { it.requestUrl?.encodedPath == "/api/signals" })
        }
    }

    @Test fun columnsAlignAcrossDifferentPeriodsAndMissingDirections() {
        mode = "columns"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("pullback-target-BTCUSDT-Long")
            val rowTags = listOf("BTCUSDT-Long", "BTCUSDT-Short", "ETHUSDT-Long", "ETHUSDT-Short")
            compose.onNodeWithTag("pullback-period-BTCUSDT-Long", useUnmergedTree = true).assertTextEquals("4H")
            compose.onNodeWithTag("pullback-period-BTCUSDT-Short", useUnmergedTree = true).assertTextEquals("15m")
            val bounds = rowTags.associateWith { row ->
                compose.onNodeWithTag("pullback-list").performScrollToNode(hasTestTag("pullback-target-$row"))
                compose.waitForIdle()
                val period = compose.onNodeWithTag("pullback-period-$row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val entry = compose.onAllNodesWithTag("pullback-entry-$row", useUnmergedTree = true).fetchSemanticsNodes()
                    .singleOrNull()?.boundsInRoot
                val details = compose.onNodeWithTag("pullback-details-$row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                Triple(period, entry, details)
            }
            val first = bounds.getValue("BTCUSDT-Long")
            bounds.values.forEach { (period, entry, details) ->
                assertEquals("Main periods share one column", first.first.left, period.left, 1f)
                assertEquals("Main column width is stable", first.first.width, period.width, 1f)
                entry?.let {
                    assertEquals("Entry periods share one column", first.second!!.left, it.left, 1f)
                    assertEquals("Entry touch width is stable", first.second!!.width, it.width, 1f)
                    assertTrue("Entry remains at least 48dp wide", it.width + 1 >= 48 * context.resources.displayMetrics.density)
                    assertTrue("Period and entry do not overlap", period.right <= it.left)
                }
                assertEquals("Details do not shift for missing targets", first.third.left, details.left, 1f)
            }
            compose.onNodeWithTag("pullback-period-ETHUSDT-Long", useUnmergedTree = true).assertTextEquals("—")
            compose.onNodeWithTag("pullback-list").performScrollToNode(hasTestTag("pullback-symbol-BTCUSDT"))
            capture("pullback-aligned-columns")
            compose.onNodeWithTag("pullback-entry-BTCUSDT-Long").performClick()
            compose.waitUntil(15000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" } }
            assertEquals("10", requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!.queryParameter("intervals"))
        }
    }
    @Test fun entryPeriodOpensItsOwnKlineAndDivisorSavesCancelsAndRestores() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("pullback-entry-BTCUSDT-Long")
            val entry = compose.onNodeWithTag("pullback-entry-BTCUSDT-Long")
            entry.assertTextEquals("10m")
            val bounds = entry.fetchSemanticsNode().boundsInRoot
            val period = compose.onNodeWithTag("pullback-period-BTCUSDT-Long", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val minimum = 48f * context.resources.displayMetrics.density
            assertTrue(bounds.width + 1 >= minimum && bounds.height + 1 >= minimum)
            assertTrue("Period targets must not overlap", period.right <= bounds.left)
            assertEquals("Periods should align vertically", period.center.y, bounds.center.y, 2f)
            capture("pullback-entry-all")
            entry.performTouchInput { click() }
            compose.waitUntil(15000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" } }
            val query = requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!
            assertEquals("BTCUSDT", query.queryParameter("symbol")); assertEquals("10", query.queryParameter("intervals"))
            compose.onNodeWithTag("pullback-evidence").assertDoesNotExist()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); await("pullback-page")
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").assertIsNotSelected()
            compose.onNodeWithTag("pullback-parameters").performClick(); await("pullback-parameters-sheet")
            compose.onNodeWithTag("pullback-entry-divisor").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick(); await("pullback-parameter-error")
            assertEquals(20, runBlocking { prefs.preferences.first().pullback.rules.entryDivisor })
            compose.onNodeWithTag("pullback-entry-divisor").performScrollTo().performTextReplacement("16")
            compose.onNodeWithContentDescription("取消参数修改").performClick()
            assertEquals(20, runBlocking { prefs.preferences.first().pullback.rules.entryDivisor })
            compose.onNodeWithTag("pullback-parameters").performClick(); await("pullback-parameters-sheet")
            compose.onNodeWithTag("pullback-entry-divisor").performScrollTo().performTextReplacement("16")
            scenario.onActivity { activity ->
                val keyboard = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            compose.onNodeWithTag("pullback-save").performScrollTo()
            capture("pullback-entry-parameters")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { prefs.preferences.first().pullback.rules.entryDivisor == 16 } }
            scenario.recreate(); await("pullback-entry-BTCUSDT-Long")
            compose.onNodeWithTag("pullback-entry-BTCUSDT-Long").assertTextEquals("15m")
            capture("pullback-entry-restored")
            refresh()
            compose.onNodeWithTag("pullback-target-BTCUSDT-Long").assertIsNotSelected()
        }
    }
    @Test fun deployedBackendSuppliesRealFullPeriodEvidence() {
        runBlocking { settings.save(savedSettings.copy(baseUrl = "http://139.180.203.107:3005/", autoRefreshSeconds = 60)) }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(25000) {
                runBlocking { cache.read()?.let { it.belongsTo("http://139.180.203.107:3005/") && it.fullUniverse && it.response.qualityConfig != null } == true }
            }
            await("pullback-page"); compose.waitForIdle()
            val snapshot = runBlocking { cache.read()!! }
            assertEquals("live", snapshot.response.candleMode)
            assertEquals("sampled_live", snapshot.response.evaluationMode)
            assertTrue(snapshot.response.results.isNotEmpty())
            assertTrue(snapshot.response.results.any { it.perIntervalQuality.size >= 5 })
            capture("pullback-real-market")
        }
    }
    @Test fun klineReturnPreservesScrolledObservationPosition() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("pullback-target-BTCUSDT-Long")
            compose.onNodeWithTag("pullback-list").performScrollToNode(hasTestTag("pullback-target-AVAXUSDT-Short"))
            compose.waitForIdle()
            val before = compose.onNodeWithTag("pullback-symbol-AVAXUSDT").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("pullback-target-AVAXUSDT-Short").performClick(); await("pullback-evidence")
            compose.onNodeWithTag("pullback-kline").performScrollTo().performClick()
            compose.waitUntil(15000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" } }
            val query = requests.first { it.requestUrl?.encodedPath == "/api/klines" }.requestUrl!!
            assertEquals("AVAXUSDT", query.queryParameter("symbol")); assertEquals("15", query.queryParameter("intervals"))
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); await("pullback-page"); compose.waitForIdle()
            assertEquals(before, compose.onNodeWithTag("pullback-symbol-AVAXUSDT").fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.onNodeWithTag("pullback-target-AVAXUSDT-Short").assertIsNotSelected()
        }
    }
    private fun awaitToggle(tag: String, on: Boolean) {
        val expected = if (on) ToggleableState.On else ToggleableState.Off
        compose.waitUntil(5000) {
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expected
        }
    }

    private fun assertSingleRowToolbar() {
        val toolbar = compose.onNodeWithTag("pullback-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = listOf("pullback-parameters", "pullback-only-candidates", "pullback-sound", "pullback-refresh")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val minimumTouch = 48f * context.resources.displayMetrics.density
        controls.forEach { bounds ->
            assertEquals("Toolbar actions must stay on one line", toolbar.center.y, bounds.center.y, 2f)
            assertTrue("Touch target too small", bounds.width + 1 >= minimumTouch && bounds.height + 1 >= minimumTouch)
            assertTrue("Action outside toolbar", bounds.left >= toolbar.left && bounds.right <= toolbar.right)
        }
        controls.zipWithNext().forEach { (left, right) -> assertTrue("Toolbar targets overlap", left.right <= right.left) }
    }

    private fun refresh() {
        val count = requests.count { it.requestUrl?.encodedPath == "/api/signals" }
        Thread.sleep(1100) // The real shared refresh cache intentionally reuses responses for one second.
        compose.onNodeWithTag("pullback-refresh").performClick()
        compose.waitUntil(15000) { requests.count { it.requestUrl?.encodedPath == "/api/signals" } > count }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("pullback-page").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
    }
    private fun await(tag: String) = compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(450) // Let the native ripple finish so saved screenshots express toggle state.
        compose.waitForIdle()
        val suffix = InstrumentationRegistry.getArguments().getString("visual_suffix", "phone")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null), "$name-$suffix.png"))
    }
    private fun fixture(): ServerSignalsResponse {
        val now = System.currentTimeMillis()
        val at = if (mode == "stale") now - 16000 else now
        return ServerSignalsResponse(true, "ready", now, at, qualityConfig = ServerSignalQualityConfig(30000, 15000),
            indicatorConfig = ServerSignalIndicatorConfig("EMA", 20), results = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT").map { symbol ->
                val longValues = listOf(0.8, 0.6, 0.5, 0.3, if (mode == "empty" || symbol in listOf("SOLUSDT", "AVAXUSDT")) 0.4 else 0.0)
                val shortValues = listOf(-1.8, -1.4, -0.6, -0.3, if (mode == "empty" || symbol in listOf("BTCUSDT", "AVAXUSDT")) -1.8 else 0.0)
                val evidence = (listOf("D", "720", "480", "360", "240").zip(longValues) + listOf("60", "45", "30", "20", "15").zip(shortValues)).map { (period, value) ->
                    val g = if (mode == "columns" && symbol == "ETHUSDT" && period in listOf("D", "720", "480", "360", "240")) 0.0 else value
                    ServerIntervalEvidence(period, "ready", value = (g * 10).toInt(), guaili = g, marketEventTime = at, isClosed = false)
                }
                if (mode == "short_history") ServerSymbolSignals(symbol, "warming_up", at,
                    perIntervalQuality = evidence.map { e -> e.copy(availability = "warming_up",
                        reasonCode = "insufficient_history", value = null, guaili = null, historyCount = 30,
                        guailiEvidence = e.copy(historyCount = 30)) },
                    missingIntervals = evidence.map { it.interval })
                else ServerSymbolSignals(symbol, "ready", at, perIntervalQuality = evidence)
            })
    }
}
