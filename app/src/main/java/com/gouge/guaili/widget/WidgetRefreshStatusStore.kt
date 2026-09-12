package com.gouge.guaili.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

enum class WidgetRefreshPhase {
    Idle,
    Refreshing,
    Success,
    Failure,
}

data class WidgetRefreshStatus(
    val phase: WidgetRefreshPhase = WidgetRefreshPhase.Idle,
    val changedAt: Long = 0L,
    val message: String? = null,
)

internal fun Preferences.widgetRefreshStatus(): WidgetRefreshStatus = WidgetRefreshStatus(
    phase = this[RefreshPhaseKey]
        ?.let { saved -> WidgetRefreshPhase.entries.firstOrNull { it.name == saved } }
        ?: WidgetRefreshPhase.Idle,
    changedAt = this[RefreshChangedAtKey] ?: 0L,
    message = this[RefreshMessageKey],
)

internal suspend fun setWidgetRefreshStatus(
    context: Context,
    glanceId: GlanceId,
    phase: WidgetRefreshPhase,
    changedAt: Long = System.currentTimeMillis(),
    message: String? = null,
) {
    updateAppWidgetState(context, glanceId) { preferences ->
        preferences[RefreshPhaseKey] = phase.name
        preferences[RefreshChangedAtKey] = changedAt
        if (message == null) preferences.remove(RefreshMessageKey) else preferences[RefreshMessageKey] = message
    }
}

internal suspend fun setAllWidgetRefreshStatuses(
    context: Context,
    phase: WidgetRefreshPhase,
    changedAt: Long = System.currentTimeMillis(),
    message: String? = null,
) {
    GlanceAppWidgetManager(context)
        .getGlanceIds(GuailiWidget::class.java)
        .forEach { glanceId ->
            setWidgetRefreshStatus(context, glanceId, phase, changedAt, message)
        }
}

private val RefreshPhaseKey = stringPreferencesKey("widget_refresh_phase")
private val RefreshChangedAtKey = longPreferencesKey("widget_refresh_changed_at")
private val RefreshMessageKey = stringPreferencesKey("widget_refresh_message")
