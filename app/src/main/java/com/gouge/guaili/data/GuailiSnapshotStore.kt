package com.gouge.guaili.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gouge.guaili.domain.GuailiTable
import com.gouge.guaili.domain.GuailiSignal
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    val signalProfile: String? = null,
    val signalRuleVersion: String? = null,
    val signals: List<GuailiSignal> = emptyList(),
    val serverClock: GuailiServerClock? = null,
)

fun interface GuailiSnapshotSink {
    suspend fun save(snapshot: GuailiSnapshot)
    suspend fun read(): GuailiSnapshot? = null
    fun currentDeviceTime(): GuailiDeviceTime? = null
    fun refreshIdentity(): Any = this
}

internal val NoopGuailiSnapshotSink = GuailiSnapshotSink { }

class GuailiSnapshotStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val deviceTime: () -> GuailiDeviceTime? = { null },
) : GuailiSnapshotSink {
    constructor(context: Context) : this(context.applicationContext.guailiSnapshotDataStore,
        { readGuailiDeviceTime(context.applicationContext) })

    override fun currentDeviceTime(): GuailiDeviceTime? = deviceTime()
    override fun refreshIdentity(): Any = dataStore

    private val json = Json { ignoreUnknownKeys = true }
    private val decoded = caches.forStore(dataStore)

    val snapshots: Flow<GuailiSnapshot?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[SnapshotKey] }
        .distinctUntilChanged()
        .map { encoded ->
            decoded.decode(encoded) { json.decodeFromString<PersistedGuailiSnapshot>(it).restore() }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override suspend fun read(): GuailiSnapshot? = snapshots.first()

    override suspend fun save(snapshot: GuailiSnapshot) {
        val encoded = withContext(Dispatchers.Default) {
            json.encodeToString(PersistedGuailiSnapshot.serializer(), PersistedGuailiSnapshot.from(snapshot))
                .also { decoded.remember(it, snapshot) }
        }
        dataStore.edit { preferences ->
            preferences[SnapshotKey] = encoded
        }
    }

    internal suspend fun clear() { dataStore.edit { it.remove(SnapshotKey) } }

    companion object {
        private val SnapshotKey = stringPreferencesKey("latest_snapshot")
        private val caches = SnapshotCacheRegistry<GuailiSnapshot>()
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
