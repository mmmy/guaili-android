package com.gouge.xbot.data

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class SessionStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun getAccessToken(): String? = synchronized(sessionLock) {
        val encrypted = preferences.getString(TokenKey, null) ?: return@synchronized null
        val iv = preferences.getString(TokenIvKey, null) ?: return@synchronized null
        cachedToken?.takeIf { it.encrypted == encrypted && it.iv == iv }?.let { return@synchronized it.token }
        runCatching {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(TagLengthBits, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                Charsets.UTF_8,
            ).also { cachedToken = CachedToken(encrypted, iv, it) }
        }.getOrElse {
            cachedSecretKey = null
            clear()
            null
        }
    }

    fun saveAccessToken(token: String) = synchronized(sessionLock) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        preferences.edit()
            .putString(TokenKey, encoded)
            .putString(TokenIvKey, iv)
            .apply()
        cachedToken = CachedToken(encoded, iv, token)
        cachedScope = null
        revision.incrementAndGet()
        ApiClientFactory.clearSessions()
        notifyChanged()
    }

    fun clear() = synchronized(sessionLock) {
        cachedToken = null
        cachedScope = null
        preferences.edit().clear().apply()
        revision.incrementAndGet()
        ApiClientFactory.clearSessions()
        context.getSharedPreferences("xbot_signal_widget_preferences", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("xbot_alert_widget_data", Context.MODE_PRIVATE).edit().remove("snapshot").apply()
        context.getSharedPreferences("xbot_alert_visibility", Context.MODE_PRIVATE).edit().clear().apply()
        notifyChanged()
    }

    /** Persistent cache identity; no token is stored in widget preferences. */
    fun currentScope(): String? = synchronized(sessionLock) {
        getAccessToken()?.takeIf(String::isNotBlank)?.let { token ->
            val baseUrl = ServerConfigStore(context).getBaseUrl()
            cachedScope?.takeIf { it.baseUrl == baseUrl && it.token == token }?.let { return@synchronized it.scope }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((baseUrl + "\n" + token).toByteArray())
            digest.joinToString("") { "%02x".format(it) }.also { cachedScope = CachedScope(baseUrl, token, it) }
        }
    }

    fun generation(): Long = revision.get()

    fun clearIfCurrent(generation: Long): Boolean = synchronized(sessionLock) {
        if (generation != revision.get()) return@synchronized false
        clear()
        true
    }

    fun withCurrentGeneration(generation: Long, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (generation != revision.get()) return@synchronized false
        action()
        true
    }

    fun withCurrentScope(scope: String?, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (scope == null || scope != currentScope()) return@synchronized false
        action()
        true
    }

    fun saveAccessTokenIfCurrent(token: String, generation: Long, beforeSave: () -> Unit): Boolean =
        withCurrentGeneration(generation) { beforeSave(); saveAccessToken(token) }

    private fun notifyChanged() {
        context.sendBroadcast(Intent(ActionSessionChanged).setPackage(context.packageName))
    }

    private fun getOrCreateSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { cachedSecretKey = it; return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey().also { cachedSecretKey = it }
        }
    }

    companion object {
        private data class CachedToken(val encrypted: String, val iv: String, val token: String)
        private data class CachedScope(val baseUrl: String, val token: String, val scope: String)
        // Accessed only under sessionLock. Compare persisted ciphertext on every read,
        // including reads through a different SessionStore instance.
        private var cachedToken: CachedToken? = null
        private var cachedScope: CachedScope? = null
        private var cachedSecretKey: SecretKey? = null
        private val sessionLock = Any()
        private val revision = AtomicLong()
        const val ActionSessionChanged = "com.gouge.xbot.action.ALERT_SESSION_CHANGED"
        const val PreferencesName = "xbot_secure_session"
        const val TokenKey = "access_token_ciphertext"
        const val TokenIvKey = "access_token_iv"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "xbot_access_token_key"
        const val Transformation = "AES/GCM/NoPadding"
        const val TagLengthBits = 128
    }
}
