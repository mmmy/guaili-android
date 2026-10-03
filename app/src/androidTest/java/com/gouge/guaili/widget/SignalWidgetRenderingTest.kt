package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.GuailiServerClock
import com.gouge.guaili.data.readGuailiDeviceTime
import com.gouge.guaili.domain.*
import com.gouge.guaili.settings.SettingsStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the test emulator after scripts/backup-widget-device.py. */
@RunWith(AndroidJUnit4::class)
class SignalWidgetRenderingTest {
    @Test fun allThreeStructuresAndTheirEvidenceAreReachableOnDesktop() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settings = SettingsStore(context).settings.first()
        val configs = WidgetConfigStore(context)
        val snapshots = GuailiSnapshotStore(context)
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GuailiWidgetReceiver::class.java))
        assertTrue("The emulator must already have a widget", ids.isNotEmpty())
        assertTrue(settings.symbols.size >= 3)
        val originalSnapshot = requireNotNull(snapshots.read())
        val id = ids.min()
        val originalConfig = configs.read(id, settings)
        val deviceTime = readGuailiDeviceTime(context)
        // Reproduce the reported 86-second device lag without changing system time.
        val time = deviceTime.wallMillis + 86_000L
        val intervals = listOf("1", "2", "3", "5", "8", "10", "15", "20", "30", "45", "60")
        val symbols = settings.symbols.take(3)
        val cells = symbols.mapIndexed { symbolIndex, symbol ->
            symbol to intervals.mapIndexed { index, interval ->
                val value = when (symbolIndex) {
                    0 -> if (index < 5) -12 else if (index > 5) 12 else 5
                    1 -> 0
                    else -> if (index < 5) 12 else 5
                }
                interval to GuailiCell(symbol, interval, value, value / 10.0, 100.0, 1.0, 50.0,
                    true, true, false, false,
                    Instant.ofEpochMilli(time / guailiIntervalDurationMillis(interval) * guailiIntervalDurationMillis(interval)).toString(),
                    Instant.ofEpochMilli((time / guailiIntervalDurationMillis(interval) + 1) * guailiIntervalDurationMillis(interval) - 1).toString(),
                    signalLongTrend = true, signalShortTrend = false, signalAtrReady = true)
            }.toMap()
        }.toMap()
        val table = GuailiTable(symbols, intervals, cells, cells)
        val fixture = GuailiSnapshot(table, deviceTime.wallMillis, "UTC", "EMA", 20,
            signalProfile = "render-test", signalRuleVersion = GuailiSignalEvolution.RuleVersion,
            signals = GuailiSignalEvolution.evaluate(table, time, "UTC"),
            serverClock = GuailiServerClock(time, deviceTime.elapsedMillis, deviceTime.bootCount, 100))
        try {
            snapshots.save(fixture)
            configs.save(id, originalConfig.copy(mode = WidgetMode.Signals, symbols = symbols,
                enabledSignalKinds = GuailiSignalKind.entries.toSet()))
            GuailiWidget().updateAll(context)
            device.pressHome()
            repeat(4) {
                if (!device.wait(Until.hasObject(By.textStartsWith("乖离信号")), 2_000)) {
                    device.swipe(device.displayWidth - 50, device.displayHeight * 3 / 4,
                        50, device.displayHeight * 3 / 4, 20)
                }
            }
            assertTrue(device.wait(Until.hasObject(By.text("长短周期分歧")), 12_000))
            val statusBadge = device.findObject(By.text("校时✓"))
                ?: device.findObject(By.textContains("品种缺失"))
            assertNotNull("Compact status must expose its details", statusBadge)
            statusBadge!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("小组件状态")), 5_000))
            assertTrue(device.wait(Until.hasObject(By.textContains("已按服务器时间判断")), 5_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("长短周期分歧")), 5_000))
            assertFalse(device.hasObject(By.textContains("行情过期")))
            val title = device.findObject(By.text("长短周期分歧"))
            var container = title.parent
            while (container != null && container.findObject(By.clazz("android.widget.ListView")) == null) {
                container = container.parent
            }
            val list = requireNotNull(container?.findObject(By.clazz("android.widget.ListView")))
            assertNotNull(list.findObject(By.textContains("短负")))
            assertNotNull(list.findObject(By.textContains("回调观察")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "widget-signals-top.png"))
            fun fullyVisible(label: String): Boolean {
                val title = list.findObject(By.text(label)) ?: return false
                val minimumTitle = (11 * context.resources.displayMetrics.scaledDensity).toInt()
                if (title.visibleBounds.height() < minimumTitle) return false
                var item = title.parent
                while (item != null && item.findObject(By.text("EMA20 ↑")) == null) item = item.parent
                val trend = item?.findObject(By.text("EMA20 ↑")) ?: return false
                return trend.visibleBounds.height() >= (9 * context.resources.displayMetrics.scaledDensity).toInt()
            }
            for (label in listOf("上方乖离共振", "多周期近均线")) {
                repeat(5) {
                    if (!fullyVisible(label)) {
                        val bounds = list.visibleBounds
                        device.swipe(bounds.centerX(), bounds.bottom - 8, bounds.centerX(), bounds.top + 8, 20)
                        device.waitForIdle()
                    }
                }
                val row = requireNotNull(list.findObject(By.text(label)))
                assertTrue("Both signal rows must be fully visible", fullyVisible(label))
                assertTrue("Signal title must be visible within its list", row.visibleBounds.height() > 0 &&
                    list.visibleBounds.contains(row.visibleBounds))
            }
            assertNotNull(list.findObject(By.text("EMA20 ↑")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "widget-signals-bottom.png"))
        } finally {
            configs.save(id, originalConfig)
            // Do not overwrite a genuine refresh that completed during rendering.
            if (snapshots.read()?.signalProfile == "render-test") snapshots.save(originalSnapshot)
            GuailiWidget().updateAll(context)
        }
    }
}
