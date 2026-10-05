package com.gouge.guaili.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.serverSignalsDataStore by preferencesDataStore(name = "server_signals_snapshot_v2")

@Serializable
data class ServerSignalsSnapshot(
    val response: ServerSignalsResponse,
    val updatedAt: Long,
    val baseUrl: String,
    val serverClock: GuailiServerClock? = null,
    val fullUniverse: Boolean = false,
)

@Serializable
data class ServerSignalsFailure(
    val message: String,
    val updatedAt: Long,
    val baseUrl: String,
)

fun ServerSignalsSnapshot.belongsTo(baseUrl: String): Boolean =
    normalizeServerSignalsBaseUrl(this.baseUrl) == normalizeServerSignalsBaseUrl(baseUrl)

fun interface ServerSignalsSnapshotSink {
    suspend fun save(snapshot: ServerSignalsSnapshot)
    suspend fun read(): ServerSignalsSnapshot? = null
    fun currentDeviceTime(): GuailiDeviceTime? = null
    suspend fun recordFailure(failure: ServerSignalsFailure?) {}
    suspend fun readFailure(): ServerSignalsFailure? = null
}

interface ServerSignalsSource : ServerSignalsSnapshotSink {
    val snapshots: Flow<ServerSignalsSnapshot?>
    val failures: Flow<ServerSignalsFailure?>
}

class ServerSignalsSnapshotStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val deviceTime: () -> GuailiDeviceTime? = { null },
) : ServerSignalsSource {
    constructor(context: Context) : this(
        context.applicationContext.serverSignalsDataStore,
        { readGuailiDeviceTime(context.applicationContext) },
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val snapshots: Flow<ServerSignalsSnapshot?> = preferences.map { values ->
        values[SnapshotKey]?.let { encoded ->
            runCatching { json.decodeFromString<ServerSignalsSnapshot>(encoded) }.getOrNull()
        }
    }.distinctUntilChanged()

    override val failures: Flow<ServerSignalsFailure?> = preferences.map { values ->
        values[FailureKey]?.let { encoded ->
            runCatching { json.decodeFromString<ServerSignalsFailure>(encoded) }.getOrNull()
        }
    }.distinctUntilChanged()

    override fun currentDeviceTime(): GuailiDeviceTime? = deviceTime()
    override suspend fun read(): ServerSignalsSnapshot? = snapshots.first()
    override suspend fun readFailure(): ServerSignalsFailure? = failures.first()

    suspend fun readForBaseUrl(baseUrl: String): ServerSignalsSnapshot? =
        read()?.takeIf { it.belongsTo(baseUrl) }

    override suspend fun save(snapshot: ServerSignalsSnapshot) {
        val encoded = json.encodeToString(ServerSignalsSnapshot.serializer(), snapshot)
        dataStore.edit { preferences ->
            preferences[SnapshotKey] = encoded
            preferences.remove(FailureKey)
        }
    }

    override suspend fun recordFailure(failure: ServerSignalsFailure?) {
        val encoded = failure?.let { json.encodeToString(ServerSignalsFailure.serializer(), it) }
        dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(FailureKey)
            else preferences[FailureKey] = encoded
        }
    }

    suspend fun clear() {
        dataStore.edit { values -> values.remove(SnapshotKey); values.remove(FailureKey) }
    }

    companion object {
        private val SnapshotKey = stringPreferencesKey("latest_server_snapshot")
        private val FailureKey = stringPreferencesKey("last_refresh_failure")
    }
}

/** Wall-clock edits never make a V2 snapshot appear fresh; use the existing monotonic clock. */
fun assessServerSignalsTime(
    snapshot: ServerSignalsSnapshot,
    device: GuailiDeviceTime,
): GuailiTimeAssessment {
    val clock = snapshot.serverClock
        ?: return GuailiTimeAssessment(unavailableReason = "时间尚未校准，请刷新")
    val fetched = clock.serverTimeMillis.takeIf { it > 0 }
    fun unavailable(reason: String) = GuailiTimeAssessment(
        fetchedAtMillis = fetched,
        unavailableReason = reason,
    )
    if (fetched == null || clock.receivedElapsedMillis < 0) return unavailable("服务器时间无效，请刷新")
    if (clock.bootCount == null || device.bootCount == null) return unavailable("时间基准不可确认，请刷新")
    if (clock.bootCount != device.bootCount || device.elapsedMillis < clock.receivedElapsedMillis) {
        return unavailable("设备已重启，请刷新校准时间")
    }
    if (clock.requestDurationMillis !in 0..MAX_GUAILI_CLOCK_REQUEST_MILLIS) {
        return unavailable("请求延迟过大，时间暂不可确认，请重试")
    }
    val age = device.elapsedMillis - clock.receivedElapsedMillis
    val now = runCatching { Math.addExact(fetched, age) }.getOrNull()
        ?: return unavailable("服务器时间无效，请刷新")
    return GuailiTimeAssessment(now, age, fetched, device.wallMillis - now)
}
