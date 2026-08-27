package com.gouge.guaili.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.domain.GuailiTable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.guailiSnapshotDataStore by preferencesDataStore(name = "guaili_snapshot")

@Serializable
data class GuailiSnapshot(
    val table: GuailiTable,
    val updatedAt: Long,
)

fun interface GuailiSnapshotSink {
    suspend fun save(snapshot: GuailiSnapshot)
}

class GuailiSnapshotStore(context: Context) : GuailiSnapshotSink {
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): GuailiSnapshot? {
        val preferences = context.guailiSnapshotDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .first()
        val encoded = preferences[SnapshotKey] ?: return null
        return runCatching { json.decodeFromString<GuailiSnapshot>(encoded) }.getOrNull()
    }

    override suspend fun save(snapshot: GuailiSnapshot) {
        val encoded = json.encodeToString(GuailiSnapshot.serializer(), snapshot)
        context.guailiSnapshotDataStore.edit { preferences ->
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
