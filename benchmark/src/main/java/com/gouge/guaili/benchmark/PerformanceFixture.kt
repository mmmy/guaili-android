package com.gouge.guaili.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlin.math.sin
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

internal const val TargetPackage = "com.gouge.guaili.perf"

/** Local, deterministic market data. No accounts, webhooks, or production services are used. */
internal class PerformanceFixture : AutoCloseable {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val now = System.currentTimeMillis()
                val body = when (url.encodedPath) {
                    "/api/indicators/guaili" -> matrix(url.queryParameter("symbols")!!.split(','), url.queryParameter("intervals")!!.split(','), now)
                    "/api/klines" -> candles(url.queryParameter("symbol")!!, url.queryParameter("intervals")!!, now)
                    "/api/signals" -> JSONObject().put("enabled", true).put("status", "warming_up")
                        .put("serverTime", now).put("evaluatedAt", now).put("results", JSONArray())
                    "/api/price-alerts/market" -> JSONObject().put("symbol", url.queryParameter("symbol"))
                        .put("tvSymbol", "BINANCE:BTCUSDT.P").put("tickSize", "0.01").put("status", "ready")
                    else -> JSONArray()
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
            }
        }
        start()
    }

    fun configure(device: UiDevice) {
        check(device.executeShellCommand("pm clear $TargetPackage").trim() == "Success")
        device.executeShellCommand("am start -W -n $TargetPackage/com.gouge.guaili.performance.PerformanceSetupActivity --es server http://127.0.0.1:${server.port}/")
        device.waitForMatrix()
        device.pressHome()
    }

    override fun close() {
        // Stop foreground refreshes before their local data source disappears.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("am force-stop $TargetPackage")
        server.shutdown()
    }

    private fun matrix(symbols: List<String>, intervals: List<String>, now: Long): JSONObject {
        val results = JSONArray()
        symbols.forEachIndexed { symbolIndex, symbol ->
            val series = JSONArray()
            intervals.forEachIndexed { index, interval ->
                val point = JSONObject().put("openTime", Instant.ofEpochMilli(now - 60_000).toString())
                    .put("closeTime", Instant.ofEpochMilli(now + 60_000).toString())
                    .put("ma", 100.0).put("atr14", 2.0).put("atrRank", 50.0).put("rankFilter", true)
                    .put("value", (index + symbolIndex) % 60 - 30).put("guaili", ((index + symbolIndex) % 60 - 30) / 10.0)
                    .put("longTrend", true).put("shortTrend", false).put("isClosed", false).put("availability", "ready")
                series.put(JSONObject().put("interval", interval).put("count", 3).put("latest", point)
                    .put("data", JSONArray().put(JSONObject(point.toString()).put("isClosed", true))
                        .put(JSONObject(point.toString()).put("isClosed", true)).put(point)))
            }
            results.put(JSONObject().put("symbol", symbol).put("series", series))
        }
        return JSONObject().put("symbols", JSONArray(symbols)).put("intervals", JSONArray(intervals))
            .put("limit", 3).put("calcLimit", 500).put("closedOnly", false).put("timezone", "UTC")
            .put("serverTime", now).put("results", results)
    }

    private fun candles(symbol: String, interval: String, now: Long): JSONObject {
        val data = JSONArray()
        val duration = when {
            interval.endsWith("W") -> (interval.dropLast(1).toLongOrNull() ?: 1) * 604_800_000L
            interval.endsWith("D") -> (interval.dropLast(1).toLongOrNull() ?: 1) * 86_400_000L
            interval.endsWith("S") -> interval.dropLast(1).toLong() * 1_000L
            else -> interval.toLong() * 60_000L
        }
        val start = now / duration * duration - 299 * duration
        repeat(300) { index ->
            val price = 100.0 + index * .02 + sin(index * .2) * 3
            val candle = JSONObject().put("openTime", Instant.ofEpochMilli(start + index * duration).toString())
                .put("closeTime", Instant.ofEpochMilli(start + (index + 1) * duration - 1).toString())
                .put("open", price).put("close", price + .3).put("high", price + 1).put("low", price - 1)
                .put("volume", 10 + index % 20).put("quoteVolume", price * 10).put("tradeCount", 10).put("isClosed", index < 299)
            data.put(JSONObject().put("symbol", symbol).put("interval", interval).put("candle", candle))
        }
        return JSONObject().put("symbol", symbol).put("intervals", JSONArray().put(interval))
            .put("limit", 300).put("closedOnly", false).put("serverTime", now)
            .put("series", JSONArray().put(JSONObject().put("interval", interval).put("count", 300).put("data", data)))
    }
}

internal fun UiDevice.openKline() {
    waitForMatrix()
    findObject(By.descStartsWith("BTCUSDT, 10D,")).click()
    check(wait(Until.hasObject(By.text("View K-line")), 5_000)) { "Cell details missing" }
    findObject(By.text("View K-line")).click()
    check(wait(Until.hasObject(By.desc("K线图表")), 10_000)) { "K-line data missing" }
    waitForIdle()
}

internal fun UiDevice.panChart() {
    val bounds = findObject(By.desc("K线图表")).visibleBounds
    val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
    val left = bounds.left + (8 * density).toInt()
    val right = bounds.right - (68 * density).toInt()
    val start = left + (right - left) * 4 / 5
    val end = left + (right - left) / 4
    val y = bounds.top + bounds.height() * 2 / 5
    swipe(start, y, end, y, 30)
    swipe(end, y, start, y, 30)
}

internal fun UiDevice.waitForMatrix() {
    check(wait(Until.hasObject(By.descStartsWith("BTCUSDT, 10D,")), 15_000)) { "Fixture matrix did not load" }
}

internal fun UiDevice.scrollMatrix() {
    waitForMatrix()
    val y = findObject(By.descStartsWith("BTCUSDT, 10D,")).visibleBounds.centerY()
    swipe(displayWidth * 9 / 10, y, displayWidth / 3, y, 30)
    swipe(displayWidth / 3, y, displayWidth * 9 / 10, y, 30)
}
