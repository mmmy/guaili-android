package com.gouge.guaili.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.domain.GuailiTable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.guailiSnapshotDataStore by preferencesDataStore(name = "guaili_snapshot")

@Serializable
data class GuailiSnapshot(
    val table: GuailiTable,
    val updatedAt: Long,
    val timezone: String? = null,
    val maType: String? = null,
    val maLength: Int? = null,
)

fun interface GuailiSnapshotSink {
    suspend fun save(snapshot: GuailiSnapshot)
}

class GuailiSnapshotStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : GuailiSnapshotSink {
    constructor(context: Context) : this(context.applicationContext.guailiSnapshotDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    val snapshots: Flow<GuailiSnapshot?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[SnapshotKey]?.let { encoded ->
                runCatching { json.decodeFromString<GuailiSnapshot>(encoded) }.getOrNull()
            }
        }
        .distinctUntilChanged()

    suspend fun read(): GuailiSnapshot? = snapshots.first()

    override suspend fun save(snapshot: GuailiSnapshot) {
        val encoded = json.encodeToString(GuailiSnapshot.serializer(), snapshot)
        dataStore.edit { preferences ->
            preferences[SnapshotKey] = encoded
        }
    }

    companion object {
        private val SnapshotKey = stringPreferencesKey("latest_snapshot")
    }
}

internal suspend fun GuailiSnapshotSink.saveIgnoringStorageFailure(snapshot: GuailiSnapshot) {
    try {
        save(snapshot)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A cache write must not turn a successful network refresh into an error.
    }
}
