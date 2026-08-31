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
)

internal fun Preferences.widgetRefreshStatus(): WidgetRefreshStatus = WidgetRefreshStatus(
    phase = this[RefreshPhaseKey]
        ?.let { saved -> WidgetRefreshPhase.entries.firstOrNull { it.name == saved } }
        ?: WidgetRefreshPhase.Idle,
    changedAt = this[RefreshChangedAtKey] ?: 0L,
)

internal suspend fun setWidgetRefreshStatus(
    context: Context,
    glanceId: GlanceId,
    phase: WidgetRefreshPhase,
    changedAt: Long = System.currentTimeMillis(),
) {
    updateAppWidgetState(context, glanceId) { preferences ->
        preferences[RefreshPhaseKey] = phase.name
        preferences[RefreshChangedAtKey] = changedAt
    }
}

internal suspend fun setAllWidgetRefreshStatuses(
    context: Context,
    phase: WidgetRefreshPhase,
    changedAt: Long = System.currentTimeMillis(),
) {
    GlanceAppWidgetManager(context)
        .getGlanceIds(GuailiWidget::class.java)
        .forEach { glanceId ->
            setWidgetRefreshStatus(context, glanceId, phase, changedAt)
        }
}

private val RefreshPhaseKey = stringPreferencesKey("widget_refresh_phase")
private val RefreshChangedAtKey = longPreferencesKey("widget_refresh_changed_at")
