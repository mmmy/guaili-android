package com.gouge.guaili.integration

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.gouge.xbot.data.*
import com.gouge.xbot.widget.AlertWidgetRenderer
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

/** Uses isolated local HTTP fixtures; no production TV account operations. */
@RunWith(AndroidJUnit4::class)
class MarketTvAlertsIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val preferences = MarketSignalPreferencesStore(context)
    private val tableCache = GuailiSnapshotStore(context)
    private val signalCache = ServerSignalsSnapshotStore(context)
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val now = Instant.now()
    private lateinit var server: MockWebServer
    private lateinit var originalSettings: GuailiSettings
    private lateinit var originalPreferences: MarketSignalPreferences
    private var originalTable: GuailiSnapshot? = null
    private var originalSignals: ServerSignalsSnapshot? = null
    private var originalFailure: ServerSignalsFailure? = null
    private var savedPrefs: Map<String, Map<String, *>> = emptyMap()
    @Volatile private var unavailable = false
    @Volatile private var emptyAlerts = false
    @Volatile private var submitted = false
    @Volatile private var allowCompletion = false
    @Volatile private var rejectReset = false
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val configs = listOf(
        TvAlertConfigDto("a", "测试 · 业务有效期", "account-a", "_A_", startTimeParamIndex = 0, validBarsParamIndex = 1,
            params = listOf(TvAlertParamDto("0", "0"), TvAlertParamDto("1", "1"))),
        TvAlertConfigDto("unknown", "测试 · 无有效期", "account-a", "_UNKNOWN_"),
        TvAlertConfigDto("b", "测试 · 第二账户", "account-b", "_B_", startTimeParamIndex = 0, validBarsParamIndex = 1,
            params = listOf(TvAlertParamDto("0", "0"), TvAlertParamDto("1", "1"))),
        TvAlertConfigDto("hidden", "测试 · 隐藏配置", "account-hidden", "_HIDDEN_"),
    )

    @Before fun setup() = runBlocking {
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let {
            assertEquals(it, context.resources.configuration.fontScale, 0.01f)
        }
        originalSettings = settings.settings.first(); originalPreferences = preferences.preferences.first()
        originalTable = tableCache.read(); originalSignals = signalCache.read(); originalFailure = signalCache.readFailure()
        val prefNames = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.startsWith("xbot_") && it.extension == "xml" }.map { it.nameWithoutExtension }.toSet() +
            setOf("xbot_secure_session", "xbot_server_config", "xbot_alert_visibility", "xbot_alert_widget_data", "market_tv_tickers")
        savedPrefs = prefNames.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value } }
        SessionStore(context).clear()
        prefNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.requestUrl?.encodedPath
                    if (path == "/api/customer/tv-alert/list" && unavailable) return MockResponse().setResponseCode(503)
                    val body = when (path) {
                        "/api/customer/signal-view/list" -> "[]"
                        "/api/customer/tv-alert/list" -> Json.encodeToString(configs)
                        "/api/customer/tv-alert/all-alert-list" -> {
                            val cookie = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("cookieId").jsonPrimitive.content
                            Json.encodeToString(if (emptyAlerts) emptyList() else alerts(cookie))
                        }
                        "/api/customer/tv-alert/add-alerts" -> {
                            if (rejectReset) """{"result":false,"msg":"测试拒绝重设"}""" else {
                                submitted = true
                                """{"result":true}"""
                            }
                        }
                        "/api/customer/tv-alert/alert-process-status" -> if (!submitted) "[]" else Json.encodeToString(listOf(
                            TvAlertProcessDto("a", 1234, if (allowCompletion) "finished" else "running", 1, if (allowCompletion) 1 else 0)))
                        "/api/price-alerts" -> Json.encodeToString(listOf(PriceAlertDto(1, 1, 1, "BTCUSDT", "60", "BTCUSDT.P",
                            "测试 · 价格穿越", PriceAlertGeometry("horizontal_segment", PriceAlertPoint(1000, 100.0),
                                PriceAlertPoint(2000, 100.0), "right"), "cross_up", "once", "active",
                            webhookUrl = "http://localhost/test", messageTemplate = "{}", label = "测试", dataStatus = "live",
                            createdAt = now.toEpochMilli(), updatedAt = now.toEpochMilli())))
                        "/api/signals" -> Json.encodeToString(ServerSignalsResponse(true, "degraded", System.currentTimeMillis(),
                            results = listOf(ServerSymbolSignals("BTCUSDT", "degraded", System.currentTimeMillis()))))
                        "/api/indicators/guaili" -> {
                            val intervals = request.requestUrl?.queryParameter("intervals")?.split(',').orEmpty()
                            Json.encodeToString(GuailiResponse(listOf("BTCUSDT"), intervals, 1, 500, false, serverTime = System.currentTimeMillis(),
                                results = listOf(GuailiSymbolResult("BTCUSDT", intervals.filter { it != "10" }.mapIndexed { index, interval ->
                                    val point = GuailiPoint(value = if (index % 2 == 0) 5 else -4, guaili = 1.2, rankFilter = true,
                                        longTrend = true, shortTrend = false, isClosed = false, availability = "ready")
                                    GuailiSeries(interval, latest = point, data = listOf(point))
                                }))))
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ServerConfigStore(context).saveBaseUrl(server.url("/").toString())
        SessionStore(context).saveAccessToken("market-tv-local-fixture")
        AlertVisibilityStore(context).saveVisibleIds(setOf("a", "unknown", "b"), configs)
        tableCache.clear(); signalCache.clear()
        settings.save(originalSettings.copy(baseUrl = server.url("/").toString(), symbols = listOf("BTCUSDT"),
            intervals = listOf("W", "D", "240", "60", "30", "10"), layoutMode = LayoutMode.Groups,
            groupLayoutSize = GroupLayoutSize.TenColumns, autoRefreshSeconds = 5))
        preferences.update { MarketSignalPreferences(view = MarketView.Table, symbols = listOf("BTCUSDT")) }
    }

    @After fun cleanup() = runBlocking {
        settings.save(originalSettings); preferences.update { originalPreferences }
        originalTable?.let { tableCache.save(it) } ?: tableCache.clear()
        originalSignals?.let { signalCache.save(it) } ?: signalCache.clear()
        signalCache.recordFailure(originalFailure)
        SessionStore(context).clear()
        savedPrefs.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) -> editor.restoreValue(key, value) }
            check(editor.commit())
        }
        AlertWidgetRenderer.renderAll(context)
        server.shutdown()
    }

    @Test fun groupingDetailsAndResetShareConfirmedResultsWithTvManager() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-tv-alerts-BTCUSDT")
            compose.onNodeWithTag("market-tv-alerts-BTCUSDT").assertTextEquals("TV 6")
            compose.onNodeWithTag("market-period-tv-BTCUSDT-60", useUnmergedTree = true).assertTextEquals("TV2")
            compose.onNodeWithTag("market-period-tv-BTCUSDT-W", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("market-period-tv-BTCUSDT-10", useUnmergedTree = true).assertIsDisplayed()
            await("market-period-alert-BTCUSDT-60")
            assertTrue(requests.none { it.requestUrl?.encodedPath == "/api/customer/tv-alert/system-alert-cache/refresh" })
            capture("groups")
            compose.onNodeWithTag("market-cell-BTCUSDT-10").performClick()
            await("market-tv-entry-account-a-7")
            compose.onNodeWithText("业务过期：未配置").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("market-cell-BTCUSDT-60").performClick()
            await("market-tv-reset-account-a-1")
            compose.onNodeWithText("测试 · 第二账户").assertIsDisplayed()
            capture("detail")
            compose.onNodeWithTag("market-tv-reset-account-a-1").performClick()
            compose.onNodeWithText("确认重设警报？").assertIsDisplayed()
            assertFalse(submitted)
            compose.onNodeWithText("取消").performClick()
            assertFalse(submitted)
            compose.onNodeWithTag("market-tv-reset-account-a-1").performClick()
            capture("confirmation")
            compose.onNodeWithText("确认重设").performClick()
            compose.waitUntil(10_000) { submitted }
            compose.onNodeWithText("重设中…").assertIsDisplayed()
            assertEquals(1, requests.count { it.requestUrl?.encodedPath == "/api/customer/tv-alert/add-alerts" })
            allowCompletion = true
            await("market-tv-entry-account-a-101")
            compose.onNodeWithText("已重新设置", substring = true).assertIsDisplayed()
            compose.onNodeWithText("业务已过期", substring = true).assertDoesNotExist()
            capture("reset-completed")
            val write = requests.single { it.requestUrl?.encodedPath == "/api/customer/tv-alert/add-alerts" }
            val payload = Json.parseToJsonElement(write.body.clone().readUtf8()).jsonObject
            assertEquals("a", payload.getValue("alertId").jsonPrimitive.content)
            assertEquals("BINANCE:BTCUSDT.P", payload.getValue("symbols").jsonPrimitive.content)
            assertEquals("60", payload.getValue("periods").jsonPrimitive.content)
            assertTrue(payload.getValue("overwrite").jsonPrimitive.boolean)
            assertTrue(requests.none { it.requestUrl?.encodedPath?.contains("delete") == true })
            compose.onNode(hasText("前往 TV 管理") and hasAnyAncestor(hasTestTag("market-tv-entry-account-a-101")))
                .performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("警报管理").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav-alerts").assertIsSelected()
            compose.onNodeWithTag("nav-market").performClick()
            await("market-tv-alerts-BTCUSDT")
            runBlocking { settings.save(settings.settings.first().copy(layoutMode = LayoutMode.Table)) }
            await("market-table-list")
            compose.onNodeWithTag("market-period-tv-BTCUSDT-60", useUnmergedTree = true).assertTextEquals("TV2")
            capture("matrix")
        }
    }

    @Test fun explicitMappingPersistsAndNeverMergesSpotAndFutures() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("market-tv-alerts-BTCUSDT")
            compose.onNodeWithTag("market-tv-alerts-BTCUSDT").performClick()
            await("market-tv-alert-list")
            compose.onNodeWithTag("market-tv-edit-ticker").performClick()
            compose.onNodeWithTag("market-tv-ticker-input").performTextReplacement("BTCUSDT")
            compose.onNodeWithText("保存映射").performClick()
            compose.onNodeWithText("请输入完整 TV 代码", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("market-tv-ticker-input").performTextReplacement("BINANCE:BTCUSDT")
            compose.onNodeWithText("保存映射").performClick()
            compose.onNodeWithTag("market-tv-entry-account-a-4").assertIsDisplayed()
            compose.onNodeWithTag("market-tv-entry-account-a-1").assertDoesNotExist()
            compose.onNodeWithText("关闭").performClick()
            scenario.recreate()
            await("market-tv-alerts-BTCUSDT")
            compose.onNodeWithTag("market-period-tv-BTCUSDT-60", useUnmergedTree = true).assertTextEquals("TV1")
            compose.onNodeWithTag("market-tv-alerts-BTCUSDT").performClick()
            compose.onNodeWithText("BINANCE:BTCUSDT").assertIsDisplayed()
            compose.onNodeWithTag("market-tv-edit-ticker").performClick()
            compose.onNodeWithText("恢复默认").performClick()
            compose.onNodeWithTag("market-tv-entry-account-a-1").assertExists()
        }
    }

    @Test fun failedReadRetainsBadgesAndSuccessfulEmptyReadRemovesThem() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-tv-alerts-BTCUSDT")
            unavailable = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("TV 同步失败", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("market-period-tv-BTCUSDT-60", useUnmergedTree = true).assertTextEquals("TV2")
            compose.onNodeWithTag("market-cell-BTCUSDT-60").performClick()
            await("market-tv-reset-account-a-1")
            compose.onNodeWithTag("market-tv-reset-account-a-1").assertIsNotEnabled()
            assertFalse(submitted)
            device.pressBack()
            unavailable = false; emptyAlerts = true
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("market-tv-alerts-BTCUSDT").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("market-period-tv-BTCUSDT-60", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun rejectedResetKeepsOriginalAlertAndNeverReportsCompletion() {
        rejectReset = true
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            await("market-tv-alerts-BTCUSDT")
            compose.onNodeWithTag("market-cell-BTCUSDT-60").performClick()
            await("market-tv-reset-account-a-1")
            compose.onNodeWithTag("market-tv-reset-account-a-1").performClick()
            compose.onNodeWithText("确认重设").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("测试拒绝重设", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("market-tv-entry-account-a-1").assertIsDisplayed()
            compose.onNodeWithTag("market-tv-entry-account-a-101").assertDoesNotExist()
            compose.onNodeWithText("已重新设置", substring = true).assertDoesNotExist()
        }
    }

    private fun alerts(cookie: String): List<TvAlertDto> {
        fun alert(id: Long, name: String, period: String, ticker: String = "BINANCE:BTCUSDT.P", active: Boolean = true,
            created: Instant = now.minusSeconds(7200)) = TvAlertDto(active, "={\"symbol\":\"$ticker\"}", name, id, period, created.toString())
        return when (cookie) {
            "account-a" -> listOf(
                if (submitted && allowCompletion) alert(101, "_A_重新设置", "60", created = Instant.now()) else alert(1, "_A_一小时过期", "60"),
                alert(2, "_A_三十分钟过期", "30"), alert(3, "_A_停用", "240", active = false),
                alert(4, "_A_现货", "60", ticker = "BINANCE:BTCUSDT"),
                alert(5, "_A_其他交易所", "60", ticker = "BYBIT:BTCUSDT.P"),
                alert(6, "_A_周线", "1W", created = now), alert(7, "_UNKNOWN_无指标数据", "10", created = now))
            "account-b" -> listOf(alert(1, "_B_第二账户", "60", created = now))
            "account-hidden" -> listOf(alert(99, "_HIDDEN_不应显示", "60"))
            else -> emptyList()
        }
    }
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle(); device.waitForIdle(1000)
        val suffix = InstrumentationRegistry.getArguments().getString("visual_suffix", "phone")
        device.takeScreenshot(File(context.getExternalFilesDir(null), "market-tv-$name-$suffix.png"))
    }
    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
            else -> error("Unsupported preference value")
        }
    }
}
