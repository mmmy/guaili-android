package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.SignalObservation
import com.gouge.xbot.data.SignalViewDto

internal data class SignalWidgetUpdate(val state: WidgetState, val status: String?, val applied: Boolean)

internal fun signalWidgetUpdate(current: WidgetState, observation: SignalObservation,
    updatedAtMillis: Long = System.currentTimeMillis(),
    signalsById: Map<String, SignalViewDto> = observation.signals.associateBy { it.id }): SignalWidgetUpdate {
    var applied = false
    val snapshots = current.signals.map { snapshot ->
        val signal = signalsById[snapshot.signalId]
        when {
            snapshot.observationEpoch == observation.epoch && snapshot.observationRevision >= observation.revision -> snapshot
            signal == null && !observation.complete -> snapshot
            else -> {
                applied = true
                (signal?.let { WidgetSnapshot.from(it, updatedAtMillis, snapshot.showSymbol) } ?: snapshot).copy(
                    observationEpoch = observation.epoch,
                    observationRevision = observation.revision,
                    observationMissing = signal == null,
                )
            }
        }
    }
    val missing = snapshots.count { it.observationMissing }
    return SignalWidgetUpdate(WidgetState(snapshots), when {
        missing == current.signals.size -> "信号已不存在"
        missing > 0 -> "部分信号已不存在"
        else -> null
    }, applied)
}

/** The foreground and the worker publish the same fetched list; publishing never fetches again. */
internal object SignalWidgetUpdater {
    fun update(context: Context, observation: SignalObservation, expectedGeneration: Long, ids: IntArray? = null,
        status: String? = null) {
        val session = SessionStore(context)
        val widgetIds = ids ?: AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, SignalWidgetProvider::class.java),
        )
        if (widgetIds.isEmpty()) return
        val signalsById = observation.signals.associateBy { it.id }
        val preferences = WidgetPreferences(context)
        widgetIds.forEach { id ->
            session.withCurrentGeneration(expectedGeneration) {
                val scope = session.currentScope() ?: return@withCurrentGeneration
                val current = preferences.get(id)
                if (current == null) {
                    SignalWidgetRenderer.render(context, id, null, "请重新配置小组件")
                } else {
                    val updated = signalWidgetUpdate(current, observation, signalsById = signalsById)
                    if (!updated.applied) return@withCurrentGeneration
                    if (updated.state != current) preferences.save(id, updated.state, scope)
                    SignalWidgetRenderer.render(context, id, updated.state, status ?: updated.status)
                }
            }
        }
    }
}
