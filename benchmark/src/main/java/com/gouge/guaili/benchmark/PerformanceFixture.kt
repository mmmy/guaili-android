package com.gouge.guaili.benchmark

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.ArrayDeque
import kotlin.math.sin
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

internal const val TargetPackage = "com.gouge.guaili.perf"
private const val FullMarketVisibleSignals = 100
internal val FullMarketSymbols = listOf(
    "BTC", "XAU", "CL", "QQQ", "SKHYNIX", "ETH", "SOL", "BNB", "XRP", "ADA",
    "DOGE", "AVAX", "DOT", "LINK", "LTC", "BCH", "ATOM", "UNI", "NEAR", "APT",
    "ARB", "OP", "SUI", "INJ", "AAVE", "ETC", "FIL", "ICP", "TRX", "TON",
    "SPY", "DIA", "IWM", "NVDA", "AAPL", "MSFT", "AMZN", "META", "TSLA", "GOOGL",
).map { "${it}USDT" }
private val FullMarketPeriods = listOf(
    "10D", "W", "4D", "3D", "2D", "D", "720", "480", "360", "240", "180", "120", "90", "60",
    "45", "30", "20", "15", "10", "8", "5", "3", "2", "1", "45S", "30S", "15S", "10S",
)

/** Local, deterministic market data. No accounts, webhooks, or production services are used. */
internal class PerformanceFixture(
    private val fullSignals: Boolean = false,
    private val compactTable: Boolean = false,
) : AutoCloseable {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val now = System.currentTimeMillis()
                val body = when (url.encodedPath) {
                    "/api/indicators/guaili" -> matrix(url.queryParameter("symbols")!!.split(','), url.queryParameter("intervals")!!.split(','), now)
                    "/api/klines" -> candles(url.queryParameter("symbol")!!, url.queryParameter("intervals")!!, now)
                    "/api/signals" -> if (fullSignals) signals(now) else JSONObject().put("enabled", true).put("status", "warming_up")
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
        val extras = if (fullSignals) " --es symbols ${FullMarketSymbols.joinToString(",")} --ez full-signals true" else ""
        device.executeShellCommand("am start -W -n $TargetPackage/com.gouge.guaili.performance.PerformanceSetupActivity --es server http://127.0.0.1:${server.port}/$extras")
        device.waitForMatrix()
        if (compactTable) {
            device.findObject(By.desc("Switch to compact table"))?.click()
            device.waitForCompactTable()
        }
        device.pressHome()
    }

    /** Stable values/IDs: 140 wire structures become 100 rows after conflict suppression. */
    private fun signals(now: Long): JSONObject {
        fun run(direction: String, periods: List<String>, minimum: Int, maximum: Int): JSONObject = JSONObject()
            .put("direction", direction).put("intervals", JSONArray(periods))
            .put("minAbsValue", minimum).put("maxAbsValue", maximum).put("meanAbsValue", (minimum + maximum) / 2.0)
            .put("maxAbsGuaili", if (direction == "neutral") .04 else maximum / 10.0)
            .put("meanAbsGuaili", if (direction == "neutral") .035 else (minimum + maximum) / 20.0)
        fun structure(symbol: String, kind: String, direction: String, runs: List<JSONObject>, anchor: String): JSONObject {
            val count = runs.sumOf { it.getJSONArray("intervals").length() }
            return JSONObject().put("id", "$symbol-$kind-$direction").put("kind", kind).put("direction", direction)
                .put("runs", JSONArray(runs)).put("levelCount", count).put("totalLevelCount", count).put("anchorInterval", anchor)
                .put("firstObservedAt", now - 120_000).put("formedAt", now - 60_000).put("lastChangedAt", now - 60_000)
        }
        val results = JSONArray()
        FullMarketSymbols.forEachIndexed { symbolIndex, symbol ->
            val evidence = JSONArray()
            FullMarketPeriods.forEachIndexed { index, interval ->
                val value = when (index) {
                    in 0..3 -> (24 - index * 2) / 10.0
                    4 -> when (symbolIndex % 3) { 0 -> 0.0; 1 -> .04; else -> .4 }
                    in 5..8 -> (-22 + (index - 5) * 2) / 10.0
                    9 -> when (symbolIndex % 3) { 1 -> 0.0; 2 -> -.04; else -> -.4 }
                    in 10..16 -> (14 + index - 10) / 10.0
                    in 17..22 -> (-18 - (index - 17)) / 10.0
                    else -> if (index % 2 == 0) .04 else -.03
                }
                val duration = when {
                    interval.endsWith("W") -> (interval.dropLast(1).toLongOrNull() ?: 1) * 604_800_000L
                    interval.endsWith("D") -> (interval.dropLast(1).toLongOrNull() ?: 1) * 86_400_000L
                    interval.endsWith("S") -> interval.dropLast(1).toLong() * 1_000L
                    else -> interval.toLong() * 60_000L
                }
                val open = now / duration * duration
                evidence.put(JSONObject().put("interval", interval).put("availability", "ready")
                    .put("value", (value * 10).toInt()).put("guaili", value).put("ma", 100.0 + symbolIndex)
                    .put("atr14", 2.0).put("atrRank", 50.0).put("historyCount", 500)
                    .put("longTrend", value > 0).put("shortTrend", value < 0)
                    .put("openTime", open).put("closeTime", open + duration - 1)
                    .put("marketEventTime", now).put("isClosed", false))
            }
            val positivePeriods = FullMarketPeriods.subList(0, 4).reversed()
            val negativePeriods = FullMarketPeriods.subList(5, 9).reversed()
            val positive = run("positive", positivePeriods, 18, 24)
            val negative = run("negative", negativePeriods, 16, 22)
            val compressionPeriods = FullMarketPeriods.takeLast(5).reversed()
            val structures = JSONArray()
            if (symbolIndex % 2 == 0) structures.put(structure(symbol, "conflict", "mixed", listOf(negative, positive), negativePeriods.first()))
            structures.put(structure(symbol, "extreme", "positive", listOf(positive), positivePeriods.first()))
                .put(structure(symbol, "extreme", "negative", listOf(negative), negativePeriods.first()))
                .put(structure(symbol, "compression", "neutral", listOf(run("neutral", compressionPeriods, 0, 0)), compressionPeriods.first()))
            results.put(JSONObject().put("symbol", symbol).put("dataStatus", "ready").put("sampledAt", now)
                .put("marketSequence", 1).put("generation", 1).put("lastMarketEventTime", now)
                .put("signals", structures).put("perIntervalQuality", evidence).put("missingIntervals", JSONArray()))
        }
        return JSONObject().put("enabled", true).put("status", "ready").put("serverTime", now).put("evaluatedAt", now)
            .put("evaluationIntervalMs", 5_000).put("configHash", "performance-40x28-v1").put("ruleVersion", "live-v1")
            .put("runId", "performance-local").put("snapshotVersion", 1).put("candleMode", "live").put("evaluationMode", "sampled_live")
            .put("indicatorConfig", JSONObject().put("maType", "EMA").put("maLength", 20))
            .put("qualityConfig", JSONObject().put("maxMarketAgeMs", 30_000).put("maxResultAgeMs", 15_000)).put("results", results)
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

internal fun UiDevice.waitForCompactTable() {
    check(wait(Until.hasObject(By.desc("Switch to symbol groups")), 5_000)) { "Fixture must use the compact table" }
    waitForMatrix()
}

/** Prove a real table viewport moved before returning to the initial observation position. */
internal fun UiDevice.scrollMatrixViewport() {
    waitForCompactTable()
    val firstCell = "BTCUSDT, 10D,"
    val before = checkNotNull(freshVisibleBounds(firstCell)) { "First compact-table cell missing before horizontal scroll" }
    val y = before.centerY()
    swipe(displayWidth * 9 / 10, y, displayWidth / 3, y, 30)
    waitForIdle()
    val after = freshVisibleBounds(firstCell, allowMissing = true)
    check(after == null || after != before) { "Horizontal gesture did not move the compact table viewport" }
    swipe(displayWidth / 3, y, displayWidth * 9 / 10, y, 30)
    waitForMatrix()
}

/** Read fields from one accessibility tree; UiObject2.refresh fails for old APKs' offscreen cells. */
private fun UiDevice.freshVisibleBounds(descriptionPrefix: String, allowMissing: Boolean = false): Rect? {
    repeat(4) {
        val snapshot = compactTableSnapshot(descriptionPrefix)
        if (snapshot != null) {
            val bounds = snapshot.firstCellBounds
            if (allowMissing || bounds != null && !bounds.isEmpty) return bounds
        }
        waitForIdle(1_000)
    }
    throw IllegalStateException("Could not read a visible target-package compact-table snapshot after 4 attempts")
}

private data class CompactTableSnapshot(val firstCellBounds: Rect?)

@Suppress("DEPRECATION") // Recycle nodes on pre-33 Android; newer Android treats it as a no-op.
private fun UiDevice.compactTableSnapshot(descriptionPrefix: String): CompactTableSnapshot? {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
    val root = automation.rootInActiveWindow ?: return null
    if (root.packageName?.toString() != TargetPackage) { root.recycle(); return null }
    val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Rect>>()
    val windowClip = Rect(0, 0, displayWidth, displayHeight)
    val rootBounds = Rect().also(root::getBoundsInScreen)
    if (!windowClip.intersect(rootBounds)) { root.recycle(); return null }
    pending.add(root to windowClip)
    var tableVisible = false
    var matrixCellVisible = false
    var firstCellBounds: Rect? = null
    var visited = 0
    while (pending.isNotEmpty() && visited++ < 5_000) {
        val (node, ancestorClip) = pending.removeLast()
        val bounds = Rect().also(node::getBoundsInScreen)
        val visible = Rect(bounds)
        if (!node.isVisibleToUser || !visible.intersect(ancestorClip)) visible.setEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (!visible.isEmpty && description == "Switch to symbol groups") tableVisible = true
        if (!visible.isEmpty && description.startsWith("BTCUSDT, ")) matrixCellVisible = true
        if (description.startsWith(descriptionPrefix)) firstCellBounds = visible
        // UiObject2 visible bounds also clip against scrollable ancestors. Carry that
        // rectangle explicitly so old, still-composed offscreen cells count as clipped.
        val childClip = Rect(ancestorClip)
        if (node.isScrollable && !childClip.intersect(bounds)) childClip.setEmpty()
        repeat(node.childCount) { index -> node.getChild(index)?.let { pending.add(it to childClip) } }
        node.recycle()
    }
    val complete = pending.isEmpty()
    while (pending.isNotEmpty()) pending.removeLast().first.recycle()
    // Empty/wrong windows and partial trees must never prove that the first column left.
    return if (complete && tableVisible && matrixCellVisible) CompactTableSnapshot(firstCellBounds) else null
}

internal fun UiDevice.openSignalsV2() {
    check(wait(Until.hasObject(By.desc("v2 信号")), 10_000)) { "V2 selector missing" }
    findObject(By.desc("v2 信号")).click()
    check(wait(Until.hasObject(By.textContains("信号 v2 · ${FullMarketVisibleSignals}条")), 15_000)) { "Full-universe V2 fixture did not load all $FullMarketVisibleSignals visible structures" }
    check(wait(Until.hasObject(By.descContains("USDT · ")), 5_000)) { "V2 signal rows missing" }
    waitForIdle()
}

internal fun UiDevice.openPullbackObservation() {
    check(wait(Until.hasObject(By.desc("回调观察")), 10_000)) { "Pullback selector missing" }
    findObject(By.desc("回调观察")).click()
    check(wait(Until.hasObject(By.textStartsWith("40 品种 · 多 ")), 15_000)) { "Full-universe pullback fixture did not load all 40 symbols" }
    check(wait(Until.hasObject(By.descContains("回调级别 2D")), 5_000)) { "Pullback target evidence was not accepted" }
    waitForIdle()
}

/** Two round trips within the list; no fixed sleep or unbounded scroll-to-end. */
internal fun UiDevice.scrollMarketSignals() {
    val x = displayWidth * 2 / 3
    val bottom = displayHeight * 3 / 4
    val top = displayHeight * 2 / 5
    repeat(2) { swipe(x, bottom, x, top, 30) }
    repeat(2) { swipe(x, top, x, bottom, 30) }
}
