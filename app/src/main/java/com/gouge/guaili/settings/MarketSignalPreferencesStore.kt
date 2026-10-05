package com.gouge.guaili.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.domain.GuailiSignalKind
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class MarketView { Table, SignalsV2 }

@Serializable
internal data class MarketSignalPreferences(
    val view: MarketView = MarketView.Table,
    // null follows the table selection until the user first customizes the signal view.
    val symbols: List<String>? = null,
    val kinds: Set<GuailiSignalKind> = GuailiSignalKind.entries.toSet(),
)

internal interface MarketSignalPreferencesSource {
    val preferences: Flow<MarketSignalPreferences>
    suspend fun update(transform: (MarketSignalPreferences) -> MarketSignalPreferences)
}

private val Context.marketSignalsDataStore by preferencesDataStore(name = "market_signal_preferences")

internal class MarketSignalPreferencesStore(context: Context) : MarketSignalPreferencesSource {
    private val store = context.applicationContext.marketSignalsDataStore
    private val key = stringPreferencesKey("preferences")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String?) = raw?.let {
        runCatching { json.decodeFromString<MarketSignalPreferences>(it) }.getOrNull()
    } ?: MarketSignalPreferences()

    override val preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { decode(it[key]) }

    override suspend fun update(transform: (MarketSignalPreferences) -> MarketSignalPreferences) {
        store.edit { values -> values[key] = json.encodeToString(transform(decode(values[key]))) }
    }
}
