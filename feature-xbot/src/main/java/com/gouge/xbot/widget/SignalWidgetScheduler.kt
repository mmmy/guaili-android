package com.gouge.xbot.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object SignalWidgetScheduler {
    private const val ImmediateWorkName = "signal-widget-refresh"
    private const val PeriodicWorkName = "signal-widget-periodic-refresh"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueImmediate(context: Context, appWidgetId: Int? = null) {
        val request = OneTimeWorkRequestBuilder<SignalWidgetWorker>()
            .apply {
                if (appWidgetId != null) {
                    setInputData(workDataOf(SignalWidgetWorker.InputAppWidgetId to appWidgetId))
                }
            }
            .build()
        val workName = appWidgetId?.let { "$ImmediateWorkName-$it" } ?: ImmediateWorkName
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SignalWidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicWorkName)
    }
}
