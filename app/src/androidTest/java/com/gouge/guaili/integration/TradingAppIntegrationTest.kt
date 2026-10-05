package com.gouge.guaili.integration

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.GuailiRepository
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.AppDestination
import com.gouge.guaili.ui.resolveAppLaunch
import com.gouge.xbot.XbotNavigation
import com.gouge.xbot.data.ApiClientFactory
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.SignalViewDto
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import com.gouge.xbot.data.XbotRepository
import com.gouge.xbot.ui.XbotPage
import com.gouge.xbot.widget.AlertWidgetActionActivity
import com.gouge.xbot.widget.AlertWidgetIntents
import com.gouge.xbot.widget.AlertWidgetRenderer
import com.gouge.xbot.widget.SignalWidgetRenderer
import com.gouge.xbot.widget.WidgetPreferences
import com.gouge.xbot.widget.WidgetState
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the real host Activity. XBot writes are restricted to a private MockWebServer.
 * Existing encrypted XBot preferences are held opaquely and restored; Guaili settings are read only.
 * These tests do not uninstall the app, clear app data, or require a reachable production backend.
 */
@RunWith(AndroidJUnit4::class)
class TradingAppIntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var xbotServer: MockWebServer
    private lateinit var preferences: XbotPreferenceBackup
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile
    private var alertFixture = false
    @Volatile
    private var visualFixture = false

    @Before
    fun isolateXbotAccountAndServer() {
        preferences = XbotPreferenceBackup(context)
        preferences.captureAndClear()
        xbotServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return when (request.method to request.requestUrl?.encodedPath) {
                        "POST" to "/api/customer/login" -> json("""{"access_token":"$TestToken"}""")
                        "POST" to "/api/customer/logout" -> json("true")
                        "GET" to "/api/customer/signal-view/list" -> json(if (visualFixture) visualSignals() else "[]")
                        "GET" to "/api/customer/tv-alert/list" -> json(when {
                            visualFixture -> visualAlertConfigs()
                            alertFixture -> AlertConfigs
                            else -> "[]"
                        })
                        "POST" to "/api/customer/tv-alert/all-alert-list" -> json(when {
                            visualFixture -> visualAlertRows()
                            alertFixture -> AlertRows
                            else -> "[]"
                        })
                        else -> json("""{"error":"Unexpected test request"}""").setResponseCode(404)
                    }
                }
            }
            start()
        }
        ServerConfigStore(context).saveBaseUrl(xbotServer.url("/").toString())
    }

    @After
    fun restoreXbotPreferences() {
        try {
            if (::preferences.isInitialized) {
                // Invalidate any late coroutine from the test account before restoring encrypted data.
                SessionStore(context).clear()
                preferences.restore()
                SignalWidgetRenderer.renderAll(context)
                AlertWidgetRenderer.renderAll(context)
            }
        } finally {
            if (::xbotServer.isInitialized) xbotServer.shutdown()
        }
    }

    @Test
    fun unauthenticatedAccountKeepsMarketAndAllFourDestinationsAvailable() {
        ActivityScenario.launch<MainActivity>(hostIntent()).use {
            awaitTag("nav-market")
            compose.onNodeWithTag("nav-market").assertIsSelected()
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            listOf("nav-market", "nav-signals", "nav-alerts", "nav-settings").forEach {
                compose.onNodeWithTag(it).assertIsDisplayed()
            }

            select("nav-signals")
            awaitTag("xbot-signals-locked")
            compose.onNodeWithTag("xbot-alerts-locked").assertDoesNotExist()
            select("nav-alerts")
            awaitTag("xbot-alerts-locked")
            compose.onNodeWithTag("xbot-signals-locked").assertDoesNotExist()

            select("nav-settings")
            awaitTag("settings-market")
            compose.onNodeWithTag("settings-account").assertIsDisplayed()
            select("nav-market")
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
            assertTrue("Browsing without login must not submit an XBot operation", requests.none {
                it.method != "GET"
            })
        }
    }

    @Test
    fun xbotLaunchersOpenSignalsAlertsAndAccountInHost() {
        listOf(XbotPage.Signals to "xbot-signals-locked", XbotPage.Alerts to "xbot-alerts-locked")
            .forEach { (page, lockedTag) ->
                val intent = XbotNavigation.createIntent(context, page)
                assertEquals(MainActivity::class.java.name, intent.component?.className)
                ActivityScenario.launch<MainActivity>(intent).use {
                    awaitTag(lockedTag)
                    compose.onNodeWithTag(if (page == XbotPage.Signals) "nav-signals" else "nav-alerts")
                        .assertIsSelected()
                }
            }

        val account = XbotNavigation.createAccountIntent(context)
        assertEquals(MainActivity::class.java.name, account.component?.className)
        ActivityScenario.launch<MainActivity>(account).use {
            awaitTag("xbot-login-submit")
            compose.onNodeWithTag("xbot-signals-locked").assertDoesNotExist()
            compose.onNodeWithTag("xbot-alerts-locked").assertDoesNotExist()
        }
    }

    @Test
    fun consumedLaunchDoesNotReplayAfterRecreation() {
        ActivityScenario.launch<MainActivity>(XbotNavigation.createIntent(context, XbotPage.Signals)).use { scenario ->
            awaitTag("xbot-signals-locked")
            select("nav-alerts")
            awaitTag("xbot-alerts-locked")
            scenario.recreate()
            awaitTag("xbot-alerts-locked")
            compose.onNodeWithTag("nav-alerts").assertIsSelected()
            compose.onNodeWithTag("xbot-signals-locked").assertDoesNotExist()
        }
    }

    @Test
    fun newIntentsSwitchExistingActivityAndKeepGuailiKlineTarget() {
        ActivityScenario.launch<MainActivity>(hostIntent()).use { scenario ->
            awaitTag("nav-market")
            lateinit var originalActivity: MainActivity
            scenario.onActivity { originalActivity = it }

            deliver(XbotNavigation.createIntent(context, XbotPage.Signals))
            awaitTag("xbot-signals-locked")
            deliver(XbotNavigation.createIntent(context, XbotPage.Alerts))
            awaitTag("xbot-alerts-locked")
            deliver(XbotNavigation.createAccountIntent(context))
            awaitTag("xbot-login-submit")

            deliver(klineIntent("BTCUSDT", "15"))
            awaitText("K-line")
            compose.onNodeWithText("BTCUSDT").assertIsDisplayed()
            compose.onNodeWithText("15m").assertIsDisplayed()
            compose.onNodeWithTag("nav-market").assertIsSelected()
            scenario.onActivity { assertSame("singleTop must route to the current host", originalActivity, it) }
        }
    }

    @Test
    fun guailiWidgetColdLaunchOpensTheRequestedKlineWithoutXbotLogin() {
        ActivityScenario.launch<MainActivity>(klineIntent("BTCUSDT", "60")).use {
            awaitText("K-line")
            compose.onNodeWithText("BTCUSDT").assertIsDisplayed()
            compose.onNodeWithText("1h").assertIsDisplayed()
            compose.onNodeWithTag("nav-market").assertIsSelected()
            compose.onNodeWithTag("xbot-login-submit").assertDoesNotExist()
        }
    }

    @Test
    fun alertWidgetBridgeExpandsAndScrollsToTheRequestedAlert() {
        alertFixture = true
        SessionStore(context).saveAccessToken(TestToken)
        ActivityScenario.launch<MainActivity>(hostIntent()).use { scenario ->
            awaitTag("nav-market")
            lateinit var originalActivity: MainActivity
            scenario.onActivity { originalActivity = it }
            val widget = Intent(context, AlertWidgetActionActivity::class.java)
                .putExtra(AlertWidgetIntents.Kind, AlertWidgetIntents.Open)
                .putExtra(AlertWidgetIntents.ConfigId, "integration-config")
                .putExtra(AlertWidgetIntents.AlertId, 6L)
            deliver(widget)
            awaitText("TARGETUSDT")
            compose.onNodeWithText("TARGETUSDT").assertIsDisplayed()
            compose.onNodeWithTag("nav-alerts").assertIsSelected()
            scenario.onActivity { assertSame(originalActivity, it) }

            // The second identical widget tap must trigger a new reveal after manual collapse.
            compose.onNodeWithText("收起").performScrollTo().performClick()
            compose.onNodeWithText("TARGETUSDT").assertDoesNotExist()
            deliver(widget)
            awaitText("TARGETUSDT")
            compose.onNodeWithText("TARGETUSDT").assertIsDisplayed()
            scenario.onActivity { assertSame(originalActivity, it) }
            assertFalse("A widget tap must not create, reset, or delete an alert", requests.any {
                it.method != "GET" && it.requestUrl?.encodedPath != "/api/customer/tv-alert/all-alert-list"
            })
        }
    }

    @Test
    fun loginAndLogoutKeepMarketSettingsAndClearBothAccountPages() {
        val marketSettings = runBlocking { SettingsStore(context).settings.first() }
        ActivityScenario.launch<MainActivity>(XbotNavigation.createAccountIntent(context)).use {
            awaitTag("xbot-login-submit")
            compose.onNodeWithTag("xbot-login-server").performTextReplacement(xbotServer.url("/").toString())
            compose.onNodeWithTag("xbot-login-username").performTextReplacement("integration-user")
            compose.onNodeWithTag("xbot-login-password").performTextReplacement("integration-password")
            val submit = compose.onNodeWithTag("xbot-login-submit")
            if (!submit.isDisplayed()) submit.performScrollTo()
            submit.performClick()
            awaitTag("xbot-logout")
            compose.onNodeWithText("返回").performScrollTo().performClick()

            select("nav-signals")
            awaitTag("signal-empty")
            select("nav-alerts")
            awaitTag("alert-empty")
            select("nav-settings")
            compose.onNodeWithTag("settings-account").performClick()
            awaitTag("xbot-logout")
            compose.onNodeWithTag("xbot-logout").performClick()
            awaitTag("xbot-logout-confirm")
            compose.onNodeWithTag("xbot-logout-confirm").performClick()
            awaitTag("xbot-login-submit")
            compose.onNodeWithText("返回").performScrollTo().performClick()

            select("nav-signals")
            awaitTag("xbot-signals-locked")
            compose.onNodeWithTag("signal-empty").assertDoesNotExist()
            select("nav-alerts")
            awaitTag("xbot-alerts-locked")
            compose.onNodeWithTag("alert-empty").assertDoesNotExist()
            select("nav-market")
            compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        }
        assertEquals(marketSettings, runBlocking { SettingsStore(context).settings.first() })
        val login = requests.single { it.requestUrl?.encodedPath == "/api/customer/login" }
        assertNull("Login must not send a previous session", login.getHeader("Authorization"))
        val logout = requests.single { it.requestUrl?.encodedPath == "/api/customer/logout" }
        assertEquals("Bearer $TestToken", logout.getHeader("Authorization"))
        assertTrue(requests.filter { it.requestUrl?.encodedPath?.endsWith("/list") == true }.all {
            it.getHeader("Authorization") == "Bearer $TestToken"
        })
    }

    @Test
    fun marketClientDoesNotInheritXbotAuthorizationAndInvalidatedClientCannotSend() = runBlocking<Unit> {
        val marketServer = MockWebServer()
        try {
            marketServer.enqueue(json("""{"symbols":["BTCUSDT"],"intervals":["15"],"limit":3,"calcLimit":500,"closedOnly":false,"results":[]}"""))
            marketServer.start()
            var sessionCurrent = true
            var token = TestToken
            val xbot = ApiClientFactory.create(
                xbotServer.url("/").toString(),
                isSessionCurrent = { sessionCurrent },
                tokenProvider = { token },
            )
            xbot.getSignalViews()
            val signal = xbotServer.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("Bearer $TestToken", signal?.getHeader("Authorization"))

            val marketSettings = GuailiSettings.defaults().copy(
                baseUrl = marketServer.url("/").toString(),
                symbols = listOf("BTCUSDT"),
                intervals = listOf("15"),
            )
            assertTrue(GuailiRepository.create(marketSettings.baseUrl).fetch(marketSettings) is GuailiResult.Success)
            val market = marketServer.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/indicators/guaili", market?.requestUrl?.encodedPath)
            assertNull("The market client must remain unauthenticated", market?.getHeader("Authorization"))

            sessionCurrent = false
            token = "another-integration-session"
            val requestCount = xbotServer.requestCount
            try {
                xbot.getSignalViews()
                fail("An API client bound to a previous account must reject a later request")
            } catch (_: IOException) {
                // Rejection must happen before a replacement token reaches the old server.
            }
            assertEquals(requestCount, xbotServer.requestCount)
        } finally {
            marketServer.shutdown()
        }
    }

    @Test
    fun accountSwitchRejectsOldWidgetCacheAndLateUnauthorizedCleanup() {
        val session = SessionStore(context)
        val widgets = WidgetPreferences(context)
        val id = 900101
        session.saveAccessToken("integration-account-a")
        val generationA = session.generation()
        val scopeA = session.currentScope()
        val stateA = WidgetState.from(listOf(SignalViewDto(id = "signal-a", symbol = "TESTAUSDT")))
        widgets.save(id, stateA, expectedScope = scopeA)
        assertEquals("signal-a", widgets.get(id)?.signals?.single()?.signalId)

        session.saveAccessToken("integration-account-b")
        assertNull("A widget snapshot from A must be invisible in B", widgets.get(id))
        val stateB = WidgetState.from(listOf(SignalViewDto(id = "signal-b", symbol = "TESTBUSDT")))
        widgets.save(id, stateB)
        widgets.save(id, stateA, expectedScope = scopeA)
        assertEquals("A late A write must not replace B's widget", "signal-b", widgets.get(id)?.signals?.single()?.signalId)
        assertFalse("A late 401 from A must not clear B", session.clearIfCurrent(generationA))
        // Both inspected values are synthetic; original encrypted credentials remain in the opaque backup.
        assertEquals("integration-account-b", session.getAccessToken())
    }

    @Test
    fun delayedAccountResponseIsCancelledAfterSwitchWithoutClearingNewSession() = runBlocking<Unit> {
        val delayedServer = MockWebServer()
        try {
            delayedServer.enqueue(json("""[{"_id":"old-account-signal","symbol":"TESTAUSDT"}]""")
                .setBodyDelay(600, TimeUnit.MILLISECONDS))
            delayedServer.start()
            val session = SessionStore(context)
            ServerConfigStore(context).saveBaseUrl(delayedServer.url("/").toString())
            session.saveAccessToken("integration-delayed-a")
            val repository = XbotRepository(ServerConfigStore(context), session).forSession(session.generation())
            val pending = async(Dispatchers.IO) { runCatching { repository.getSignalViews() } }
            val oldRequest = delayedServer.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("Bearer integration-delayed-a", oldRequest?.getHeader("Authorization"))
            session.saveAccessToken("integration-delayed-b")
            val response = pending.await()
            assertTrue("A late A result must be discarded", response.exceptionOrNull() is CancellationException)
            assertEquals("integration-delayed-b", session.getAccessToken())
        } finally {
            delayedServer.shutdown()
        }
    }

    /** Opt-in screenshot batch: adb instrumentation argument visual=true; normal suites skip it. */
    @Test
    fun visualSmokeScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in visual capture", arguments.getString("visual") == "true")
        visualFixture = true
        SessionStore(context).saveAccessToken(TestToken)
        val device = UiDevice.getInstance(instrumentation)
        val output = File(context.cacheDir, "integration-v1").apply { mkdirs() }
        val suffix = arguments.getString("visualSuffix").orEmpty().replace(Regex("[^a-zA-Z0-9_-]"), "")
        val originalHardwareIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        fun capture(name: String) {
            compose.waitForIdle()
            device.waitForIdle()
            assertTrue("Screenshot capture failed", device.takeScreenshot(File(output, "$name$suffix.png")))
        }
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            ActivityScenario.launch<MainActivity>(hostIntent()).use { scenario ->
                awaitTag("nav-market")
                compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
                capture("01-market")
                select("nav-signals")
                awaitText("测试 · 有效多头")
                capture("02-signals")
                select("nav-alerts")
                awaitText("测试 · 警报有效期")
                capture("03-alerts")
                select("nav-settings")
                awaitTag("settings-account")
                capture("04-settings")
                compose.onNodeWithTag("settings-account").performClick()
                awaitTag("xbot-logout")
                compose.onNodeWithTag("xbot-logout").performClick()
                awaitTag("xbot-logout-confirm")
                compose.onNodeWithTag("xbot-logout-confirm").performClick()
                awaitTag("xbot-login-submit")
                val username = compose.onNodeWithTag("xbot-login-username")
                if (!username.isDisplayed()) username.performScrollTo()
                username.performClick().performTextReplacement("测试账户")
                compose.waitUntil(timeoutMillis = 10_000) {
                    var keyboardVisible = false
                    scenario.onActivity {
                        keyboardVisible = ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    keyboardVisible
                }
                capture("05-account-keyboard")
            }
        } finally {
            if (originalHardwareIme == "null") {
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            } else {
                originalHardwareIme.toIntOrNull()?.let {
                    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $it")
                }
            }
        }
    }

    // MainActivity.setIntent replaces its Intent on widget taps. ActivityScenario matches lifecycle
    // callbacks by action/component/categories, so every host launch must use the same launcher filters.
    private fun hostIntent(): Intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))

    private fun klineIntent(symbol: String, interval: String): Intent = hostIntent()
        .putExtra(MainActivity.ExtraWidgetSymbol, symbol)
        .putExtra(MainActivity.ExtraWidgetInterval, interval)
        .also {
            val launch = resolveAppLaunch(it)
            assertEquals(AppDestination.Market, launch?.destination)
            assertEquals(symbol, launch?.klineTarget?.symbol)
            assertEquals(interval, launch?.klineTarget?.interval)
        }

    private fun deliver(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun select(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithText(text).isDisplayed()
        }
    }

    companion object {
        private const val TestToken = "integration-only-xbot-token"
        private const val AlertConfigs = """[{"_id":"integration-config","title":"Integration alerts","cookieId":"integration-cookie","namePre":"integration_","periods":"15"}]"""
        private val AlertRows = (1..6).joinToString(prefix = "[", postfix = "]") { id ->
            val ticker = if (id == 6) "TARGETUSDT" else "ROW${id}USDT"
            """{"alert_id":$id,"active":true,"symbol":"={\"symbol\":\"BINANCE:$ticker\"}","name":"integration_$id","resolution":"15"}"""
        }

        private fun visualSignals(): String {
            val now = Instant.now()
            return Json.encodeToString(listOf(
                SignalViewDto(id = "visual-valid", name = "测试 · 有效多头", symbol = "BTCUSDT",
                    comment = "仅供整合界面测试", periods = listOf("15", "60", "240"), longOn = true,
                    expireAt = now.plusSeconds(7_200).toString(), levelMin = "15", levelMax = "240"),
                SignalViewDto(id = "visual-expired", name = "测试 · 已过期空头", symbol = "ETHUSDT",
                    comment = "测试数据 · 无真实交易", periods = listOf("60", "240"), shortOn = true,
                    expireAt = now.minusSeconds(3_600).toString(), levelMin = "60", levelMax = "240"),
            ))
        }

        private fun visualAlertConfigs(): String = Json.encodeToString(listOf(
            TvAlertConfigDto(id = "visual-alerts", title = "测试 · 警报有效期", cookieId = "visual-cookie",
                namePre = "visual_", periods = "15 60", tickerIds = "BINANCE:BTCUSDT.P",
                startTimeParamIndex = 0, validBarsParamIndex = 1,
                params = listOf(TvAlertParamDto(index = "0", value = "0"), TvAlertParamDto(index = "1", value = "4"))),
        ))

        private fun visualAlertRows(): String {
            val now = Instant.now()
            return Json.encodeToString(listOf(
                TvAlertDto(alertId = 7001, active = true, name = "visual_valid", resolution = "15",
                    symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\"}", createTime = now.minusSeconds(900).toString()),
                TvAlertDto(alertId = 7002, active = true, name = "visual_expired", resolution = "15",
                    symbol = "={\"symbol\":\"BINANCE:ETHUSDT.P\"}", createTime = now.minusSeconds(7_200).toString()),
            ))
        }

        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}

/** Saves encrypted session bytes without decrypting, inspecting, or logging them. */
private class XbotPreferenceBackup(private val context: Context) {
    private var backup: Map<String, Map<String, *>> = emptyMap()

    fun captureAndClear() {
        backup = preferenceNames().associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }
        backup.keys.forEach { name ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
    }

    fun restore() {
        (preferenceNames() + backup.keys).forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            backup[name].orEmpty().forEach { (key, value) -> editor.restoreValue(key, value) }
            check(editor.commit()) { "Failed to restore XBot test preferences" }
        }
    }

    private fun preferenceNames(): Set<String> = File(context.applicationInfo.dataDir, "shared_prefs")
        .listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("xbot_") && it.extension == "xml" }
        .mapTo(mutableSetOf()) { it.nameWithoutExtension }
        .apply {
            addAll(listOf(
                "xbot_secure_session", "xbot_server_config", "xbot_alert_visibility",
                "xbot_alert_widget_data", "xbot_signal_widget_preferences",
                "xbot_signal_icon_mappings", "xbot_tv_alert_preferences",
            ))
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
            else -> error("Unsupported SharedPreferences value type")
        }
    }
}
