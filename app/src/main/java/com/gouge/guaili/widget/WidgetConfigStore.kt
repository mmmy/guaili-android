package com.gouge.guaili.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.widgetConfigDataStore by preferencesDataStore(name = "guaili_widget_config")

data class WidgetConfig(
    val symbols: List<String>,
    val intervals: List<String>,
    val mode: WidgetMode = WidgetMode.Signals,
    val singleSymbolColumns: WidgetColumnCount = WidgetColumnCount.Auto,
    val reminders: List<DecisionReminder> = emptyList(),
)

enum class WidgetMode(val label: String) {
    Signals("信号模式"),
    Matrix("数据矩阵"),
    SingleSymbol("单品种全周期"),
    DecisionReminders("决策提醒"),
}

@Serializable
data class DecisionReminder(
    val id: String,
    val symbol: String,
    val interval: String,
    val direction: DecisionDirection,
    val targetAtEpochMillis: Long,
)

@Serializable
enum class DecisionDirection(val label: String, val glyph: String) {
    Long("看多", "↑"),
    Short("看空", "↓"),
}

enum class WidgetColumnCount(val label: String, val count: Int?) {
    Auto("自动", null),
    Five("5 列", 5),
    Six("6 列", 6),
    Eight("8 列", 8),
    Ten("10 列", 10),
}

class WidgetConfigStore(context: Context) {
    private val context = context.applicationContext

    suspend fun read(appWidgetId: Int, settings: GuailiSettings): WidgetConfig {
        val preferences = context.widgetConfigDataStore.data.first()
        val savedSymbols = parse(preferences[stringPreferencesKey(symbolsKey(appWidgetId))])
        val savedIntervals = parse(preferences[stringPreferencesKey(intervalsKey(appWidgetId))])
        val savedMode = preferences[stringPreferencesKey(modeKey(appWidgetId))]
        val savedColumnCount = preferences[stringPreferencesKey(columnCountKey(appWidgetId))]
        val savedReminders = decodeReminders(
            preferences[stringPreferencesKey(remindersKey(appWidgetId))],
        )
        val mode = savedMode
            ?.let { saved -> WidgetMode.entries.firstOrNull { it.name == saved } }
            ?: WidgetMode.Signals
        val symbols = if (savedMode == null) {
            settings.symbols.take(MaxSymbols)
        } else {
            savedSymbols.ifEmpty { settings.symbols.take(MaxSymbols) }
        }
        return WidgetConfig(
            symbols = normalizeWidgetSymbols(symbols, mode),
            intervals = savedIntervals.ifEmpty { defaultIntervals(settings.intervals) },
            mode = mode,
            singleSymbolColumns = savedColumnCount
                ?.let { saved -> WidgetColumnCount.entries.firstOrNull { it.name == saved } }
                ?: WidgetColumnCount.Auto,
            reminders = savedReminders,
        )
    }

    suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        val normalizedConfig = normalizeWidgetConfig(config)
        context.widgetConfigDataStore.edit { preferences ->
            preferences[stringPreferencesKey(symbolsKey(appWidgetId))] =
                normalizedConfig.symbols.joinToString(",")
            preferences[stringPreferencesKey(intervalsKey(appWidgetId))] =
                normalizedConfig.intervals.joinToString(",")
            preferences[stringPreferencesKey(modeKey(appWidgetId))] = normalizedConfig.mode.name
            preferences[stringPreferencesKey(columnCountKey(appWidgetId))] =
                normalizedConfig.singleSymbolColumns.name
            preferences[stringPreferencesKey(remindersKey(appWidgetId))] =
                ReminderJson.encodeToString(normalizedConfig.reminders)
        }
    }

    suspend fun delete(appWidgetId: Int) {
        context.widgetConfigDataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(symbolsKey(appWidgetId)))
            preferences.remove(stringPreferencesKey(intervalsKey(appWidgetId)))
            preferences.remove(stringPreferencesKey(modeKey(appWidgetId)))
            preferences.remove(stringPreferencesKey(columnCountKey(appWidgetId)))
            preferences.remove(stringPreferencesKey(remindersKey(appWidgetId)))
        }
    }

    companion object {
        const val MaxSymbols = 5
        const val MaxIntervals = 4
        const val MaxReminders = 12

        private val PreferredIntervals = listOf("5", "15", "60", "D")

        fun defaultIntervals(available: List<String>): List<String> {
            val preferred = PreferredIntervals.filter(available::contains)
            return (preferred + available).distinct().take(MaxIntervals)
        }

        private fun symbolsKey(id: Int) = "widget_${id}_symbols"
        private fun intervalsKey(id: Int) = "widget_${id}_intervals"
        private fun modeKey(id: Int) = "widget_${id}_mode"
        private fun columnCountKey(id: Int) = "widget_${id}_single_symbol_columns"
        private fun remindersKey(id: Int) = "widget_${id}_decision_reminders"

        private fun parse(value: String?): List<String> = value
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

        private fun decodeReminders(value: String?): List<DecisionReminder> =
            value
                ?.let { encoded ->
                    runCatching { ReminderJson.decodeFromString<List<DecisionReminder>>(encoded) }
                        .getOrDefault(emptyList())
                }
                .orEmpty()
                .filter { reminder ->
                    reminder.id.isNotBlank() &&
                        reminder.symbol.isNotBlank() &&
                        reminder.interval.isNotBlank() &&
                        reminder.targetAtEpochMillis > 0L
                }
                .distinctBy(DecisionReminder::id)
                .take(MaxReminders)
    }
}

internal fun normalizeWidgetSymbols(symbols: List<String>, mode: WidgetMode): List<String> =
    symbols.take(if (mode == WidgetMode.SingleSymbol) 1 else WidgetConfigStore.MaxSymbols)

internal fun normalizeWidgetConfig(config: WidgetConfig): WidgetConfig = config.copy(
    symbols = normalizeWidgetSymbols(config.symbols, config.mode),
    intervals = config.intervals.take(WidgetConfigStore.MaxIntervals),
    reminders = config.reminders
        .filter { it.symbol.isNotBlank() && it.interval.isNotBlank() && it.targetAtEpochMillis > 0L }
        .distinctBy(DecisionReminder::id)
        .take(WidgetConfigStore.MaxReminders),
)

private val ReminderJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
