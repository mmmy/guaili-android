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
import androidx.work.workDataOf
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
        val showFeedback = inputData.getBoolean(ShowRefreshFeedbackKey, false)
        if (showFeedback) {
            setAllWidgetRefreshStatuses(applicationContext, WidgetRefreshPhase.Refreshing)
            GuailiWidget().updateAll(applicationContext)
        }
        val settings = SettingsStore(applicationContext).settings.first()
        val result = GuailiRefreshUseCase(
            snapshotSink = GuailiSnapshotStore(applicationContext),
        ).refresh(settings)
        if (showFeedback) {
            setAllWidgetRefreshStatuses(
                context = applicationContext,
                phase =
                when (result) {
                    is GuailiResult.Success -> WidgetRefreshPhase.Success
                    is GuailiResult.Failure -> WidgetRefreshPhase.Failure
                },
            )
        }
        GuailiWidget().updateAll(applicationContext)
        return when (result) {
            is GuailiResult.Success -> Result.success()
            is GuailiResult.Failure -> if (showFeedback) Result.failure() else Result.retry()
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

    fun refreshNow(context: Context, showFeedback: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<GuailiWidgetWorker>()
            .setInputData(workDataOf(ShowRefreshFeedbackKey to showFeedback))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (!showFeedback) builder.setConstraints(networkConstraints)
        val work = builder.build()
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

internal const val ShowRefreshFeedbackKey = "show_refresh_feedback"
