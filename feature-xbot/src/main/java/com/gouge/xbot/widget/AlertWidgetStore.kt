package com.gouge.xbot.widget

import android.content.Context
import com.gouge.xbot.data.SessionStore
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

    fun snapshot(): AlertSnapshot? = observation().snapshot

    /** Decode outside the account monitor, then atomically tag only a stable session read. */
    fun observation(): AlertSnapshotObservation {
        val session = SessionStore(context)
        while (true) {
            val generation = session.generation()
            val scope = session.currentScope()
            val snapshot = readSnapshot(scope)
            var observation: AlertSnapshotObservation? = null
            session.withCurrentGeneration(generation) {
                if (session.currentScope() == scope) {
                    observation = AlertSnapshotObservation(scope, generation, snapshot)
                }
            }
            observation?.let { return it }
        }
    }

    private fun readSnapshot(scope: String?): AlertSnapshot? {
        if (scope == null) return null
        val value = preferences.getString("snapshot", null) ?: return null
        val envelope = synchronized(snapshotCacheLock) {
            if (cachedEncoded != value) {
                cachedEnvelope = runCatching { json.decodeFromString<AlertSnapshotEnvelope>(value) }.getOrNull()
                cachedEncoded = value
            }
            cachedEnvelope
        }
        return envelope?.takeIf { it.scope == scope }?.snapshot
    }

    /** Callers doing I/O can require durable persistence before reporting a successful sync. */
    fun saveSnapshot(scope: String, snapshot: AlertSnapshot, requirePersistence: Boolean = false,
        expectedGeneration: Long? = null): Boolean {
        val envelope = AlertSnapshotEnvelope(scope, snapshot)
        // Serialization may involve hundreds of alerts. Keep it outside the session monitor,
        // which is also used by foreground account reads and login/logout.
        val encoded = json.encodeToString(envelope)
        val session = SessionStore(context)
        var saved = false
        val save = {
            saved = session.withCurrentScope(scope) {
                val editor = preferences.edit().putString("snapshot", encoded)
                if (requirePersistence) check(editor.commit()) { "无法保存警报快照" } else editor.apply()
                synchronized(snapshotCacheLock) { cachedEncoded = encoded; cachedEnvelope = envelope }
            }
        }
        if (expectedGeneration == null) save() else session.withCurrentGeneration(expectedGeneration, save)
        return saved
    }

    fun clearSnapshot() {
        preferences.edit().remove("snapshot").apply()
        synchronized(snapshotCacheLock) { cachedEncoded = null; cachedEnvelope = null }
    }

    fun settings(id: Int): AlertWidgetSettings = preferences.getString("settings_$id", null)?.let {
        runCatching { json.decodeFromString<AlertWidgetSettings>(it) }.getOrNull()
    } ?: AlertWidgetSettings()

    fun saveSettings(id: Int, settings: AlertWidgetSettings) {
        preferences.edit().putString("settings_$id", json.encodeToString(settings)).apply()
    }

    fun remove(id: Int) { preferences.edit().remove("settings_$id").apply() }

    companion object {
        private val snapshotCacheLock = Any()
        private var cachedEncoded: String? = null
        private var cachedEnvelope: AlertSnapshotEnvelope? = null
    }
}
