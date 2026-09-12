package com.gouge.guaili.widget

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gouge.guaili.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.glance.appwidget.updateAll
import java.io.File

/** Configuration tests use a private ID; rendering restores the original widget in finally. */
@RunWith(AndroidJUnit4::class)
class WidgetBusinessFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val store = WidgetConfigStore(context)
    private val testId = 900013
    private fun intent() = Intent(context, GuailiWidgetConfigurationActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testId)
    private fun read() = runBlocking { store.read(testId, SettingsStore(context).settings.first()) }
    private fun seed(reminder: DecisionReminder) = runBlocking {
        store.save(testId, WidgetConfig(listOf(reminder.symbol), listOf(reminder.interval), WidgetMode.DecisionReminders, reminders = listOf(reminder)))
    }
    private fun cleanup() = runBlocking {
        read().reminders.forEach { DecisionReminderScheduler.cancel(context, testId, it.id) }
        store.delete(testId)
    }

    @Test fun savingOpenEditorDoesNotResurrectReminderCompletedFromNotification() {
        verifyExternalReminderActionSurvivesEditorSave("COMPLETE")
    }

    @Test fun savingRecreatedEditorKeepsNotificationSnooze() {
        verifyExternalReminderActionSurvivesEditorSave("SNOOZE")
    }

    private fun verifyExternalReminderActionSurvivesEditorSave(action: String) {
        val reminder = DecisionReminder("concurrent-editor", "BTCUSDT", "15", DecisionDirection.Long, System.currentTimeMillis() + 60_000)
        seed(reminder)
        try {
            ActivityScenario.launch<GuailiWidgetConfigurationActivity>(intent()).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("提醒（1/12）")), 10_000))
                runBlocking {
                    DecisionReminderScheduler.handleIntent(context, Intent("com.gouge.guaili.DECISION_REMINDER_$action")
                        .putExtra("decision_reminder_widget_id", testId)
                        .putExtra("decision_reminder_id", reminder.id))
                }
                val afterNotification = read().reminders
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("保存小组件")), 5_000))
                device.findObject(By.text("保存小组件")).click()
                assertTrue(device.wait(Until.gone(By.text("配置乖离小组件")), 10_000))
                assertEquals(afterNotification, read().reminders)
                if (action == "COMPLETE") assertTrue(read().reminders.isEmpty())
                else assertTrue(read().reminders.single().targetAtEpochMillis > reminder.targetAtEpochMillis)
            }
        } finally { cleanup() }
    }

    @Test fun deleteLastReminderSurvivesRecreationAndSavesEmptyList() {
        val reminder = DecisionReminder("ui-delete", "BTCUSDT", "15", DecisionDirection.Long, System.currentTimeMillis() + 900_000)
        seed(reminder)
        try {
            ActivityScenario.launch<GuailiWidgetConfigurationActivity>(intent()).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("删除")), 10_000))
                device.findObject(By.text("删除")).click()
                assertTrue(device.wait(Until.hasObject(By.text("提醒（0/12）")), 5_000))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("继续编辑")), 5_000))
                device.findObject(By.text("继续编辑")).click()
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("提醒（0/12）")), 5_000))
                assertTrue(device.hasObject(By.text("有未保存修改")))
                device.findObject(By.text("保存小组件")).click()
                device.waitForIdle()
                val deadline = System.currentTimeMillis() + 10_000
                while (read().reminders.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertTrue(read().reminders.isEmpty())
            }
        } finally { cleanup() }
    }

    @Test fun missedReminderIsPersistentlyDeduplicatedAndSnoozeIsExactlyFifteenMinutes() = runBlocking {
        val reminder = DecisionReminder("delivery-test", "TEST-WIDGET", "15", DecisionDirection.Long, System.currentTimeMillis() - 120_000)
        seed(reminder)
        fun action(name: String) = Intent("com.gouge.guaili.DECISION_REMINDER_$name")
            .putExtra("decision_reminder_widget_id", testId)
            .putExtra("decision_reminder_id", reminder.id)
        try {
            assertTrue("Enable notifications for this emulator test", reminderDeliveryStatus(context).notificationsEnabled)
            DecisionReminderScheduler.handleIntent(context, action("FIRE"))
            assertEquals(reminder.targetAtEpochMillis, read().reminders.single().notifiedTargetAtEpochMillis)
            val manager = context.getSystemService(NotificationManager::class.java)
            val notificationId = "$testId:${reminder.id}".hashCode()
            assertTrue(manager.activeNotifications.any { it.id == notificationId })
            manager.cancel(notificationId)
            DecisionReminderScheduler.handleIntent(context, action("FIRE"))
            assertFalse(manager.activeNotifications.any { it.id == notificationId })
            val before = System.currentTimeMillis()
            DecisionReminderScheduler.handleIntent(context, action("SNOOZE"))
            val after = System.currentTimeMillis()
            val snoozed = read().reminders.single()
            assertTrue(snoozed.targetAtEpochMillis in (before + 900_000)..(after + 900_000))
            assertNull(snoozed.notifiedTargetAtEpochMillis)
            DecisionReminderScheduler.handleIntent(context, action("COMPLETE"))
            assertTrue(read().reminders.isEmpty())
        } finally { cleanup() }
    }

    @Test fun desktopMatrixCanScrollToFifthConfiguredSymbol() = runBlocking<Unit> {
        val settings = SettingsStore(context).settings.first()
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GuailiWidgetReceiver::class.java))
        assertTrue("Add a Guaili widget before running the desktop test", ids.isNotEmpty())
        assertTrue(settings.symbols.size >= 5)
        val id = ids.min()
        val original = store.read(id, settings)
        try {
            store.save(id, original.copy(mode = WidgetMode.Matrix, symbols = settings.symbols.take(5), intervals = WidgetConfigStore.defaultIntervals(settings.intervals)))
            GuailiWidget().updateAll(context)
            device.pressHome()
            repeat(3) {
                if (!device.wait(Until.hasObject(By.text("乖离矩阵")), 2_000)) {
                    device.swipe(device.displayWidth - 50, device.displayHeight * 3 / 4, 50, device.displayHeight * 3 / 4, 20)
                }
            }
            assertTrue(device.wait(Until.hasObject(By.text("乖离矩阵")), 15_000))
            val title = device.findObject(By.text("乖离矩阵"))
            var container = title.parent
            while (container != null && container.findObject(By.clazz("android.widget.ListView")) == null) container = container.parent
            val list = container?.findObject(By.clazz("android.widget.ListView"))
            assertNotNull("Matrix must expose a scrollable list", list)
            val fifth = settings.symbols[4].removeSuffix("USDT")
            repeat(2) {
                val bounds = list!!.visibleBounds
                device.swipe(bounds.centerX(), bounds.bottom - 10, bounds.centerX(), bounds.top + 10, 20)
                device.waitForIdle()
            }
            val fifthRow = list!!.findObject(By.text(fifth))
            assertNotNull("Fifth symbol is reachable", fifthRow)
            val visible = fifthRow.visibleBounds
            assertTrue("Fifth symbol must be inside the visible list", visible.height() > 0 && list.visibleBounds.contains(visible))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "widget-matrix-verified.png"))
        } finally {
            store.save(id, original)
            GuailiWidget().updateAll(context)
        }
    }
}
