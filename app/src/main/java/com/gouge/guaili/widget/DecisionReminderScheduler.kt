package com.gouge.guaili.widget

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gouge.guaili.MainActivity
import com.gouge.guaili.R
import com.gouge.guaili.settings.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object DecisionReminderScheduler {
    private const val PeriodicWidgetUpdateWorkName = "decision-reminder-widget-update"
    private const val ActionFire = "com.gouge.guaili.DECISION_REMINDER_FIRE"
    private const val ActionComplete = "com.gouge.guaili.DECISION_REMINDER_COMPLETE"
    private const val ActionSnooze = "com.gouge.guaili.DECISION_REMINDER_SNOOZE"
    private const val ExtraWidgetId = "decision_reminder_widget_id"
    private const val ExtraReminderId = "decision_reminder_id"
    private const val NotificationChannelId = "decision_reminders"

    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                "决策提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "在手动设置的时间提醒你重新检查行情并做决策"
            },
        )
    }

    fun replace(
        context: Context,
        appWidgetId: Int,
        previous: List<DecisionReminder>,
        current: List<DecisionReminder>,
    ) {
        previous.forEach { cancel(context, appWidgetId, it.id) }
        current.forEach { schedule(context, appWidgetId, it) }
    }

    fun rescheduleAll(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            rescheduleAllNow(context)
        }
    }

    internal suspend fun rescheduleAllNow(context: Context) {
        val settings = SettingsStore(context).settings.first()
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, GuailiWidgetReceiver::class.java),
        )
        ids.forEach { appWidgetId ->
            val config = WidgetConfigStore(context).read(appWidgetId, settings)
            if (config.mode == WidgetMode.DecisionReminders) {
                config.reminders.forEach { schedule(context, appWidgetId, it) }
            }
        }
        GuailiWidget().updateAll(context)
    }

    fun schedulePeriodicWidgetUpdates(context: Context) {
        val work = PeriodicWorkRequestBuilder<DecisionReminderWidgetUpdateWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicWidgetUpdateWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    fun cancelPeriodicWidgetUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicWidgetUpdateWorkName)
    }

    @SuppressLint("MissingPermission", "ScheduleExactAlarm")
    internal fun schedule(context: Context, appWidgetId: Int, reminder: DecisionReminder) {
        if (reminder.targetAtEpochMillis <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = alarmPendingIntent(context, appWidgetId, reminder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.targetAtEpochMillis,
                operation,
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.targetAtEpochMillis,
                operation,
            )
        }
    }

    internal fun cancel(context: Context, appWidgetId: Int, reminderId: String) {
        val intent = baseIntent(context, ActionFire, appWidgetId, reminderId)
        val operation = PendingIntent.getBroadcast(
            context,
            requestCode(appWidgetId, reminderId, ActionFire),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    internal suspend fun handleIntent(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appWidgetId = intent.getIntExtra(ExtraWidgetId, AppWidgetManager.INVALID_APPWIDGET_ID)
        val reminderId = intent.getStringExtra(ExtraReminderId) ?: return
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (action) {
            ActionFire -> fireNotification(context, appWidgetId, reminderId)
            ActionComplete -> mutateReminder(context, appWidgetId, reminderId, snooze = false)
            ActionSnooze -> mutateReminder(context, appWidgetId, reminderId, snooze = true)
        }
    }

    private suspend fun fireNotification(
        context: Context,
        appWidgetId: Int,
        reminderId: String,
    ) {
        val settings = SettingsStore(context).settings.first()
        val config = WidgetConfigStore(context).read(appWidgetId, settings)
        if (config.mode != WidgetMode.DecisionReminders) return
        val reminder = config.reminders.firstOrNull { it.id == reminderId } ?: return
        if (reminder.targetAtEpochMillis > System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createNotificationChannel(context)
        val notification = Notification.Builder(context, NotificationChannelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                "${reminder.symbol} · " +
                    "${formatReminderIntervalForNotification(reminder.interval)} · " +
                    reminder.direction.label,
            )
            .setContentText("已到决策时间，请重新检查行情后再做决定。")
            .setContentIntent(
                klinePendingIntent(
                    context,
                    reminder.symbol,
                    reminder.interval,
                    appWidgetId,
                    reminderId,
                ),
            )
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "完成",
                    actionPendingIntent(context, ActionComplete, appWidgetId, reminderId),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "延后 15 分钟",
                    actionPendingIntent(context, ActionSnooze, appWidgetId, reminderId),
                ).build(),
            )
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(appWidgetId, reminderId), notification)
    }

    private suspend fun mutateReminder(
        context: Context,
        appWidgetId: Int,
        reminderId: String,
        snooze: Boolean,
    ) {
        val settings = SettingsStore(context).settings.first()
        val store = WidgetConfigStore(context)
        val config = store.read(appWidgetId, settings)
        val existing = config.reminders.firstOrNull { it.id == reminderId } ?: return
        val updated = if (snooze) {
            existing.copy(
                targetAtEpochMillis = alignedDecisionReminderTime(15L).toEpochMilli(),
            )
        } else {
            null
        }
        val reminders = config.reminders.filterNot { it.id == reminderId } + listOfNotNull(updated)
        store.save(appWidgetId, config.copy(reminders = reminders))
        cancel(context, appWidgetId, reminderId)
        updated?.let { schedule(context, appWidgetId, it) }
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(appWidgetId, reminderId))
    }

    private fun alarmPendingIntent(
        context: Context,
        appWidgetId: Int,
        reminder: DecisionReminder,
    ): PendingIntent {
        val intent = baseIntent(context, ActionFire, appWidgetId, reminder.id)
        return PendingIntent.getBroadcast(
            context,
            requestCode(appWidgetId, reminder.id, ActionFire),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        appWidgetId: Int,
        reminderId: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(appWidgetId, reminderId, action),
        baseIntent(context, action, appWidgetId, reminderId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun baseIntent(
        context: Context,
        action: String,
        appWidgetId: Int,
        reminderId: String,
    ): Intent = Intent(context, DecisionReminderReceiver::class.java).apply {
        this.action = action
        data = Uri.parse("guaili://decision-reminder/$appWidgetId/$reminderId/$action")
        putExtra(ExtraWidgetId, appWidgetId)
        putExtra(ExtraReminderId, reminderId)
    }

    private fun klinePendingIntent(
        context: Context,
        symbol: String,
        interval: String,
        appWidgetId: Int,
        reminderId: String,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("guaili://kline/$symbol/$interval")
            putExtra(MainActivity.ExtraWidgetSymbol, symbol)
            putExtra(MainActivity.ExtraWidgetInterval, interval)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode(appWidgetId, reminderId, "open"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(appWidgetId: Int, reminderId: String, action: String): Int =
        "$appWidgetId:$reminderId:$action".hashCode()

    private fun notificationId(appWidgetId: Int, reminderId: String): Int =
        "$appWidgetId:$reminderId".hashCode()

    private fun formatReminderIntervalForNotification(interval: String): String =
        if (interval.all(Char::isDigit)) "${interval}m" else interval
}

class DecisionReminderWidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        GuailiWidget().updateAll(applicationContext)
        return Result.success()
    }
}

class DecisionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DecisionReminderScheduler.handleIntent(appContext, intent)
                GuailiWidget().updateAll(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class DecisionReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RescheduleActions) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DecisionReminderScheduler.rescheduleAllNow(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RescheduleActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
