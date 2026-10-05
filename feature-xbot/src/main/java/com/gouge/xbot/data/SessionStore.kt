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
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun saveAccessToken(token: String) = synchronized(sessionLock) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(TokenKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(TokenIvKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        revision.incrementAndGet()
        notifyChanged()
    }

    fun clear() = synchronized(sessionLock) {
        preferences.edit().clear().apply()
        revision.incrementAndGet()
        context.getSharedPreferences("xbot_signal_widget_preferences", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("xbot_alert_widget_data", Context.MODE_PRIVATE).edit().remove("snapshot").apply()
        context.getSharedPreferences("xbot_alert_visibility", Context.MODE_PRIVATE).edit().clear().apply()
        notifyChanged()
    }

    /** Persistent cache identity; no token is stored in widget preferences. */
    fun currentScope(): String? = synchronized(sessionLock) {
        getAccessToken()?.takeIf(String::isNotBlank)?.let { token ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((ServerConfigStore(context).getBaseUrl() + "\n" + token).toByteArray())
            digest.joinToString("") { "%02x".format(it) }
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
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }

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
            generateKey()
        }
    }

    companion object {
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
