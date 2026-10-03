package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.data.GuailiDeviceTime
import com.gouge.guaili.data.GuailiServerClock
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.ServerSignalsRefreshUseCase
import com.gouge.guaili.data.ServerSignalsRepository
import com.gouge.guaili.data.ServerSignalsSnapshotStore
import com.gouge.guaili.data.ServerSignalsSnapshot
import com.gouge.guaili.data.ServerSignalsResponse
import com.gouge.guaili.data.belongsTo
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDetector
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.domain.GuailiTable
import com.gouge.guaili.settings.SettingsStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Opt-in emulator integration: host backend at :3005, an existing launcher widget, no app clearing. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ServerSignalsV2IntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val localBaseUrl = "http://10.0.2.2:3005/"
    private val privateWidgetId = 900026
    private val configuredSymbols = listOf(
        "BTCUSDT", "XAUUSDT", "QQQUSDT", "CLUSDT", "CXMTUSDT", "KORUUSDT", "SOXLUSDT", "UNITREEUSDT",
    )

    @Test fun a_liveBackendResponseUsesTypedDynamicSignalsAndMillisecondTimes() = runBlocking<Unit> {
        val result = ServerSignalsRepository.create(localBaseUrl).fetch()
        assertTrue("Local backend must expose /api/signals: $result", result is GuailiResult.Success)
        val response = (result as GuailiResult.Success).value
        assertTrue(response.serverTime > 0L)
        assertNotNull("The running sampler must publish an evaluation timestamp", response.evaluatedAt)
        assertTrue(response.evaluatedAt!! <= response.serverTime + 2_000L)
        assertTrue(response.status in setOf("warming_up", "ready", "degraded", "disabled", "config_error"))
        assertEquals("live", response.candleMode)
        assertEquals("sampled_live", response.evaluationMode)
        assertTrue(response.evaluationIntervalMs > 0L)
        assertTrue(response.runId.isNotBlank())
        assertTrue(response.configHash.isNotBlank())
        response.results.forEach { symbol ->
            assertTrue(symbol.symbol.isNotBlank())
            assertTrue(symbol.sampledAt > 0L)
            symbol.perIntervalQuality.forEach { evidence ->
                evidence.openTime?.let { assertTrue(it > 0L) }
                if (evidence.availability == "ready") assertEquals(false, evidence.isClosed)
            }
        }
    }

    @Test fun b_configurationRetainsLegacyModeAndSavesV2TypesAndTenSymbolLimit() = runBlocking<Unit> {
        val settingsStore = SettingsStore(context)
        val originalSettings = settingsStore.settings.first()
        val configs = WidgetConfigStore(context)
        val legacyStore = GuailiSnapshotStore(context)
        val originalLegacySnapshot = legacyStore.read()
        // Temporary symbols exercise the widget limit independently of the backend's configured symbols.
        val symbols = (originalSettings.symbols + (1..11).map { "V2TEST${it}USDT" }).distinct().take(11)
        val testSettings = originalSettings.copy(baseUrl = localBaseUrl, symbols = symbols)
        try {
            settingsStore.save(testSettings)
            configs.save(privateWidgetId, WidgetConfig(
                symbols = symbols.take(10),
                intervals = WidgetConfigStore.defaultIntervals(testSettings.intervals),
                mode = WidgetMode.Signals,
                enabledSignalKinds = GuailiSignalKind.entries.toSet(),
            ))
            val intent = Intent(context, GuailiWidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, privateWidgetId)
            ActivityScenario.launch<GuailiWidgetConfigurationActivity>(intent).use {
                assertTrue(device.wait(Until.hasObject(By.text("信号模式")), 10_000L))
                assertTrue(device.hasObject(By.text("信号模式 v2")))
                device.findObject(By.text("信号模式 v2")).click()
                configurationObject(By.text("上方 / 下方乖离共振"))
                configurationObject(By.text("多周期近均线")).click()
                configurationObject(By.text("长短周期分歧"))
                configurationObject(By.text("品种（10/10）"))

                val eleventh = configurationObject(By.text(symbols.last()))
                var row: UiObject2? = eleventh.parent
                while (row != null && row.findObject(By.checkable(true)) == null) row = row.parent
                val checkbox = row?.findObject(By.checkable(true))
                assertNotNull("The eleventh symbol must have a disabled checkbox", checkbox)
                assertFalse("Ten selected symbols prevent selecting an eleventh", checkbox!!.isEnabled)

                device.findObject(By.text("保存小组件")).click()
                assertTrue(device.wait(Until.gone(By.text("配置乖离小组件")), 12_000L))
            }
            val saved = configs.read(privateWidgetId, testSettings)
            assertEquals(WidgetMode.SignalsV2, saved.mode)
            assertEquals(symbols.take(10), saved.symbols)
            assertEquals(setOf(GuailiSignalKind.Extreme, GuailiSignalKind.Conflict), saved.enabledSignalKinds)
            assertEquals(10, WidgetConfigStore.maxSymbols(WidgetMode.SignalsV2))
            assertEquals(10, WidgetConfigStore.maxSymbols(WidgetMode.Signals))
        } finally {
            configs.delete(privateWidgetId)
            settingsStore.save(originalSettings)
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("guaili-widget-immediate-refresh")
            if (originalLegacySnapshot != null) legacyStore.save(originalLegacySnapshot)
            GuailiWidget().updateAll(context)
        }
    }

    @Test fun c_realWidgetShowsLiveV2AndOpensServerTimeDetails() = runBlocking<Unit> {
        val settingsStore = SettingsStore(context)
        val originalSettings = settingsStore.settings.first()
        val configs = WidgetConfigStore(context)
        val snapshots = ServerSignalsSnapshotStore(context)
        val originalSnapshot = snapshots.read()
        val originalFailure = snapshots.readFailure()
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, GuailiWidgetReceiver::class.java))
        assertTrue("An existing launcher widget is required; this test does not create or delete one", ids.isNotEmpty())
        val id = ids.sorted().firstOrNull { configs.read(it, originalSettings).mode != WidgetMode.DecisionReminders }
            ?: ids.min()
        val originalConfig = configs.read(id, originalSettings)
        val localSettings = originalSettings.copy(baseUrl = localBaseUrl)
        val arguments = InstrumentationRegistry.getArguments()
        val expectedDisabled = arguments.getString("expect_disabled") == "true"
        val keepLocalV2 = arguments.getString("keep_local_v2") == "true"
        var completed = false
        try {
            settingsStore.save(localSettings)
            configs.save(id, originalConfig.copy(
                mode = WidgetMode.SignalsV2,
                symbols = originalSettings.symbols.take(10),
                enabledSignalKinds = GuailiSignalKind.entries.toSet(),
            ))
            GuailiWidget().updateAll(context)
            device.pressHome()
            val titleSelector = By.text(java.util.regex.Pattern.compile("^(乖离信号 v2|信号v2).*"))
            visibleTitle(titleSelector)

            // Find the launcher page before the final fetch, so the screenshot uses a fresh result.
            val currentTargets = widgetRefreshTargets(context, localSettings)
            val result = ServerSignalsRefreshUseCase(snapshotSink = snapshots).refresh(localBaseUrl,
                symbols = serverSignalQuerySymbols(currentTargets.map { it.config }))
            assertTrue("Live response must be saved for the real widget: $result", result is GuailiResult.Success)
            val fetchedElapsed = SystemClock.elapsedRealtime()
            val fetched = (result as GuailiResult.Success).value
            if (expectedDisabled) {
                assertFalse("The local backend should have its signal calculation switch disabled", fetched.response.enabled)
                assertEquals("disabled", fetched.response.status)
            }
            assertTrue(fetched.belongsTo(localBaseUrl))
            assertTrue(fetched.response.serverTime > 0L)
            assertNotNull(fetched.serverClock)
            assertEquals(fetched.response.snapshotVersion, snapshots.read()!!.response.snapshotVersion)
            assertEquals(WidgetMode.SignalsV2, configs.read(id, localSettings).mode)
            val glanceManager = GlanceAppWidgetManager(context)
            val glanceId = glanceManager.getGlanceIds(GuailiWidget::class.java).first { glanceManager.getAppWidgetId(it) == id }
            setWidgetRefreshStatus(context, glanceId, WidgetRefreshPhase.Idle)
            GuailiWidget().updateAll(context)
            visibleTitle(titleSelector)
            val expectedFetchTime = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(fetched.response.serverTime))
            val renderDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (device.findObjects(By.text(expectedFetchTime)).none { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 } &&
                SystemClock.elapsedRealtime() < renderDeadline) {
                Thread.sleep(100)
            }
            assertTrue("Widget must show the timestamp of the actual newly fetched response",
                device.findObjects(By.text(expectedFetchTime)).any { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 })
            if (expectedDisabled) assertTrue(device.hasObject(By.text("服务器信号计算已关闭")))
            visibleTitle(titleSelector)
            val desktopShot = File(requireNotNull(context.getExternalFilesDir(null)), "server-signals-v2-live.png")
            assertTrue("Launcher screenshot must be captured", device.takeScreenshot(desktopShot))
            assertTrue("Screenshot must follow the actual refresh within 15 seconds",
                SystemClock.elapsedRealtime() - fetchedElapsed < 15_000L)

            val detailSelector = By.text(java.util.regex.Pattern.compile("校时✓|✓|部分过期|行情过期|行情旧|采样超时|采样旧|快照过期|缓存旧|已关闭|连接恢复中|恢复中|部分预热|历史预热|指标预热|预热|历史断档|断档|动态K缺失|缺K|数据异常|异常|等待行情|待行情|等待数据|待数据|数据待确认|品种未计算|缺品种|时间异常|待校时|详情"))
            val titleBounds = visibleTitle(titleSelector).visibleBounds
            val detail = device.findObjects(detailSelector).firstOrNull { candidate ->
                val bounds = candidate.visibleBounds
                bounds.width() > 0 && bounds.height() > 0 && kotlin.math.abs(bounds.centerY() - titleBounds.centerY()) < 60
            }
            assertNotNull("The V2 status badge must open details", detail)
            val bounds = detail!!.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            assertTrue(device.wait(Until.hasObject(By.text("信号 v2 状态")), 8_000L))
            assertNotNull(device.findObject(By.text("来源：$localBaseUrl")))
            val sampled = device.findObject(By.textStartsWith("服务器采样时间："))
            val received = device.findObject(By.textStartsWith("手机获取时间："))
            assertNotNull("Server sample time must be exposed", sampled)
            assertNotNull("Phone fetch time must be exposed", received)
            if (!expectedDisabled) assertFalse("Server sample time must not be unknown", sampled!!.text.endsWith("未知"))
            assertFalse("Phone fetch time must not be unknown", received!!.text.endsWith("未知"))
            assertTrue(device.hasObject(By.text("刷新服务器信号")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "server-signals-v2-status-live.png"))
            device.pressBack()
            completed = true
        } finally {
            if (!keepLocalV2 || !completed) {
                configs.save(id, originalConfig)
                settingsStore.save(originalSettings)
            // Preserve a genuine refresh from another source that may have completed meanwhile.
                if (snapshots.read()?.belongsTo(localBaseUrl) == true) {
                    if (originalSnapshot != null) snapshots.save(originalSnapshot)
                    snapshots.recordFailure(originalFailure)
                }
            }
            GuailiWidget().updateAll(context)
        }
    }

    @Test fun d_allConfiguredSourcesPublishCurrentDynamicEvidenceWithoutTradeOnlyGap() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val expected = arguments.getString("expected_symbols")?.split(',')?.filter { it.isNotBlank() }
            ?: configuredSymbols
        val repository = ServerSignalsRepository.create(localBaseUrl)
        val seenLive = mutableSetOf<String>()
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        var lastResponse: ServerSignalsResponse? = null
        do {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val result = withTimeoutOrNull(remaining) { repository.fetch(expected) } ?: break
            assertTrue("Configured-symbol query must succeed: $result", result is GuailiResult.Success)
            val response = (result as GuailiResult.Success).value
            lastResponse = response
            assertTrue("This acceptance case requires calculation enabled", response.enabled)
            assertEquals("No configured source may silently disappear", expected.toSet(), response.results.map { it.symbol }.toSet())
            val eligibleRows = eligibleDynamicRows(response)
            response.results.forEach { symbol ->
                assertTrue("${symbol.symbol} must publish a sampled result", symbol.sampledAt > 0L)
                assertTrue("${symbol.symbol} must have received live source events", (symbol.marketSequence ?: 0L) > 0L)
                assertTrue("${symbol.symbol} must retain the actual market event time", (symbol.lastMarketEventTime ?: 0L) > 0L)
                if (symbol.symbol in eligibleRows) {
                    assertFalse("${symbol.symbol} is still blocked by trade-only snapshot ingestion",
                        symbol.perIntervalQuality.any { it.reason?.contains("waiting for a live") == true })
                }
                if (symbol.symbol in eligibleRows &&
                    symbol.perIntervalQuality.any { it.availability == "ready" && it.isClosed == false }) {
                    seenLive += symbol.symbol
                }
            }
            // Low-volume sources can legitimately become stale between events. Compare each
            // complete response on its actual ready periods; no freshness threshold is relaxed.
            assertSameEvidenceMatchesOriginalDetector(response, expected)
            if (seenLive.containsAll(expected)) break
            val pause = (deadline - SystemClock.elapsedRealtime()).coerceIn(0L, 1_000L)
            if (pause > 0L) Thread.sleep(pause)
        } while (SystemClock.elapsedRealtime() < deadline)
        val missing = expected.filterNot(seenLive::contains)
        val diagnostics = lastResponse?.results?.filter { it.symbol in missing }?.associate { row ->
            row.symbol to row.perIntervalQuality.groupBy { "${it.availability}: ${it.reason}" }
                .mapValues { (_, periods) -> periods.map { it.interval } }
        }
        assertTrue("No current dynamic evidence observed within 90 seconds for $missing; last quality=$diagnostics",
            missing.isEmpty())
    }

    /** Compare both production detectors using the same current dynamic evidence. */
    private fun assertSameEvidenceMatchesOriginalDetector(response: ServerSignalsResponse, symbols: List<String>) {
        val now = response.serverTime
        val snapshot = ServerSignalsSnapshot(response, now, localBaseUrl,
            GuailiServerClock(now, 1_000L, 1, 0L))
        val allKinds = GuailiSignalKind.entries.toSet()
        val currentState = serverSignalsWidgetState(snapshot, null,
            WidgetConfig(symbols, emptyList(), WidgetMode.SignalsV2, enabledSignalKinds = allKinds), localBaseUrl,
            GuailiDeviceTime(now, 1_000L, 1))
        val evaluated = response.evaluatedAt
        val expired = evaluated != null &&
            (evaluated > now + 2_000L || now - evaluated > serverSignalsCacheLifetime(response))
        if (response.status !in setOf("ready", "degraded") || evaluated == null || expired ||
            currentState.status !in setOf("ready", "degraded")) {
            val expectedStatus = when {
                response.status == "config_error" -> "config_error"
                !response.enabled || response.status == "disabled" -> "disabled"
                expired -> "stale"
                response.candleMode != "live" || response.status !in setOf("ready", "degraded", "warming_up") -> "unknown"
                else -> "warming_up"
            }
            assertEquals("Recovery/expired observations must retain their actual display status", expectedStatus, currentState.status)
            assertTrue("Recovery/expired observations must not display active signals: ${currentState.status}",
                currentState.signals.isEmpty())
            return
        }
        val eligibleRows = eligibleDynamicRows(response)
        val cells = response.results.associate { row ->
            row.symbol to row.perIntervalQuality.associate { evidence ->
                val ready = row.symbol in eligibleRows && evidence.availability == "ready" && evidence.isClosed == false
                if (ready) {
                    assertNotNull("${row.symbol}/${evidence.interval}: ready evidence must have current openTime", evidence.openTime)
                    assertTrue("${row.symbol}/${evidence.interval}: current openTime must not be in the future",
                        evidence.openTime!! > 0L && evidence.openTime <= now)
                }
                evidence.interval to GuailiCell(
                    symbol = row.symbol,
                    interval = evidence.interval,
                    // Unavailable periods stay in table.intervals and remain a missing-data barrier.
                    value = evidence.value.takeIf { ready },
                    guaili = evidence.guaili,
                    ma = evidence.ma,
                    atr14 = evidence.atr14,
                    atrRank = evidence.atrRank,
                    rankFilter = ready,
                    longTrend = evidence.longTrend,
                    shortTrend = evidence.shortTrend,
                    isClosed = evidence.isClosed,
                    openTime = evidence.openTime?.let { Instant.ofEpochMilli(it).toString() },
                    closeTime = evidence.closeTime?.let { Instant.ofEpochMilli(it).toString() },
                    signalLongTrend = evidence.longTrend,
                    signalShortTrend = evidence.shortTrend,
                    signalAtrReady = ready,
                )
            }
        }
        val intervals = response.results.flatMap { row -> row.perIntervalQuality.map { it.interval } }.distinct()
        val table = GuailiTable(symbols, intervals, cells)
        val candidates = GuailiSignalDetector.candidates(table, selectedSymbols = symbols, nowMillis = now)
        val enabledSets = listOf(allKinds, emptySet()) + GuailiSignalKind.entries.map { setOf(it) } +
            GuailiSignalKind.entries.map { allKinds - it }
        enabledSets.forEach { enabled ->
            val reference = GuailiSignalEvolution.visible(candidates, table, symbols, enabled, now, null)
            val config = WidgetConfig(symbols, emptyList(), WidgetMode.SignalsV2, enabledSignalKinds = enabled)
            val state = serverSignalsWidgetState(snapshot, null, config, localBaseUrl,
                GuailiDeviceTime(now, 1_000L, 1))
            assertTrue("A freshly queried snapshot must be eligible for V2 display: ${state.status}",
                state.status in setOf("ready", "degraded"))
            val actual = state.signals.map { requireNotNull(serverWidgetPresentation(it, response, now)) }
            assertEquals("Same sampled dynamic evidence must preserve original structure/selection/trend; enabled=$enabled",
                reference.map(::signalShape), actual.map(::signalShape))
        }
    }

    private fun eligibleDynamicRows(response: ServerSignalsResponse): Set<String> {
        val now = response.serverTime
        val evaluated = response.evaluatedAt ?: return emptySet()
        val lifetime = serverSignalsCacheLifetime(response)
        if (!response.enabled || response.candleMode != "live" || response.status !in setOf("ready", "degraded") ||
            evaluated > now + 2_000L || now - evaluated > lifetime) return emptySet()
        return response.results.filter { row ->
            row.dataStatus in setOf("ready", "degraded") && row.sampledAt > 0L &&
                row.sampledAt <= now + 2_000L && now - row.sampledAt <= lifetime
        }.mapTo(mutableSetOf()) { it.symbol }
    }

    private fun signalShape(signal: GuailiSignal): String =
        "${signal.symbol}:${signal.kind}:" + signal.runs.joinToString(">") {
            "${it.direction}[${it.intervals.joinToString(",")}]"
        } + ":anchor=${signal.anchorInterval}:count=${signal.totalLevelCount}:trend=${signal.trend}"

    @Test fun e_querySubsetAndUnknownSymbolFallbackPreserveSupportedSignals() = runBlocking<Unit> {
        val repository = ServerSignalsRepository.create(localBaseUrl)
        val selected = listOf("BTCUSDT", "SOXLUSDT")
        val subset = repository.fetch(selected)
        assertTrue("Subset query must succeed: $subset", subset is GuailiResult.Success)
        assertEquals(selected.toSet(), (subset as GuailiResult.Success).value.results.map { it.symbol }.toSet())
        val fallback = repository.fetch(selected + "V2NOTCONFIGUREDUSDT")
        assertTrue("An unsupported selection must not block supported widgets: $fallback", fallback is GuailiResult.Success)
        val fallbackSymbols = (fallback as GuailiResult.Success).value.results.map { it.symbol }
        assertTrue(fallbackSymbols.containsAll(selected))
        assertFalse(fallbackSymbols.contains("V2NOTCONFIGUREDUSDT"))
    }

    private fun configurationObject(selector: BySelector): UiObject2 {
        repeat(12) {
            device.findObject(selector)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 18)
            device.waitForIdle()
        }
        return requireNotNull(device.findObject(selector)) { "Configuration item is not reachable: $selector" }
    }

    private fun visibleTitle(selector: BySelector): UiObject2 {
        repeat(7) {
            device.findObjects(selector).firstOrNull { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }?.let { return it }
            device.swipe(device.displayWidth - 50, device.displayHeight * 3 / 4, 50,
                device.displayHeight * 3 / 4, 18)
            device.waitForIdle()
        }
        return requireNotNull(device.findObjects(selector).firstOrNull { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }) {
            "The V2 widget must be visibly on the current launcher page"
        }
    }
}
