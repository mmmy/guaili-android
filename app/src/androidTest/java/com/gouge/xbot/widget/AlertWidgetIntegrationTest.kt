package com.gouge.xbot.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Parcel
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.gouge.xbot.R
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import com.gouge.xbot.ui.theme.XbotTheme
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AlertWidgetIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var server: MockWebServer
    private val preferenceNames = (listOf(
        "xbot_secure_session", "xbot_server_config", "xbot_alert_visibility", "xbot_alert_widget_data",
        "xbot_signal_widget_preferences", "xbot_signal_icon_mappings", "xbot_tv_alert_preferences",
    ) + File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("xbot_") && it.extension == "xml" }
        .map { it.nameWithoutExtension }).distinct()
    private lateinit var savedPreferences: Map<String, Map<String, *>>
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val fail = AtomicBoolean(false)
    private var host: AppWidgetHost? = null
    private var widgetId: Int? = null
    private lateinit var hostView: AppWidgetHostView
    private val now = Instant.now()
    private val configs = listOf(config("a", "测试MA", "_A_"), config("b", "测试ATR", "_B_"), config("c", "未勾选", "_C_"))
    private val alerts = listOf(alert(1, "_A_", -7200), alert(2, "_A_", 5400), alert(3, "_B_", 280000), alert(4, "_C_", 3600))

    @Before fun setup() {
        savedPreferences = preferenceNames.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request.path.orEmpty())
                if (fail.get()) return MockResponse().setResponseCode(500)
                val body = when (request.path) {
                    "/api/customer/tv-alert/list" -> Json.encodeToString(configs)
                    "/api/customer/tv-alert/all-alert-list" -> Json.encodeToString(alerts)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        ServerConfigStore(context).saveBaseUrl(server.url("/").toString())
        SessionStore(context).saveAccessToken("alert-widget-instrumentation-token")
        AlertVisibilityStore(context).saveVisibleIds(setOf("a", "b"), configs)
        val store = AlertWidgetStore(context)
        store.saveSnapshot(store.currentScope()!!, AlertSnapshot(configs, mapOf("cookie" to alerts), now.toEpochMilli()))
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            widgetId?.let { host?.deleteAppWidgetId(it) }
            host?.stopListening()
        }
        if (AlertWidgetRenderer.widgetIds(context).isEmpty()) AlertWidgetScheduler.cancel(context)
        SessionStore(context).clear()
        savedPreferences.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
        AlertDataCoordinator(context).changed()
        server.shutdown()
    }

    @Test fun refreshUsesHomeSelectionAndNeverRebuildsTradingViewCache() = runBlocking {
        val coordinator = AlertDataCoordinator(context)
        val snapshot = coordinator.refresh()
        assertEquals(setOf("a", "b"), AlertVisibilityStore(context).getVisibleIds())
        assertEquals(listOf(1L, 2L, 3L), alertWidgetEntries(snapshot, setOf("a", "b")).map { it.alert.alertId })
        assertTrue(requests.contains("/api/customer/tv-alert/all-alert-list"))
        assertFalse(requests.any { it.contains("system-alert-cache") })
        coordinator.removeAlert("cookie", 1)
        assertFalse(AlertWidgetStore(context).snapshot()!!.alertsByCookieId["cookie"]!!.any { it.alertId == 1L })
        coordinator.replaceAccount("cookie", listOf(alert(99, "_A_", 10800)))
        assertEquals(listOf(99L), alertWidgetEntries(AlertWidgetStore(context).snapshot()!!, setOf("a", "b")).map { it.alert.alertId })
    }

    @Test fun failedRefreshKeepsLastSuccessfulDataAndAccountSwitchHidesIt() = runBlocking {
        val before = AlertWidgetStore(context).snapshot()!!
        fail.set(true)
        assertTrue(runCatching { AlertDataCoordinator(context).refresh() }.isFailure)
        val after = AlertWidgetStore(context).snapshot()!!
        assertEquals(before.alertsByCookieId, after.alertsByCookieId)
        assertEquals(before.updatedAtMillis, after.updatedAtMillis)
        assertNotNull(after.error)
        SessionStore(context).saveAccessToken("different-account-token")
        assertNull(AlertWidgetStore(context).snapshot())
        SessionStore(context).clear()
        assertNull(AlertWidgetStore(context).snapshot())
    }

    @Test fun normalAndCompactUseDifferentNativeRowHeights() {
        val entry = alertWidgetEntries(AlertWidgetStore(context).snapshot()!!, setOf("a")).first()
        val row = AlertWidgetRow.Alert(entry, grouped = true)
        instrumentation.runOnMainSync {
            val container = FrameLayout(context)
            val normal = AlertWidgetRenderer.rowViews(context, row, AlertWidgetDensity.Normal, now).apply(context, container)
            val compact = AlertWidgetRenderer.rowViews(context, row, AlertWidgetDensity.Compact, now).apply(context, container)
            val scale = context.resources.displayMetrics.density
            assertEquals((64 * scale + .5f).toInt(), normal.layoutParams.height)
            assertEquals((48 * scale + .5f).toInt(), compact.layoutParams.height)
        }
    }

    @Test fun settingsSaveModeOrderAndDensityWithoutChangingHomeSelection() {
        var saved: AlertWidgetSettings? = null
        val before = AlertVisibilityStore(context).getVisibleIds()
        compose.setContent { XbotTheme { AlertWidgetSettingsScreen(AlertWidgetSettings(), onCancel = {}) { saved = it } } }
        compose.onNodeWithText("正常").performClick()
        compose.onNodeWithText("按时间").performClick()
        compose.onNodeWithText("最晚优先").performClick()
        saveScreenshot("alert-widget-settings")
        compose.onNodeWithText("保存设置").performClick()
        assertEquals(AlertWidgetSettings(AlertWidgetMode.Time, AlertWidgetOrder.Latest, AlertWidgetDensity.Normal), saved)
        assertEquals(before, AlertVisibilityStore(context).getVisibleIds())
    }

    @Test fun realCollectionWidgetSupportsGroupCollapseAndExpansion() {
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.installedProviders.first { it.provider == ComponentName(context, AlertWidgetProvider::class.java) }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            instrumentation.runOnMainSync {
                host = AppWidgetHost(context, 81234)
                widgetId = host!!.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId!!, provider.provider))
                host!!.startListening()
            }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        compose.setContent {
            XbotTheme {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    AndroidView(modifier = Modifier.fillMaxWidth().height(340.dp), factory = { viewContext ->
                        host!!.createView(viewContext, widgetId!!, provider).also { hostView = it }
                    })
                }
            }
        }
        compose.runOnIdle { AlertWidgetRenderer.render(context, widgetId!!) }
        awaitRowCount(4)
        saveScreenshot("alert-widget-grouped-compact")
        onView(withText("测试MA")).perform(click())
        awaitRowCount(2)
        onView(withText("测试ATR")).perform(click())
        awaitRowCount(3)
        assertEquals(setOf("b"), AlertWidgetStore(context).settings(widgetId!!).expandedGroupIds)
        compose.runOnIdle {
            AlertWidgetStore(context).saveSettings(widgetId!!, AlertWidgetSettings(AlertWidgetMode.Time, AlertWidgetOrder.Latest, AlertWidgetDensity.Normal))
            AlertWidgetRenderer.render(context, widgetId!!)
        }
        awaitRowCount(3)
        saveScreenshot("alert-widget-time-normal")
        assertFalse(requests.any { it.contains("system-alert-cache") })
    }

    private fun awaitRowCount(expected: Int) {
        compose.waitUntil(timeoutMillis = 12_000) {
            var count = -1
            instrumentation.runOnMainSync { count = hostView.findViewById<ListView>(R.id.xbot_aw_list)?.adapter?.count ?: -1 }
            count == expected
        }
    }

    private fun saveScreenshot(name: String) {
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun config(id: String, title: String, prefix: String) = TvAlertConfigDto(id = id, title = title, cookieId = "cookie", namePre = prefix, sort = id.first().code.toDouble(),
        startTimeParamIndex = 0, validBarsParamIndex = 1, params = listOf(TvAlertParamDto("0", "0"), TvAlertParamDto("1", "0")))
    private fun alert(id: Long, prefix: String, offset: Long) = TvAlertDto(alertId = id, active = true, name = "$prefix$id", resolution = "60",
        createTime = now.plusSeconds(offset).toString(), symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\"}")

    @Test fun hundredsOfAlertsFitTheWidgetTransaction() {
        val snapshot = AlertSnapshot(configs, mapOf("cookie" to (1L..500L).map { alert(it, "_A_", it * 3600) }), now.toEpochMilli())
        val views = AlertWidgetRenderer.buildViews(context, 901, snapshot, setOf("a"),
            AlertWidgetSettings(mode = AlertWidgetMode.Time), loggedIn = true, now = now)
        val parcel = Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0)
            assertTrue("Widget parcel: ${parcel.dataSize()} bytes", parcel.dataSize() < 900_000)
        } finally { parcel.recycle() }
    }
}
