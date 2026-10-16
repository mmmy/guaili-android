package com.gouge.xbot.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.XbotRepository
import com.gouge.xbot.ui.theme.XbotTheme
import com.gouge.xbot.widget.AlertDataCoordinator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class XbotSessionInitializationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val session get() = SessionStore(context)
    private val models = ViewModelStore()
    private val restorationThread = Executors.newSingleThreadExecutor()
    private lateinit var server: MockWebServer
    private lateinit var savedPreferences: Map<String, Map<String, *>>

    @Before fun prepareLocalSessionFixture() {
        val names = (listOf("xbot_secure_session", "xbot_server_config", "xbot_signal_widget_preferences",
            "xbot_alert_widget_data", "xbot_alert_visibility") +
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("xbot_") && it.extension == "xml" }
                .map { it.nameWithoutExtension }).distinct()
        savedPreferences = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        session.clear()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/api/customer/signal-view/list") {
                        MockResponse().addHeader("Content-Type", "application/json").setBody("[]")
                    } else MockResponse().setResponseCode(404)
            }
            start()
        }
        ServerConfigStore(context).saveBaseUrl(server.url("/").toString())
    }

    @After fun restorePreferences() {
        instrumentation.runOnMainSync { models.clear() }
        restorationThread.shutdownNow()
        session.clear()
        savedPreferences.forEach { (name, values) ->
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
        AlertDataCoordinator(context).changed()
        server.shutdown()
    }

    @Test fun delayedSignedInRestorationKeepsMainResponsiveAndShowsLoadingUntilReady() {
        session.saveAccessToken("initialization-account-a")
        val dispatcher = PausedSessionDispatcher()
        val model = createModel(dispatcher)
        showAccount(model)

        assertRestoring(model)
        dispatcher.runNext()
        compose.waitUntil(10_000) { !model.uiState.value.isSessionInitializing && !model.uiState.value.isLoading }

        compose.onNodeWithTag("xbot-session-loading").assertDoesNotExist()
        compose.onNodeWithText("已登录").assertIsDisplayed()
        compose.onNodeWithText(server.url("/").toString()).assertIsDisplayed()
        compose.onNodeWithTag("xbot-login-submit").assertDoesNotExist()
        assertTrue(model.uiState.value.isAuthenticated)
        assertSignalRequest("initialization-account-a")
    }

    @Test fun delayedSignedOutRestorationRevealsSavedServerAndEnabledLoginOnlyWhenReady() {
        val dispatcher = PausedSessionDispatcher()
        val model = createModel(dispatcher)
        showAccount(model)

        assertRestoring(model)
        dispatcher.runNext()
        compose.waitUntil(10_000) { !model.uiState.value.isSessionInitializing }

        compose.onNodeWithTag("xbot-session-loading").assertDoesNotExist()
        compose.onNodeWithTag("xbot-login-server").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(server.url("/").toString())
        compose.onNodeWithTag("xbot-login-submit").assertIsEnabled()
        assertFalse(model.uiState.value.isAuthenticated)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(0, server.requestCount)
    }

    @Test fun accountChangeAfterCredentialsWereReadRejectsOldRestoreAndRetries() {
        verifyGenerationChange("initialization-account-b")
    }

    @Test fun logoutAfterCredentialsWereReadRejectsAuthenticatedRestoreAndRetries() {
        verifyGenerationChange(null)
    }

    private fun verifyGenerationChange(nextToken: String?) {
        session.saveAccessToken("initialization-account-a")
        val dispatcher = PausedSessionDispatcher()
        val model = createModel(dispatcher)
        showAccount(model)
        assertRestoring(model)

        // Hold only the test's Main task while the worker captures A. Its publication is
        // queued behind us, so changing generation here deterministically precedes it.
        instrumentation.runOnMainSync {
            restorationThread.submit { dispatcher.runNext() }.get(5, TimeUnit.SECONDS)
            if (nextToken == null) session.clear() else session.saveAccessToken(nextToken)
        }
        instrumentation.waitForIdleSync()
        assertRestoring(model)
        assertEquals(1, dispatcher.pendingCount)
        dispatcher.runNext()
        compose.waitUntil(10_000) { !model.uiState.value.isSessionInitializing && !model.uiState.value.isLoading }

        assertEquals(nextToken != null, model.uiState.value.isAuthenticated)
        assertEquals(server.url("/").toString(), model.uiState.value.serverUrl)
        if (nextToken != null) {
            compose.onNodeWithText("已登录").assertIsDisplayed()
            assertSignalRequest(nextToken)
        } else {
            compose.onNodeWithTag("xbot-login-submit").assertIsEnabled()
            assertEquals(0, server.requestCount)
        }
    }

    private fun createModel(dispatcher: CoroutineDispatcher): MainViewModel {
        lateinit var model: MainViewModel
        instrumentation.runOnMainSync {
            val config = ServerConfigStore(context)
            model = MainViewModel(XbotRepository(config, session), config, session,
                AlertVisibilityStore(context), AlertDataCoordinator(context), onSignalsChanged = {},
                sessionDispatcher = dispatcher)
            models.put("session-under-test", model)
        }
        return model
    }

    private fun showAccount(model: MainViewModel) {
        compose.setContent { XbotTheme { XbotAccountScreen(model, onBack = {}) } }
    }

    private fun assertRestoring(model: MainViewModel) {
        // These Compose queries and runOnIdle must complete while the session worker is paused.
        compose.onNodeWithTag("xbot-session-loading").assertIsDisplayed()
        compose.onNodeWithTag("xbot-login-submit").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(model.uiState.value.isSessionInitializing)
            assertTrue(model.uiState.value.isLoading)
            assertFalse(model.uiState.value.isAuthenticated)
        }
        assertEquals(0, server.requestCount)
    }

    private fun assertSignalRequest(token: String) {
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Local signal request was not made" }
        assertEquals("/api/customer/signal-view/list", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    /** Pauses only credential restoration, leaving Android Main and all UI work untouched. */
    private class PausedSessionDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        val pendingCount get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }

        fun runNext() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Credential fixture must run off Main" }
            checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Credential restoration was not dispatched" }.run()
        }
    }
}
