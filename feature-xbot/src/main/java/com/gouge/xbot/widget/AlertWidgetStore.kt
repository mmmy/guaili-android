package com.gouge.xbot.widget

import android.content.Context
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class AlertSnapshotEnvelope(val scope: String, val snapshot: AlertSnapshot)

class AlertWidgetStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("xbot_alert_widget_data", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun currentScope(): String? {
        return SessionStore(context).currentScope()
    }

    fun snapshot(): AlertSnapshot? {
        val scope = currentScope() ?: return null
        val value = preferences.getString("snapshot", null) ?: return null
        return runCatching { json.decodeFromString<AlertSnapshotEnvelope>(value) }.getOrNull()
            ?.takeIf { it.scope == scope }?.snapshot
    }

    fun saveSnapshot(scope: String, snapshot: AlertSnapshot) {
        SessionStore(context).withCurrentScope(scope) {
            preferences.edit().putString("snapshot", json.encodeToString(AlertSnapshotEnvelope(scope, snapshot))).apply()
        }
    }

    fun clearSnapshot() { preferences.edit().remove("snapshot").apply() }

    fun settings(id: Int): AlertWidgetSettings = preferences.getString("settings_$id", null)?.let {
        runCatching { json.decodeFromString<AlertWidgetSettings>(it) }.getOrNull()
    } ?: AlertWidgetSettings()

    fun saveSettings(id: Int, settings: AlertWidgetSettings) {
        preferences.edit().putString("settings_$id", json.encodeToString(settings)).apply()
    }

    fun remove(id: Int) { preferences.edit().remove("settings_$id").apply() }
}
