package com.gouge.xbot.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionStoreCachingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("xbot_secure_session", "xbot_server_config", "xbot_signal_widget_preferences",
        "xbot_alert_widget_data", "xbot_alert_visibility")
    private lateinit var saved: Map<String, Map<String, *>>

    @Before fun savePreferences() {
        saved = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    }

    @After fun restorePreferences() {
        SessionStore(context).clear()
        saved.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }

    @Test fun cachedSessionFollowsTokenServerAndLogoutAcrossStoreInstances() {
        val first = SessionStore(context)
        val second = SessionStore(context)
        val server = ServerConfigStore(context)
        server.saveBaseUrl("http://127.0.0.1:32101")
        first.saveAccessToken("first-local-fixture")
        val generation = first.generation()
        val scope = first.currentScope()!!
        repeat(10) {
            assertEquals("first-local-fixture", second.getAccessToken())
            assertEquals(scope, second.currentScope())
        }

        server.saveBaseUrl("http://127.0.0.1:32102")
        assertNotEquals(scope, second.currentScope())
        second.saveAccessToken("second-local-fixture")
        assertEquals("second-local-fixture", first.getAccessToken())
        assertFalse(first.withCurrentGeneration(generation) { error("Old generation was accepted") })
        assertFalse(first.withCurrentScope(scope) { error("Old account scope was accepted") })

        second.clear()
        assertNull(first.getAccessToken())
        assertNull(first.currentScope())
    }

    @Test fun restoringPersistedCiphertextDoesNotKeepTheOtherCachedToken() {
        val store = SessionStore(context)
        val preferences = context.getSharedPreferences(SessionStore.PreferencesName, Context.MODE_PRIVATE)
        store.saveAccessToken("original-local-fixture")
        val encrypted = preferences.getString(SessionStore.TokenKey, null)
        val iv = preferences.getString(SessionStore.TokenIvKey, null)
        store.saveAccessToken("temporary-local-fixture")
        assertEquals("temporary-local-fixture", store.getAccessToken())

        preferences.edit().putString(SessionStore.TokenKey, encrypted).putString(SessionStore.TokenIvKey, iv).commit()

        assertEquals("original-local-fixture", SessionStore(context).getAccessToken())
    }
}
