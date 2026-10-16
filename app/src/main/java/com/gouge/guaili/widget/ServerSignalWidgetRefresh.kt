package com.gouge.guaili.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gouge.guaili.data.*
import com.gouge.guaili.settings.GuailiSettings
import java.util.concurrent.TimeUnit

internal data class WidgetRefreshTarget(val id: GlanceId, val config: WidgetConfig)

/** Widget selections are presentation filters; production refreshes fetch the full universe. */
internal fun serverSignalQuerySymbols(configurations: Iterable<WidgetConfig>): List<String> =
    configurations.filter { it.mode == WidgetMode.SignalsV2 }
        .flatMap { it.symbols }.map(String::trim).filter(String::isNotEmpty).distinct()

internal suspend fun widgetRefreshTargets(context: Context, settings: GuailiSettings): List<WidgetRefreshTarget> {
    val manager = GlanceAppWidgetManager(context)
    val store = WidgetConfigStore(context)
    return manager.getGlanceIds(GuailiWidget::class.java).map { id ->
        WidgetRefreshTarget(id, store.read(manager.getAppWidgetId(id), settings))
    }
}

/** The market's single V2 poller updates widgets in both table and signal views. */
internal suspend fun updateServerSignalWidgets(context: Context, settings: GuailiSettings,
    result: GuailiResult<ServerSignalsSnapshot>) {
    val targets = widgetRefreshTargets(context, settings).filter { it.config.mode.usesServerSignalsSnapshot() }
    if (targets.isEmpty()) return
    targets.forEach { target -> setWidgetRefreshStatus(context, target.id,
        if (result is GuailiResult.Success) WidgetRefreshPhase.Success else WidgetRefreshPhase.Failure,
        message = (result as? GuailiResult.Failure)?.message, deduplicate = true) }
    if (result is GuailiResult.Success) {
        if (targets.any { it.config.mode == WidgetMode.SignalsV2 }) scheduleServerSignalsExpiry(context, result.value)
        if (targets.any { it.config.mode == WidgetMode.PullbackObservation }) result.value.serverClock?.let {
            schedulePullbackWidgetExpiry(context, settings.baseUrl,
                GuailiDeviceTime(result.value.updatedAt, it.receivedElapsedMillis, it.bootCount))
        }
    }
    val widget = GuailiWidget()
    targets.forEach { widget.update(context, it.id) }
}

internal data class ServerSignalsExpiryIdentity(val baseUrl: String, val evaluatedAt: Long, val lifetimeMillis: Long)
internal fun serverSignalsExpiryIdentity(snapshot: ServerSignalsSnapshot): ServerSignalsExpiryIdentity? =
    if (!snapshot.response.enabled) null else ServerSignalsExpiryIdentity(
        normalizeServerSignalsBaseUrl(snapshot.baseUrl), snapshot.response.evaluatedAt ?: snapshot.response.serverTime,
        serverSignalsCacheLifetime(snapshot.response),
    )

private val serverSignalsExpiryLock = Any()
private var scheduledServerSignalsExpiry: ServerSignalsExpiryIdentity? = null

internal fun scheduleServerSignalsExpiry(context: Context, snapshot: ServerSignalsSnapshot) {
    val identity = serverSignalsExpiryIdentity(snapshot) ?: return
    val age = snapshot.response.evaluatedAt?.let { (snapshot.response.serverTime - it).coerceAtLeast(0L) } ?: 0L
    val delay = (serverSignalsCacheLifetime(snapshot.response) - age + 1_000L).coerceAtLeast(1_000L)
    synchronized(serverSignalsExpiryLock) {
        // Polling can return the same evaluated result many times. Its expiry is unchanged.
        if (scheduledServerSignalsExpiry == identity) return
        WorkManager.getInstance(context).enqueueUniqueWork("server-signals-v2-cache-expiry",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetFeedbackExpiryWorker>()
                .setInputData(workDataOf(OnlyV2WidgetsKey to true))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        scheduledServerSignalsExpiry = identity
    }
}

internal fun clearServerSignalsExpirySchedule() = synchronized(serverSignalsExpiryLock) {
    scheduledServerSignalsExpiry = null
}

internal const val OnlyV2WidgetsKey = "only_v2_widgets"

internal const val OnlyPullbackWidgetsKey = "only_pullback_widgets"
private data class PullbackExpiry(val baseUrl: String, val boot: Int, val elapsed: Long)
private val pullbackExpiryLock = Any()
private var scheduledPullbackExpiry: PullbackExpiry? = null

/** Keep an earlier clock check; frequent foreground reads do not repeatedly replace the job. */
internal fun schedulePullbackWidgetExpiry(context: Context, baseUrl: String, receipt: GuailiDeviceTime,
    appendAfterCurrent: Boolean = false) {
    val device = readGuailiDeviceTime(context)
    val age = pullbackWidgetReceiptAge(receipt, device) ?: return
    if (age >= PullbackWidgetRefreshWindowMillis) return
    val next = PullbackExpiry(normalizeServerSignalsBaseUrl(baseUrl), receipt.bootCount ?: return,
        receipt.elapsedMillis + PullbackWidgetRefreshWindowMillis)
    synchronized(pullbackExpiryLock) {
        val previous = scheduledPullbackExpiry
        // The worker re-reads the current service; an old service must never postpone an earlier check.
        if (previous != null && previous.boot == next.boot && previous.elapsed <= next.elapsed) return
        WorkManager.getInstance(context).enqueueUniqueWork("pullback-widget-cache-expiry",
            if (appendAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetFeedbackExpiryWorker>()
                .setInputData(workDataOf(OnlyPullbackWidgetsKey to true))
                .setInitialDelay(next.elapsed - device.elapsedMillis + 1_000, TimeUnit.MILLISECONDS).build())
        scheduledPullbackExpiry = next
    }
}

internal fun clearPullbackExpirySchedule() = synchronized(pullbackExpiryLock) { scheduledPullbackExpiry = null }
