package com.gouge.guaili.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.gouge.guaili.data.*
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.PriceAlertConfirmation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val Context.pullbackAlertsDataStore by preferencesDataStore(name = "pullback_widget_price_alerts")

@Serializable
internal data class PullbackWidgetAlertSnapshot(val alerts: List<PriceAlertDto> = emptyList(),
    val updatedAt: Long = 0, val error: String? = null,
    @kotlinx.serialization.Transient val receipt: GuailiDeviceTime? = null)

internal class PullbackWidgetAlertStore(context: Context, baseUrl: String) {
    private val store = context.applicationContext.pullbackAlertsDataStore
    private val scope = normalizeServerSignalsBaseUrl(baseUrl)
    private val key = stringPreferencesKey(MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
        .joinToString("") { "%02x".format(it) })
    private val elapsedKey = longPreferencesKey("${key.name}_elapsed")
    private val wallKey = longPreferencesKey("${key.name}_wall")
    private val bootKey = intPreferencesKey("${key.name}_boot")
    private fun Preferences.receipt(): GuailiDeviceTime? {
        val elapsed = this[elapsedKey] ?: return null
        val wall = this[wallKey] ?: return null
        return GuailiDeviceTime(wall, elapsed, this[bootKey])
    }
    private fun MutablePreferences.setReceipt(time: GuailiDeviceTime) {
        this[elapsedKey] = time.elapsedMillis
        this[wallKey] = time.wallMillis
        time.bootCount?.let { this[bootKey] = it } ?: remove(bootKey)
    }
    private val decoded = caches.forStore(store)
    val snapshots = store.data.catch { if (it is java.io.IOException) emit(emptyPreferences()) else throw it }
        .map { it[key] to it.receipt() }.distinctUntilChanged().map { (encoded, receipt) ->
        (decoded.decode(encoded) { json.decodeFromString<PullbackWidgetAlertSnapshot>(it) }
            ?: PullbackWidgetAlertSnapshot()).copy(receipt = receipt)
    }.flowOn(Dispatchers.Default)

    suspend fun publish(result: PriceAlertResult<List<PriceAlertDto>>, generation: Long,
        received: GuailiDeviceTime? = null): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        store.edit { values ->
            val previous = values[key]?.let { json.decodeFromString<PullbackWidgetAlertSnapshot>(it) } ?: PullbackWidgetAlertSnapshot()
            val next = when (result) {
                is PriceAlertResult.Success -> if (previous.alerts == result.value && previous.error == null) previous
                    else PullbackWidgetAlertSnapshot(result.value, System.currentTimeMillis())
                is PriceAlertResult.Failure -> previous.copy(error = result.message)
            }
            val refreshReceipt = result is PriceAlertResult.Success && received != null &&
                (previous != next || shouldPersistPullbackReceipt(values.receipt(), received))
            if (previous == next && !refreshReceipt) return@edit
            val encoded = if (previous != next) json.encodeToString(next) else null
            val version = versions.getOrPut(scope) { AlertScopeVersion() }
            synchronized(version) {
                // A confirmed edit/delete invalidates every full read begun before it.
                if (version.generation == generation) {
                    if (encoded != null) values[key] = encoded
                    if (refreshReceipt) values.setReceipt(requireNotNull(received))
                    changed = true
                }
            }
        }
        changed
    }

    suspend fun confirm(confirmation: PriceAlertConfirmation) = withContext(Dispatchers.IO) {
        val id = confirmation.alert?.id ?: confirmation.operation.alertId ?: return@withContext
        store.edit { values ->
            val previous = values[key]?.let { json.decodeFromString<PullbackWidgetAlertSnapshot>(it) } ?: PullbackWidgetAlertSnapshot()
            val existing = previous.alerts.firstOrNull { it.id == id }
            val alert = confirmation.alert
            if (alert != null && existing != null && alert.revision < existing.revision) return@edit
            values[key] = json.encodeToString(previous.copy(alerts = previous.alerts.filterNot { it.id == id } +
                listOfNotNull(alert), updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val caches = SnapshotCacheRegistry<PullbackWidgetAlertSnapshot>()
    }
}

private class AlertScopeVersion(var generation: Long = 0, var persistenceFailed: Boolean = false)
private val versions = ConcurrentHashMap<String, AlertScopeVersion>()
private val alertReads = RefreshSingleFlight<Pair<String, Long>, PriceAlertResult<List<PriceAlertDto>>>()
private val alertPublications = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun hasPullbackWidgets(context: Context): Boolean {
    val manager = GlanceAppWidgetManager(context)
    val ids = manager.getGlanceIds(GuailiWidget::class.java)
    if (ids.isEmpty()) return false
    val settings = SettingsStore(context).settings.first()
    val configs = WidgetConfigStore(context)
    return ids.any { configs.read(manager.getAppWidgetId(it), settings).mode == WidgetMode.PullbackObservation }
}

/** The foreground's existing ten-second read and the widget worker join one request. */
internal suspend fun refreshPullbackWidgetAlerts(context: Context, baseUrl: String,
    onlyIfInstalled: Boolean = false): PriceAlertResult<List<PriceAlertDto>> {
    if (onlyIfInstalled && !hasPullbackWidgets(context)) return PriceAlertRepository.create(baseUrl).listAll()
    val scope = normalizeServerSignalsBaseUrl(baseUrl)
    val version = versions.getOrPut(scope) { AlertScopeVersion() }
    val generation = synchronized(version) { version.generation }
    return alertReads.run(scope to generation) {
        val result = PriceAlertRepository.create(baseUrl).listAll()
        val received = readGuailiDeviceTime(context)
        val changed = try {
            PullbackWidgetAlertStore(context, baseUrl).publish(result, generation, received)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            synchronized(version) {
                if (version.generation == generation) version.persistenceFailed = true
            }
            return@run PriceAlertResult.Failure("价格警报快照未保存，请重试")
        }
        val recovered = if (result is PriceAlertResult.Success) synchronized(version) {
            if (version.generation != generation) false else {
                val failed = version.persistenceFailed
                version.persistenceFailed = false
                failed
            }
        } else false
        if (changed && result is PriceAlertResult.Success) try {
            PullbackWidgetAlertStore(context, baseUrl).snapshots.first().receipt?.let {
                schedulePullbackWidgetExpiry(context, baseUrl, it)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* A failed redraw schedule cannot invalidate the persisted successful read. */ }
        if (changed || recovered) try { updatePullbackWidgets(context, baseUrl) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* A rendering error does not invalidate the saved observation. */ }
        result
    }
}

internal fun confirmPullbackWidgetAlert(context: Context, baseUrl: String, confirmation: PriceAlertConfirmation) {
    val version = versions.getOrPut(normalizeServerSignalsBaseUrl(baseUrl)) { AlertScopeVersion() }
    synchronized(version) { version.generation++ }
    alertPublications.launch {
        if (!hasPullbackWidgets(context)) return@launch
        try {
            PullbackWidgetAlertStore(context, baseUrl).confirm(confirmation)
            updatePullbackWidgets(context, baseUrl)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* A failed widget cache cannot roll back a confirmed server edit. */ }
    }
}

internal suspend fun updatePullbackWidgets(context: Context, baseUrl: String? = null) {
    val settings = SettingsStore(context).settings.first()
    if (baseUrl != null && normalizeServerSignalsBaseUrl(baseUrl) != normalizeServerSignalsBaseUrl(settings.baseUrl)) return
    val widget = GuailiWidget()
    widgetRefreshTargets(context, settings).filter { it.config.mode == WidgetMode.PullbackObservation }
        .forEach { widget.update(context, it.id) }
}

internal fun pullbackWidgetAlertsStale(baseUrl: String, snapshot: PullbackWidgetAlertSnapshot, device: GuailiDeviceTime): Boolean {
    val version = versions[normalizeServerSignalsBaseUrl(baseUrl)]
    return snapshot.error != null || version?.let { synchronized(it) { it.persistenceFailed } } == true ||
        pullbackWidgetReceiptOverdue(snapshot.receipt, device)
}
