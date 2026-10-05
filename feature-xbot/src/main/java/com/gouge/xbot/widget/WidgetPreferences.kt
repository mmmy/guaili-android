package com.gouge.xbot.widget

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.gouge.xbot.data.SessionStore

class WidgetPreferences(context: Context) {
    private val session = SessionStore(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun save(appWidgetId: Int, state: WidgetState, expectedScope: String? = session.currentScope()) {
        session.withCurrentScope(expectedScope) {
            preferences.edit().putString(key(appWidgetId), json.encodeToString(state))
                .putString(scopeKey(appWidgetId), expectedScope).apply()
        }
    }

    fun get(appWidgetId: Int): WidgetState? {
        val scope = session.currentScope() ?: return null
        if (scope != preferences.getString(scopeKey(appWidgetId), null)) return null
        val value = preferences.getString(key(appWidgetId), null) ?: return null
        return decodeWidgetState(value, json)
    }

    fun remove(appWidgetId: Int) {
        preferences.edit().remove(key(appWidgetId)).remove(scopeKey(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"
    private fun scopeKey(appWidgetId: Int) = "widget_scope_$appWidgetId"

    private companion object {
        const val PreferencesName = "xbot_signal_widget_preferences"
    }
}
