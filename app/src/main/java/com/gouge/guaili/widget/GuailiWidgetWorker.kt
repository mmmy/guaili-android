package com.gouge.guaili.widget

import android.content.Context
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
import com.gouge.guaili.data.ServerSignalsRefreshUseCase
import com.gouge.guaili.data.ServerSignalsSnapshotStore
import com.gouge.guaili.settings.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class GuailiWidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val showFeedback = inputData.getBoolean(ShowRefreshFeedbackKey, false)
        val settings = SettingsStore(applicationContext).settings.first()
        val targets = widgetRefreshTargets(applicationContext, settings)
            .filter { it.config.mode != WidgetMode.DecisionReminders }
        if (targets.isEmpty()) return Result.success()
        val widget = GuailiWidget()
        if (showFeedback) {
            targets.forEach {
                setWidgetRefreshStatus(applicationContext, it.id, WidgetRefreshPhase.Refreshing)
                widget.update(applicationContext, it.id)
            }
        }
        val needsLegacy = targets.any { it.config.mode !in setOf(WidgetMode.SignalsV2, WidgetMode.DecisionReminders) }
        val needsV2 = targets.any { it.config.mode == WidgetMode.SignalsV2 }
        val failures = coroutineScope {
            val legacy = async { if (needsLegacy) GuailiRefreshUseCase(snapshotSink = GuailiSnapshotStore(applicationContext))
                .refresh(settings, requirePersistence = true) else null }
            val v2 = async { if (needsV2) ServerSignalsRefreshUseCase(snapshotSink = ServerSignalsSnapshotStore(applicationContext))
                .refresh(settings.baseUrl, reuseWithinMillis = 1_000L) else null }
            val legacyResult = legacy.await()
            val v2Result = v2.await()
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(applicationContext)
            val configurations = WidgetConfigStore(applicationContext)
            targets.forEach { target ->
                val result = if (target.config.mode == WidgetMode.SignalsV2) v2Result else legacyResult
                val currentMode = configurations.read(manager.getAppWidgetId(target.id), settings).mode
                if (result != null && currentMode == target.config.mode) setWidgetRefreshStatus(applicationContext, target.id,
                    if (result is GuailiResult.Success) WidgetRefreshPhase.Success else WidgetRefreshPhase.Failure,
                    message = (result as? GuailiResult.Failure)?.message, deduplicate = !showFeedback)
            }
            if (v2Result is GuailiResult.Success) scheduleServerSignalsExpiry(applicationContext, v2Result.value)
            listOfNotNull(legacyResult, v2Result).filterIsInstance<GuailiResult.Failure>()
        }
        targets.forEach { widget.update(applicationContext, it.id) }
        return if (failures.isEmpty()) Result.success() else if (showFeedback) Result.failure() else Result.retry()
    }
}

object GuailiWidgetScheduler {
    private const val PeriodicWorkName = "guaili-widget-periodic-refresh"
    private const val ImmediateWorkName = "guaili-widget-immediate-refresh"
    private const val FeedbackExpiryWorkName = "guaili-widget-feedback-expiry"

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
        if (showFeedback) scheduleFeedbackExpiry(context, 65)
    }

    fun scheduleFeedbackExpiry(context: Context, seconds: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(FeedbackExpiryWorkName, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetFeedbackExpiryWorker>()
                .setInitialDelay(seconds, TimeUnit.SECONDS).build(),
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicWorkName)
    }
}

internal const val ShowRefreshFeedbackKey = "show_refresh_feedback"

class WidgetFeedbackExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val onlyV2 = inputData.getBoolean(OnlyV2WidgetsKey, false)
        if (onlyV2) clearServerSignalsExpirySchedule()
        val settings = SettingsStore(applicationContext).settings.first()
        val widget = GuailiWidget()
        widgetRefreshTargets(applicationContext, settings)
            .filter { if (onlyV2) it.config.mode == WidgetMode.SignalsV2 else it.config.mode != WidgetMode.DecisionReminders }
            .forEach { widget.update(applicationContext, it.id) }
        return Result.success()
    }
}
