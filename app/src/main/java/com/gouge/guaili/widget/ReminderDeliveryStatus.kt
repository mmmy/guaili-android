package com.gouge.guaili.widget

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build

data class ReminderDeliveryStatus(val notificationsEnabled: Boolean, val exactAlarms: Boolean) {
    val label: String get() = when {
        !notificationsEnabled -> "仅桌面显示 · 通知未开启"
        !exactAlarms -> "通知已开启 · 提醒可能延迟"
        else -> "通知已开启 · 精确定时"
    }
}

fun reminderDeliveryStatus(context: Context): ReminderDeliveryStatus {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channelEnabled = manager.getNotificationChannel("decision_reminders")?.importance != NotificationManager.IMPORTANCE_NONE
    return ReminderDeliveryStatus(
        notificationsEnabled = manager.areNotificationsEnabled() && channelEnabled,
        exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    )
}

internal fun shouldNotifyReminder(reminder: DecisionReminder, now: Long): Boolean =
    reminder.targetAtEpochMillis <= now && reminder.notifiedTargetAtEpochMillis != reminder.targetAtEpochMillis
