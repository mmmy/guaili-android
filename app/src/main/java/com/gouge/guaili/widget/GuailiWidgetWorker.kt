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
        val availableTargets = widgetRefreshTargets(applicationContext, settings)
            .filter { it.config.mode != WidgetMode.DecisionReminders }
        val requestedId = inputData.getInt(RefreshWidgetIdKey, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        val targets = if (requestedId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) availableTargets
            else {
                val manager = androidx.glance.appwidget.GlanceAppWidgetManager(applicationContext)
                val requested = availableTargets.firstOrNull { manager.getAppWidgetId(it.id) == requestedId }
                if (requested?.config?.mode?.usesServerSignalsSnapshot() == true)
                    availableTargets.filter { it.config.mode.usesServerSignalsSnapshot() }
                else availableTargets.filter { manager.getAppWidgetId(it.id) == requestedId }
            }
        if (targets.isEmpty()) return Result.success()
        val widget = GuailiWidget()
        if (showFeedback) {
            targets.forEach {
                setWidgetRefreshStatus(applicationContext, it.id, WidgetRefreshPhase.Refreshing)
                widget.update(applicationContext, it.id)
            }
        }
        val needsLegacy = targets.any { it.config.mode.usesLegacyMarketSnapshot() }
        val needsV2 = targets.any { it.config.mode.usesServerSignalsSnapshot() }
        val failures = coroutineScope {
            val legacy = async { if (needsLegacy) GuailiRefreshUseCase(snapshotSink = GuailiSnapshotStore(applicationContext))
                .refresh(settings, requirePersistence = true) else null }
            val v2 = async { if (needsV2) ServerSignalsRefreshUseCase(snapshotSink = ServerSignalsSnapshotStore(applicationContext))
                .refresh(settings.baseUrl, reuseWithinMillis = 1_000L) else null }
            val alerts = async { if (targets.any { it.config.mode == WidgetMode.PullbackObservation })
                refreshPullbackWidgetAlerts(applicationContext, settings.baseUrl) else null }
            val legacyResult = legacy.await()
            val v2Result = v2.await()
            val alertsResult = alerts.await()
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(applicationContext)
            val configurations = WidgetConfigStore(applicationContext)
            targets.forEach { target ->
                val result = if (target.config.mode == WidgetMode.PullbackObservation && alertsResult is com.gouge.guaili.data.PriceAlertResult.Failure)
                    GuailiResult.Failure("价格警报更新失败：${alertsResult.message}")
                    else if (target.config.mode.usesServerSignalsSnapshot()) v2Result else legacyResult
                val currentMode = configurations.read(manager.getAppWidgetId(target.id), settings).mode
                if (result != null && currentMode == target.config.mode) setWidgetRefreshStatus(applicationContext, target.id,
                    if (result is GuailiResult.Success) WidgetRefreshPhase.Success else WidgetRefreshPhase.Failure,
                    message = (result as? GuailiResult.Failure)?.message, deduplicate = !showFeedback)
            }
            if (v2Result is GuailiResult.Success) scheduleServerSignalsExpiry(applicationContext, v2Result.value)
            listOfNotNull(legacyResult, v2Result).filterIsInstance<GuailiResult.Failure>() +
                if (alertsResult is com.gouge.guaili.data.PriceAlertResult.Failure) listOf(GuailiResult.Failure(alertsResult.message)) else emptyList()
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

    fun refreshNow(context: Context, showFeedback: Boolean = false, appWidgetId: Int? = null) {
        val builder = OneTimeWorkRequestBuilder<GuailiWidgetWorker>()
            .setInputData(workDataOf(ShowRefreshFeedbackKey to showFeedback,
                RefreshWidgetIdKey to (appWidgetId ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (!showFeedback) builder.setConstraints(networkConstraints)
        val work = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (appWidgetId == null) ImmediateWorkName else "$ImmediateWorkName-server",
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
internal const val RefreshWidgetIdKey = "refresh_widget_id"

class WidgetFeedbackExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val onlyV2 = inputData.getBoolean(OnlyV2WidgetsKey, false)
        if (onlyV2) clearServerSignalsExpirySchedule()
        val settings = SettingsStore(applicationContext).settings.first()
        val widget = GuailiWidget()
        widgetRefreshTargets(applicationContext, settings)
            .filter { if (onlyV2) it.config.mode.usesServerSignalsSnapshot() else it.config.mode != WidgetMode.DecisionReminders }
            .forEach { widget.update(applicationContext, it.id) }
        return Result.success()
    }
}
