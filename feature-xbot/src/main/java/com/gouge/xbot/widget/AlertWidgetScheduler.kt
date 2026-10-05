package com.gouge.xbot.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gouge.xbot.data.AlertVisibilityStore
import java.time.Instant
import java.util.concurrent.TimeUnit

object AlertWidgetScheduler {
    private const val Immediate = "alert-widget-refresh"
    private const val Periodic = "alert-widget-periodic"
    fun enqueueImmediate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(Immediate, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AlertWidgetWorker>().build())
    }
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(Periodic, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertWidgetWorker>(15, TimeUnit.MINUTES).build())
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(Immediate)
        WorkManager.getInstance(context).cancelUniqueWork(Periodic)
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }
    fun scheduleLocalRepaint(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (AlertWidgetRenderer.widgetIds(context).isEmpty()) { alarm.cancel(tickIntent(context)); return }
        val snapshot = AlertWidgetStore(context).snapshot() ?: run { alarm.cancel(tickIntent(context)); return }
        val now = Instant.now()
        val next = alertWidgetEntries(snapshot, AlertVisibilityStore(context).getVisibleIds())
            .mapNotNull { it.expiresAt?.let { expiry -> nextAlertWidgetChange(expiry, now) } }.minOrNull()
        if (next == null) alarm.cancel(tickIntent(context))
        else alarm.set(AlarmManager.RTC, next.toEpochMilli(), tickIntent(context))
    }
    private fun tickIntent(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlertWidgetProvider::class.java).setAction(AlertWidgetProvider.Tick),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
