package com.gouge.guaili.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.content.Context
import com.gouge.guaili.data.PriceAlertRepository
import com.gouge.guaili.ui.theme.GuailiTheme
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI and HTTP contract tests against a device-local server; no real signal receiver is contacted. */
@RunWith(AndroidJUnit4::class)
class PriceAlertInteractionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val json = PriceAlertRepository.json
    private val writes = CopyOnWriteArrayList<JsonObject>()
    private val mutations = ConcurrentHashMap<String, JsonObject>()
    @Volatile private var alert: JsonObject? = null
    @Volatile private var legacy: JsonObject? = null
    private val legacyWrites = CopyOnWriteArrayList<JsonObject>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let {
            assertEquals("设备资源的字体缩放必须与测试场景一致", it, context.resources.configuration.fontScale, .01f)
        }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                fun response(value: JsonElement, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(value.toString())
                if (path == "/api/klines") return response(klines())
                if (path == "/api/alerts" && request.method == "GET") return response(buildJsonArray { legacy?.let(::add) })
                if (path == "/api/alerts/7" && request.method == "PATCH") {
                    val fields = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    legacyWrites += fields
                    legacy = JsonObject(legacy!!.toMap() + fields.filterValues { it != JsonNull })
                    return response(legacy!!)
                }
                if (path == "/api/price-alerts/market") return response(buildJsonObject { put("symbol", "BTCUSDT"); put("tvSymbol", "BTCUSDT.P"); put("tickSize", "0.10"); put("status", "ready"); put("reason", JsonNull) })
                if (path.startsWith("/api/price-alerts/mutations/")) return mutations[path.substringAfterLast('/')]?.let { response(it) } ?: MockResponse().setResponseCode(404)
                if (path.endsWith("/events")) return response(buildJsonArray {})
                if (request.method == "GET" && path == "/api/price-alerts") return response(buildJsonArray { alert?.let(::add) })
                if (request.method == "GET" && path == "/api/price-alerts/1") return alert?.let { response(it) } ?: MockResponse().setResponseCode(404)
                if (request.method == "POST" || request.method == "PATCH") {
                    val fields = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val mutationId = fields["mutationId"]!!.jsonPrimitive.content
                    mutations[mutationId]?.let { return response(it) }
                    val previous = alert
                    if (request.method == "PATCH" && fields["expectedRevision"]!!.jsonPrimitive.long != previous?.get("revision")?.jsonPrimitive?.long) return MockResponse().setResponseCode(409)
                    writes += fields
                    val changedGeometry = fields["geometry"] != null && fields["geometry"] != previous?.get("geometry")
                    val updated = buildJsonObject {
                        previous?.forEach { (k, v) -> put(k, v) }
                        fields.forEach { (k, v) -> if (k != "mutationId" && k != "expectedRevision") put(k, v) }
                        put("id", 1); put("revision", (previous?.get("revision")?.jsonPrimitive?.long ?: 0) + 1)
                        put("armGeneration", (previous?.get("armGeneration")?.jsonPrimitive?.long ?: 0) + if (changedGeometry || previous == null) 1 else 0)
                        put("dataStatus", "live"); put("createdAt", 1); put("updatedAt", System.currentTimeMillis())
                        put("triggeredAt", JsonNull); put("deliveryStatus", JsonNull); put("deliveryError", JsonNull)
                    }
                    alert = updated; mutations[mutationId] = updated
                    return response(updated, if (request.method == "POST") 201 else 200)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        compose.setContent {
            GuailiTheme { KlineScreen(server.url("/").toString(), listOf("BTCUSDT"), listOf("60", "15"), "BTCUSDT", "60", 60, false, {}) }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("price-alert-chart").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun shutdown() { server.shutdown() }
    private fun drawHorizontal() {
        compose.onNodeWithTag("draw-horizontal-alert").performClick()
        compose.onNodeWithTag("price-alert-chart").performTouchInput { click(Offset(width * .3f, height * .25f)) }
        compose.onNodeWithText("点选终点，完成线段").assertIsDisplayed()
        compose.onNodeWithTag("price-alert-chart").performTouchInput { click(Offset(width * .65f, height * .25f)) }
        compose.onNodeWithTag("price-alert-editor").assertIsDisplayed()
    }
    private fun saveNew() {
        compose.onNodeWithText("警报名称").performTextReplacement("测试 · 价格线")
        compose.onNodeWithText("通知", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("price-alert-webhook").performTextReplacement(server.url("/mock-webhook").toString())
        screenshot("editor-notifications")
        compose.onNodeWithTag("price-alert-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("price-alert-editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("price-alert-selection").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Semantics can settle one frame before the emulator's surface is presented.
        Thread.sleep(350)
        val target = File(context.getExternalFilesDir(null), "price-alert-$name.png")
        assertTrue(device.takeScreenshot(target))
        File(context.getExternalFilesDir(null), "price-alert-$name.metrics.txt").writeText("fontScale=${context.resources.configuration.fontScale}\n")
    }
    @Test fun drawDragRearmEditPauseAndHistoryUseRealTouchAndNewApi() {
        drawHorizontal(); screenshot("editor-conditions"); saveNew()
        assertEquals(1, writes.size)
        val template = json.parseToJsonElement(writes[0]["messageTemplate"]!!.jsonPrimitive.content).jsonObject
        assertFalse(template["noConfirm"]!!.jsonPrimitive.boolean)
        assertTrue(template["price"]!!.jsonPrimitive.isString)
        assertEquals("60", writes[0]["interval"]!!.jsonPrimitive.content)
        screenshot("selected")
        compose.onNodeWithTag("price-alert-chart").performTouchInput {
            swipe(Offset(width * .48f, height * .25f), Offset(width * .48f, height * .34f), 600)
        }
        compose.waitUntil(10_000) { writes.size >= 2 && compose.onAllNodesWithText("正在保存").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, writes.size)
        assertEquals(setOf("geometry", "mutationId", "expectedRevision"), writes[1].keys)
        assertEquals(1, writes[1]["expectedRevision"]!!.jsonPrimitive.long)
        assertNotEquals(writes[0]["geometry"], writes[1]["geometry"])
        compose.onNodeWithTag("price-alert-editor").assertDoesNotExist()
        screenshot("after-drag")
        compose.onNodeWithContentDescription("撤销移动").performClick()
        compose.waitUntil(10_000) { writes.size == 3 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在保存").fetchSemanticsNodes().isEmpty() }
        assertEquals(writes[0]["geometry"], writes[2]["geometry"])
        compose.onNodeWithContentDescription("暂停警报").performClick()
        compose.waitUntil(10_000) { writes.size == 4 && compose.onAllNodesWithText("手动暂停").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("disabled", writes[3]["status"]!!.jsonPrimitive.content)
        compose.onNodeWithContentDescription("触发记录").performClick()
        compose.onNodeWithText("暂无触发记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭触发记录").performClick()
        compose.onNodeWithContentDescription("编辑警报").performClick()
        compose.onNodeWithTag("price-alert-editor").assertIsDisplayed()
        compose.onNodeWithText("消息", useUnmergedTree = true).performClick()
        screenshot("editor-message")
        compose.onNodeWithTag("price-alert-template").assertIsDisplayed()
    }
    @Test fun cancellingDrawingAndPinchNeverWriteAnAlert() {
        compose.onNodeWithTag("draw-trend-alert").performClick()
        compose.onNodeWithTag("price-alert-chart").performTouchInput { click(Offset(width * .3f, height * .3f)) }
        compose.onNodeWithText("取消绘图").performClick()
        compose.onNodeWithText("点选终点，完成线段").assertDoesNotExist()
        compose.onNodeWithTag("price-alert-chart").performTouchInput {
            down(0, Offset(width * .35f, height * .4f)); down(1, Offset(width * .65f, height * .4f))
            moveTo(0, Offset(width * .2f, height * .4f)); moveTo(1, Offset(width * .8f, height * .4f))
            up(0); up(1)
        }
        assertTrue(writes.isEmpty())
        screenshot("chart")
    }
    @Test fun trendEndpointChangesOnlyTheChosenEndpointAndKeepsBinding() {
        compose.onNodeWithTag("draw-trend-alert").performClick()
        compose.onNodeWithTag("price-alert-chart").performTouchInput { click(Offset(width * .3f, height * .25f)) }
        compose.onNodeWithTag("price-alert-chart").performTouchInput { click(Offset(width * .65f, height * .4f)) }
        saveNew()
        assertEquals("trend_segment", writes[0]["geometry"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        screenshot("trend-selected")
        compose.onNodeWithTag("price-alert-chart").performTouchInput {
            swipe(Offset(width * .65f, height * .4f), Offset(width * .78f, height * .3f), 600)
        }
        compose.waitUntil(10_000) { writes.size == 2 && compose.onAllNodesWithText("正在保存").fetchSemanticsNodes().isEmpty() }
        assertEquals(writes[0]["geometry"]!!.jsonObject["first"], writes[1]["geometry"]!!.jsonObject["first"])
        assertNotEquals(writes[0]["geometry"]!!.jsonObject["second"], writes[1]["geometry"]!!.jsonObject["second"])
        assertFalse(writes[1].containsKey("interval"))
        screenshot("trend-after-endpoint")
        compose.onNodeWithContentDescription("触发记录").performClick()
        compose.onNodeWithText("暂无触发记录").assertIsDisplayed()
    }
    @Test fun legacyPriceAlertsRemainManageableThroughTheirOriginalEndpoint() {
        legacy = buildJsonObject {
            put("id", 7); put("symbol", "BTCUSDT"); put("interval", "60"); put("price", 101); put("direction", "cross_up"); put("status", "active")
            put("expiresAt", JsonNull); put("webhookUrl", server.url("/mock-webhook").toString()); put("messageTemplate", "{}"); put("createdAt", 1); put("updatedAt", 1)
        }
        compose.onNodeWithTag("open-price-alerts").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("旧版固定价位").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("101 · 上穿").performClick()
        compose.onNodeWithText("旧版固定价位警报").assertIsDisplayed()
        compose.onNodeWithText("暂停", substring = false).performClick()
        compose.waitUntil(10_000) { legacyWrites.size == 1 }
        assertEquals("disabled", legacyWrites.single()["status"]!!.jsonPrimitive.content)
        assertTrue(writes.isEmpty())
    }
    private fun klines(): JsonObject {
        val now = Instant.now().toEpochMilli() / 3_600_000 * 3_600_000
        return buildJsonObject {
            put("symbol", "BTCUSDT"); put("intervals", buildJsonArray { add("60") }); put("limit", 300); put("closedOnly", false)
            put("series", buildJsonArray { add(buildJsonObject {
                put("interval", "60"); put("data", buildJsonArray {
                    repeat(100) { index -> add(buildJsonObject {
                        put("symbol", "BTCUSDT"); put("interval", "60"); put("candle", buildJsonObject {
                            put("openTime", Instant.ofEpochMilli(now - (99 - index) * 3_600_000L).toString()); put("closeTime", Instant.ofEpochMilli(now - (98 - index) * 3_600_000L - 1).toString())
                            val price = 100 + kotlin.math.sin(index * .3) * 5
                            put("open", price); put("high", price + 2); put("low", price - 2); put("close", price + .5)
                            put("volume", 10); put("quoteVolume", 1000); put("tradeCount", 10); put("isClosed", index != 99)
                        })
                    }) }
                })
            }) })
        }
    }
}
