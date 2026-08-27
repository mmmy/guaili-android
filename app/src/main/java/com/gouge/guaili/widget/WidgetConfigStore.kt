package com.gouge.guaili.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.flow.first

private val Context.widgetConfigDataStore by preferencesDataStore(name = "guaili_widget_config")

data class WidgetConfig(
    val symbols: List<String>,
    val intervals: List<String>,
)

class WidgetConfigStore(context: Context) {
    private val context = context.applicationContext

    suspend fun read(appWidgetId: Int, settings: GuailiSettings): WidgetConfig {
        val preferences = context.widgetConfigDataStore.data.first()
        val savedSymbols = parse(preferences[stringPreferencesKey(symbolsKey(appWidgetId))])
        val savedIntervals = parse(preferences[stringPreferencesKey(intervalsKey(appWidgetId))])
        return WidgetConfig(
            symbols = savedSymbols.ifEmpty { settings.symbols.take(MaxSymbols) },
            intervals = savedIntervals.ifEmpty { defaultIntervals(settings.intervals) },
        )
    }

    suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        context.widgetConfigDataStore.edit { preferences ->
            preferences[stringPreferencesKey(symbolsKey(appWidgetId))] =
                config.symbols.take(MaxSymbols).joinToString(",")
            preferences[stringPreferencesKey(intervalsKey(appWidgetId))] =
                config.intervals.take(MaxIntervals).joinToString(",")
        }
    }

    companion object {
        const val MaxSymbols = 3
        const val MaxIntervals = 4

        private val PreferredIntervals = listOf("5", "15", "60", "D")

        fun defaultIntervals(available: List<String>): List<String> {
            val preferred = PreferredIntervals.filter(available::contains)
            return (preferred + available).distinct().take(MaxIntervals)
        }

        private fun symbolsKey(id: Int) = "widget_${id}_symbols"
        private fun intervalsKey(id: Int) = "widget_${id}_intervals"

        private fun parse(value: String?): List<String> = value
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
    }
}
