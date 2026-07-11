package com.gouge.guaili.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "guaili_settings")

class SettingsStore(
    context: Context,
) : GuailiSettingsSource {
    private val context = context.applicationContext

    override val settings: Flow<GuailiSettings> = this.context.dataStore.data
        .catch {
            if (it is IOException) {
                emit(emptyPreferences())
            } else {
                throw it
            }
        }
        .map { prefs ->
            val defaults = GuailiSettings.defaults()
            GuailiSettings(
                baseUrl = prefs[Keys.baseUrl] ?: defaults.baseUrl,
                symbols = parseCsvOrDefault(prefs[Keys.symbols], defaults.symbols),
                symbolDisplayMode = parseEnumOrDefault(
                    prefs[Keys.symbolDisplayMode],
                    defaults.symbolDisplayMode,
                ),
                symbolColumnWidthMode = parseEnumOrDefault(
                    prefs[Keys.symbolColumnWidthMode],
                    defaults.symbolColumnWidthMode,
                ),
                tableDensity = parseEnumOrDefault(
                    prefs[Keys.tableDensity],
                    defaults.tableDensity,
                ),
                intervals = parseCsvOrDefault(prefs[Keys.intervals], defaults.intervals),
                autoRefreshSeconds = prefs[Keys.autoRefreshSeconds] ?: defaults.autoRefreshSeconds,
                limit = defaults.limit,
                calcLimit = prefs[Keys.calcLimit] ?: defaults.calcLimit,
                closedOnly = prefs[Keys.closedOnly] ?: defaults.closedOnly,
                maLength = prefs[Keys.maLength] ?: defaults.maLength,
                maType = prefs[Keys.maType] ?: defaults.maType,
                atrLen = prefs[Keys.atrLen] ?: defaults.atrLen,
                atrPercentLen = prefs[Keys.atrPercentLen] ?: defaults.atrPercentLen,
                maxAtrRank = prefs[Keys.maxAtrRank] ?: defaults.maxAtrRank,
                slopeMul = prefs[Keys.slopeMul] ?: defaults.slopeMul,
                useSlope = prefs[Keys.useSlope] ?: defaults.useSlope,
            )
        }

    override suspend fun save(settings: GuailiSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrl] = settings.baseUrl
            prefs[Keys.symbols] = settings.symbols.joinToString(",")
            prefs[Keys.symbolDisplayMode] = settings.symbolDisplayMode.name
            prefs[Keys.symbolColumnWidthMode] = settings.symbolColumnWidthMode.name
            prefs[Keys.tableDensity] = settings.tableDensity.name
            prefs[Keys.intervals] = settings.intervals.joinToString(",")
            prefs[Keys.autoRefreshSeconds] = settings.autoRefreshSeconds
            prefs[Keys.calcLimit] = settings.calcLimit
            prefs[Keys.closedOnly] = settings.closedOnly
            prefs[Keys.maLength] = settings.maLength
            prefs[Keys.maType] = settings.maType
            prefs[Keys.atrLen] = settings.atrLen
            prefs[Keys.atrPercentLen] = settings.atrPercentLen
            prefs[Keys.maxAtrRank] = settings.maxAtrRank
            prefs[Keys.slopeMul] = settings.slopeMul
            prefs[Keys.useSlope] = settings.useSlope
        }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val symbols = stringPreferencesKey("symbols")
        val symbolDisplayMode = stringPreferencesKey("symbol_display_mode")
        val symbolColumnWidthMode = stringPreferencesKey("symbol_column_width_mode")
        val tableDensity = stringPreferencesKey("table_density")
        val intervals = stringPreferencesKey("intervals")
        val autoRefreshSeconds = intPreferencesKey("auto_refresh_seconds")
        val calcLimit = intPreferencesKey("calc_limit")
        val closedOnly = booleanPreferencesKey("closed_only")
        val maLength = intPreferencesKey("ma_length")
        val maType = stringPreferencesKey("ma_type")
        val atrLen = intPreferencesKey("atr_len")
        val atrPercentLen = intPreferencesKey("atr_percent_len")
        val maxAtrRank = doublePreferencesKey("max_atr_rank")
        val slopeMul = doublePreferencesKey("slope_mul")
        val useSlope = booleanPreferencesKey("use_slope")
    }
}

private inline fun <reified T : Enum<T>> parseEnumOrDefault(raw: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: default

private fun parseCsvOrDefault(raw: String?, default: List<String>): List<String> {
    val parsed = raw?.let(::parseCsv).orEmpty()
    return parsed.ifEmpty { default }
}
