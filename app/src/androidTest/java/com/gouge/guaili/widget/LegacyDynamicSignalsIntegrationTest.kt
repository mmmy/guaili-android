package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.domain.signalCells
import com.gouge.guaili.settings.SettingsStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real backend verification. Refreshes data without changing settings or widget modes. */
@RunWith(AndroidJUnit4::class)
class LegacyDynamicSignalsIntegrationTest {
    @Test fun originalWidgetUsesCurrentDynamicCandlesAndShowsTheirSignals() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settingsStore = SettingsStore(context)
        val settings = settingsStore.settings.first()
        val configurations = WidgetConfigStore(context)
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, GuailiWidgetReceiver::class.java))
        val before = ids.associateWith { configurations.read(it, settings) }
        val id = requireNotNull(before.entries.firstOrNull { it.value.mode == WidgetMode.Signals }?.key) {
            "The emulator needs an existing original signal-mode widget"
        }
        val config = before.getValue(id)
        val store = GuailiSnapshotStore(context)
        val result = GuailiRefreshUseCase(snapshotSink = store).refresh(settings, requirePersistence = true)
        assertTrue("The configured real backend must return current K: $result", result is GuailiResult.Success)
        val snapshot = (result as GuailiResult.Success).value
        assertEquals("dynamic-structure-v1", snapshot.signalRuleVersion)
        assertEquals(snapshot, store.read())
        val time = assessGuailiTime(snapshot, readGuailiDeviceTime(context))
        assertTrue("Server clock must be available: $time", time.available)
        val now = requireNotNull(time.nowMillis)
        val evidence = config.symbols.flatMap { signalCells(snapshot.table, it).values }
        val usable = evidence.filter { dynamicSignalCellAvailability(it, now, snapshot.timezone) == CellAvailability.Ready }
        assertTrue("The real response must contain current dynamic evidence", usable.isNotEmpty())
        assertTrue(usable.all { it.isClosed == false })
        assertTrue(usable.all { parseGuailiTime(it.openTime, snapshot.timezone)!! <= now + 2_000L &&
            parseGuailiTime(it.closeTime, snapshot.timezone)!! >= now })
        val signals = widgetSignals(snapshot, config, now)
        assertTrue("This integration sample must expose at least one visible signal", signals.isNotEmpty())
        signals.forEach { signal ->
            signal.runs.flatMap { it.intervals }.forEach { interval ->
                val current = requireNotNull(signalCells(snapshot.table, signal.symbol)[interval])
                assertEquals(false, current.isClosed)
                assertEquals(CellAvailability.Ready,
                    dynamicSignalCellAvailability(current, now, snapshot.timezone))
            }
        }
        val report = buildString {
            appendLine("ruleVersion=${GuailiSignalEvolution.RuleVersion}")
            appendLine("source=${settings.baseUrl}")
            appendLine("serverTime=$now dynamicReady=${usable.size} visibleSignals=${signals.size}")
            usable.forEach { live ->
                val closed = snapshot.table.closedCells[live.symbol]?.get(live.interval)
                appendLine("${live.symbol}/${live.interval}: dynamic=${live.value} closed=${closed?.value} " +
                    "isClosed=${live.isClosed} open=${live.openTime} end=${live.closeTime}")
            }
            signals.forEach { appendLine("${it.symbol}: ${it.kind} ${it.runs.map { run -> run.intervals }}") }
        }
        File(context.getExternalFilesDir(null), "legacy-dynamic-signal-verification.txt").writeText(report)
        File(context.getExternalFilesDir(null), "legacy-dynamic-signal-snapshot.json").writeText(
            Json.encodeToString(GuailiSnapshot.serializer(), snapshot))
        val manager = GlanceAppWidgetManager(context)
        val glanceId = manager.getGlanceIds(GuailiWidget::class.java).first { manager.getAppWidgetId(it) == id }
        setWidgetRefreshStatus(context, glanceId, WidgetRefreshPhase.Idle)
        GuailiWidget().updateAll(context)
        device.pressHome()
        val titleSelector = By.text(Pattern.compile("(?:乖离)?信号\\s*·\\s*\\d+条"))
        for (attempt in 0..4) {
            if (device.wait(Until.hasObject(titleSelector), 2_000L)) break
            device.swipe(device.displayWidth - 50, device.displayHeight / 2,
                50, device.displayHeight / 2, 20)
        }
        assertTrue("The original signal widget must be visible", device.wait(Until.hasObject(titleSelector), 5_000L))
        val formats = listOf("HH:mm:ss", "HH:mm").map { pattern ->
            DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snapshot.serverClock!!.serverTimeMillis))
        }
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        var shownTime: android.graphics.Rect? = null
        while (SystemClock.elapsedRealtime() < deadline && shownTime == null) {
            val header = device.findObject(titleSelector)?.visibleBounds
            if (header != null) {
                shownTime = formats.asSequence().flatMap { device.findObjects(By.text(it)).asSequence() }
                    .map { it.visibleBounds }.firstOrNull {
                        it.width() > 0 && it.height() > 0 && kotlin.math.abs(it.centerY() - header.centerY()) < 60
                    }
            }
            if (shownTime == null) SystemClock.sleep(250L)
        }
        assertNotNull("The widget must show this newly fetched response's time", shownTime)
        assertTrue("The current signal count must be rendered", device.hasObject(
            By.text(Pattern.compile("(?:乖离)?信号\\s*·\\s*${signals.size}条"))))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "legacy-dynamic-signals-live.png"))
        val bounds = requireNotNull(shownTime)
        device.click(bounds.centerX(), bounds.centerY())
        assertTrue(device.wait(Until.hasObject(By.text("信号计算使用当前实时动态 K")), 8_000L))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "legacy-dynamic-signals-status.png"))
        device.pressBack()
        assertEquals(settings, settingsStore.settings.first())
        before.forEach { (widgetId, original) -> assertEquals(original, configurations.read(widgetId, settings)) }
    }
}
