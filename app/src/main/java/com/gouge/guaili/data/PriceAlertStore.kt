package com.gouge.guaili.data

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

@Serializable
data class PriceAlertOperation(
    val method: String,
    val mutationId: String,
    val alertId: Long? = null,
    val expectedRevision: Long = 0,
    val body: JsonObject,
    val previousGeometry: PriceAlertGeometry? = null,
    val queuedGeometry: PriceAlertGeometry? = null,
    val isUndo: Boolean = false,
    val requiresUserRetry: Boolean = false,
) { val key: Long get() = alertId ?: 0L }

interface PriceAlertJournal {
    fun read(): List<PriceAlertOperation>
    suspend fun write(operations: List<PriceAlertOperation>)
}

interface PriceAlertSeenStore {
    fun readSeenTriggers(): Set<String>
    suspend fun saveSeenTriggers(triggers: Set<String>)
}

class PriceAlertStore(context: Context, baseUrl: String) : PriceAlertJournal, PriceAlertSeenStore {
    private val hash = MessageDigest.getInstance("SHA-256").digest(baseUrl.trimEnd('/').toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    private val preferences = context.applicationContext.getSharedPreferences("price-alerts-$hash", Context.MODE_PRIVATE)
    override fun read(): List<PriceAlertOperation> = preferences.getString("pending", null)?.let {
        runCatching { PriceAlertRepository.json.decodeFromString<List<PriceAlertOperation>>(it) }.getOrNull()
    }.orEmpty()
    override suspend fun write(operations: List<PriceAlertOperation>) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("pending", PriceAlertRepository.json.encodeToString(operations)).commit()) { "无法保存操作记录" }
    }
    override fun readSeenTriggers(): Set<String> = preferences.getStringSet("seen-triggers", emptySet()).orEmpty().toSet()
    override suspend fun saveSeenTriggers(triggers: Set<String>) = withContext(Dispatchers.IO) {
        check(preferences.edit().putStringSet("seen-triggers", triggers).commit()) { "无法保存警报查看记录" }
    }
    fun preset(): PriceAlertPreset = preferences.getString("preset", null)?.let {
        runCatching { PriceAlertRepository.json.decodeFromString<PriceAlertPreset>(it) }.getOrNull()
    } ?: PriceAlertPreset()
    suspend fun savePreset(preset: PriceAlertPreset) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("preset", PriceAlertRepository.json.encodeToString(preset)).commit()) { "无法保存预设" }
    }
}
