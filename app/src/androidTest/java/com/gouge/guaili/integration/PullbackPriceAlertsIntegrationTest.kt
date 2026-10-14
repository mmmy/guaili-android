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

/** All writes go to a device-local server; pre-test settings and caches are restored. */
@RunWith(AndroidJUnit4::class)
class PullbackPriceAlertsIntegrationTest {
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
    private val records = linkedMapOf<Long, PriceAlertDto>()
    private val receipts = mutableMapOf<String, JsonElement>()
    private val json = PriceAlertRepository.json
    private val now = System.currentTimeMillis()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Volatile private var lostReceipt = false
    @Volatile private var writeCount = 0

    @Before fun prepare() = runBlocking {
        savedSettings = settings.settings.first(); savedPrefs = prefs.preferences.first()
        savedSnapshot = cache.read(); savedFailure = cache.readFailure(); savedTable = table.read()
        records[1] = alert(1, "open_long", "120")
        records[2] = alert(2, "close_long", "240")
        records[3] = alert(3, "open_short", "120", true)
        records[4] = alert(4, "close_short", "60")
        records[5] = alert(5, "unclassified", "30")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(records) {
                    requests.add(request)
                    val path = request.requestUrl!!.encodedPath
                    if (path.startsWith("/api/price-alerts/mutations/")) return@synchronized receipts[path.substringAfterLast('/')]?.let(::response) ?: MockResponse().setResponseCode(404)
                    if (path == "/api/price-alerts/market") return@synchronized response(json.encodeToJsonElement(PriceAlertMarket("BTCUSDT", "BTCUSDT.P", "0.1", "ready")))
                    if (path == "/api/price-alerts") return@synchronized response(json.encodeToJsonElement(records.values.toList()))
                    if (path.startsWith("/api/price-alerts/")) {
                        val id = path.substringAfterLast('/').toLongOrNull() ?: return@synchronized MockResponse().setResponseCode(404)
                        val alert = records[id] ?: return@synchronized MockResponse().setResponseCode(404)
                        if (request.method == "GET") return@synchronized response(json.encodeToJsonElement(alert))
                        if (request.method == "PATCH") {
                            val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                            val mutation = body.getValue("mutationId").jsonPrimitive.content
                            receipts[mutation]?.let { return@synchronized response(it) }
                            if (body["expectedRevision"]?.jsonPrimitive?.long != alert.revision) return@synchronized MockResponse().setResponseCode(409)
                            val geometry = body["geometry"]?.let { json.decodeFromJsonElement<PriceAlertGeometry>(it) } ?: alert.geometry
                            val intent = body["intent"]?.jsonPrimitive?.content ?: alert.intent
                            val duplicate = records.values.firstOrNull { it.id != id && it.symbol == alert.symbol && it.interval == alert.interval && it.intent == intent && it.status == "active" }
                            if (duplicate != null) {
                                if (body["replaceActiveId"]?.jsonPrimitive?.long != duplicate.id || body["expectedReplaceRevision"]?.jsonPrimitive?.long != duplicate.revision) return@synchronized MockResponse().setResponseCode(409)
                                records[duplicate.id] = duplicate.copy(status = "disabled", revision = duplicate.revision + 1)
                            }
                            val updated = alert.copy(revision = alert.revision + 1, geometry = geometry, intent = intent,
                                dataStatus = if (geometry.first.timeMs > now) "waiting_for_range" else "live")
                            records[id] = updated; writeCount++
                            val result = json.encodeToJsonElement(updated); receipts[mutation] = result
                            return@synchronized if (lostReceipt) { lostReceipt = false; MockResponse().setResponseCode(503) } else response(result)
                        }
                    }
                    when (path) {
                        "/api/signals" -> response(json.encodeToJsonElement(signals()))
                        "/api/klines" -> response(json.encodeToJsonElement(klines(request.requestUrl!!.queryParameter("intervals") ?: "120", request.requestUrl!!.queryParameter("limit") == "1")))
                        "/api/alerts" -> response(JsonArray(emptyList()))
                        else -> response(json.encodeToJsonElement(GuailiResponse(emptyList(), emptyList(), 1, 500, false, results = emptyList())))
                    }
                }
            }; start()
        }
        cache.clear(); prefs.update { MarketSignalPreferences(view = MarketView.Pullback) }
        settings.save(savedSettings.copy(baseUrl = server.url("/").toString(), symbols = listOf("BTCUSDT"), autoRefreshSeconds = 60))
    }
    @After fun restore() = runBlocking {
        settings.save(savedSettings); prefs.update { savedPrefs }
        savedSnapshot?.let { cache.save(it) } ?: cache.clear(); cache.recordFailure(savedFailure)
        savedTable?.let { table.save(it) } ?: table.clear(); server.shutdown()
    }
    @Test fun groupingPrimaryPersistenceKlineNavigationAndDismissal() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("pullback-alert-1"); await("pullback-alert-3"); capture("main")
            compose.onNodeWithTag("pullback-unclassified-BTCUSDT").performClick(); await("pullback-alert-group")
            compose.onNodeWithTag("pullback-alert-5").assertExists(); device.pressBack(); gone("pullback-alert-group")
            compose.onNodeWithTag("pullback-alert-more-BTCUSDT-Long").performClick(); await("pullback-alert-group"); capture("group")
            compose.onNodeWithTag("pullback-primary-2").performClick()
            device.pressBack(); gone("pullback-alert-group"); await("pullback-alert-2")
            scenario.recreate(); await("pullback-alert-2")
            compose.onNodeWithTag("pullback-alert-group").assertDoesNotExist()
            compose.onNodeWithTag("pullback-alert-2").performClick(); await("price-alert-selection")
            compose.waitUntil(15000) { requests.any { it.requestUrl?.encodedPath == "/api/klines" && it.requestUrl?.queryParameter("intervals") == "240" } }
            device.pressBack(); device.pressBack(); await("pullback-page"); await("pullback-alert-2")
            assertEquals(0, writeCount)
        }
    }
    @Test fun extensionGlobalConfigurationFreshPreviewRecoveryAndUndo() {
        val original = records.getValue(3)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("pullback-extend-3"); compose.onNodeWithTag("pullback-extend-3").performClick()
            await("pullback-extension-sheet")
            compose.waitUntil(10000) { compose.onAllNodesWithText("延期后等待起点").fetchSemanticsNodes().isNotEmpty() }
            capture("preview")
            compose.onNodeWithTag("pullback-extension-settings").performScrollTo().performClick(); await("pullback-parameters-sheet")
            compose.onNodeWithTag("pullback-extension-bars").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick(); compose.onNodeWithTag("pullback-parameter-error").assertExists()
            compose.onNodeWithTag("pullback-extension-bars").performScrollTo().performTextReplacement("7"); device.pressBack()
            capture("settings")
            compose.onNodeWithTag("pullback-save").performScrollTo().performClick(); await("pullback-extension-sheet")
            compose.waitUntil(10000) { compose.onAllNodesWithText("7 根").fetchSemanticsNodes().isNotEmpty() }
            lostReceipt = true
            compose.onNodeWithTag("pullback-extension-confirm").performScrollTo().performClick()
            gone("pullback-extension-sheet")
            compose.waitUntil(10000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, writeCount)
            val saved = synchronized(records) { records.getValue(3) }
            val period = 120 * 60_000L
            assertEquals(now / period * period + 7 * period, saved.geometry.second.timeMs)
            assertEquals(original.geometry.second.timeMs - original.geometry.first.timeMs, saved.geometry.second.timeMs - saved.geometry.first.timeMs)
            val body = requests.first { it.method == "PATCH" }.body.clone().readUtf8().let { Json.parseToJsonElement(it).jsonObject }
            assertEquals(setOf("geometry", "expectedRevision", "mutationId"), body.keys)
            capture("success")
            compose.onNodeWithText("撤销").performClick()
            compose.waitUntil(10000) { synchronized(records) { records.getValue(3).geometry == original.geometry } }
            assertEquals(2, writeCount)
            scenario.recreate(); await("pullback-page")
            assertEquals(7, runBlocking { prefs.preferences.first().pullback.extensionBars })
        }
    }
    @Test fun intentEditorShowsConflictAndExplicitReplacementPreservesHistoryRecord() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("pullback-alert-1"); compose.onNodeWithTag("pullback-alert-1").performClick(); await("price-alert-selection")
            compose.onNodeWithContentDescription("编辑警报").performClick(); await("price-alert-editor")
            compose.onNodeWithTag("price-alert-intent-open_short").performScrollTo().performClick()
            compose.onNodeWithTag("price-alert-save").performClick(); await("price-alert-conflict-replace")
            capture("conflict")
            assertEquals(0, writeCount)
            compose.onNodeWithTag("price-alert-conflict-replace").performClick()
            compose.waitUntil(10000) { writeCount == 1 }
            val (old, replacement) = synchronized(records) { records.getValue(3) to records.getValue(1) }
            assertEquals("disabled", old.status); assertEquals("open_short", replacement.intent)
            assertEquals(5, synchronized(records) { records.size })
        }
    }
    private fun response(body: JsonElement) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun alert(id: Long, intent: String, interval: String, ended: Boolean = false): PriceAlertDto {
        val period = interval.toLong() * 60_000; val open = now / period * period
        val geometry = if (ended) PriceAlertGeometry("horizontal_segment", PriceAlertPoint(open - 7 * period, 85439.2), PriceAlertPoint(open - 2 * period, 85439.2), "none")
            else PriceAlertGeometry("horizontal_segment", PriceAlertPoint(open - period, 81497.3), PriceAlertPoint(open + period * 5, 81497.3), "right")
        return PriceAlertDto(id, 1, 1, "BTCUSDT", interval, "BTCUSDT.P", "价格警报", geometry, "cross_any", "once", "active",
            webhookUrl = "http://127.0.0.1/unused", messageTemplate = "{}", label = "", createdAt = now, updatedAt = now, intent = intent,
            dataStatus = if (ended) "range_ended" else "live")
    }
    private fun signals(): ServerSignalsResponse {
        val at = System.currentTimeMillis()
        val evidence = (listOf("D", "720", "480", "360", "240").zip(listOf(0.8, 0.6, 0.5, 0.3, 0.0)) +
            listOf("60", "45", "30", "20", "15").zip(listOf(-1.8, -1.4, -0.6, -0.3, -1.8))).map { (period, value) ->
            ServerIntervalEvidence(period, "ready", value = (value * 10).toInt(), guaili = value, marketEventTime = at, isClosed = false)
        }
        return ServerSignalsResponse(true, "ready", at, at, qualityConfig = ServerSignalQualityConfig(30000, 15000),
            indicatorConfig = ServerSignalIndicatorConfig("EMA", 20), results = listOf(ServerSymbolSignals("BTCUSDT", "ready", at, perIntervalQuality = evidence)))
    }
    private fun klines(interval: String, latest: Boolean): KlineEnvelope {
        val period = interval.toLongOrNull()?.times(60_000) ?: 86_400_000
        val open = now / period * period; val count = if (latest) 1 else 80
        return KlineEnvelope("BTCUSDT", listOf(interval), count, false, serverTime = System.currentTimeMillis(), series = listOf(KlineSeriesDto(interval, count = count,
            data = (0 until count).map { i -> val time = open - (count - 1 - i) * period
                KlineRowDto("BTCUSDT", interval, KlineCandleDto(Instant.ofEpochMilli(time).toString(), Instant.ofEpochMilli(time + period - 1).toString(),
                    81000.0, 81200.0, 80900.0, 81100.0, 1.0, 81000.0, 1, i != count - 1)) })))
    }
    private fun await(tag: String) = compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle()
        val suffix = InstrumentationRegistry.getArguments().getString("visual_suffix", "phone")
        device.takeScreenshot(File(context.getExternalFilesDir(null), "pullback-price-$name-$suffix.png"))
    }
}
