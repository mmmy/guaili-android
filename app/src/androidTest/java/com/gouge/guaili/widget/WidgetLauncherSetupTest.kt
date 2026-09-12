package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gouge.guaili.MainActivity
import com.gouge.guaili.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in setup, not included in verify-widget-device.ps1. */
@RunWith(AndroidJUnit4::class)
class WidgetLauncherSetupTest {
    @Test fun addEmptyReminderWidgetIfMissing() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, GuailiWidgetReceiver::class.java)
        val before = manager.getAppWidgetIds(component).toSet()
        if (before.size >= 2) return@runBlocking
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(manager.requestPinAppWidget(component, null, null)) }
            val add = device.wait(Until.findObject(By.textContains("Add to home screen")), 8_000)
                ?: device.wait(Until.findObject(By.text("Add")), 3_000)
            assertTrue("Launcher must present the pin confirmation", add != null)
            add!!.click()
            val deadline = System.currentTimeMillis() + 10_000
            while (manager.getAppWidgetIds(component).toSet() == before && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val added = manager.getAppWidgetIds(component).first { it !in before }
            val settings = SettingsStore(context).settings.first()
            WidgetConfigStore(context).save(added, WidgetConfig(settings.symbols.take(1), WidgetConfigStore.defaultIntervals(settings.intervals), WidgetMode.DecisionReminders))
            GuailiWidget().updateAll(context)
        }
    }
}
