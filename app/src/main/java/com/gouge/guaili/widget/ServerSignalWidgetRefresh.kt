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
    val targets = widgetRefreshTargets(context, settings).filter { it.config.mode == WidgetMode.SignalsV2 }
    if (targets.isEmpty()) return
    targets.forEach { target -> setWidgetRefreshStatus(context, target.id,
        if (result is GuailiResult.Success) WidgetRefreshPhase.Success else WidgetRefreshPhase.Failure,
        message = (result as? GuailiResult.Failure)?.message, deduplicate = true) }
    if (result is GuailiResult.Success) scheduleServerSignalsExpiry(context, result.value)
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
