package com.gouge.guaili.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gouge.guaili.data.GuailiRefreshUseCase
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.settings.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class GuailiWidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).settings.first()
        val result = GuailiRefreshUseCase(
            snapshotSink = GuailiSnapshotStore(applicationContext),
        ).refresh(settings)
        GuailiWidget().updateAll(applicationContext)
        return when (result) {
            is GuailiResult.Success -> Result.success()
            is GuailiResult.Failure -> Result.retry()
        }
    }
}

object GuailiWidgetScheduler {
    private const val PeriodicWorkName = "guaili-widget-periodic-refresh"
    private const val ImmediateWorkName = "guaili-widget-immediate-refresh"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    internal val ImmediateWorkPolicy = ExistingWorkPolicy.REPLACE

    fun schedulePeriodic(context: Context) {
        val work = PeriodicWorkRequestBuilder<GuailiWidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    fun refreshNow(context: Context) {
        val work = OneTimeWorkRequestBuilder<GuailiWidgetWorker>()
            .setConstraints(networkConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ImmediateWorkName,
            ImmediateWorkPolicy,
            work,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicWorkName)
    }
}
