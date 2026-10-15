package com.gouge.guaili.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gouge.guaili.data.*
import com.gouge.guaili.ui.theme.GuailiTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PullbackValidityClockTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun clockSuspendsInBackgroundResumesImmediatelyAndKeepsUnchangedTimers() {
        val at = 1_800_000_000_000L
        val now = AtomicLong(at)
        val reads = AtomicInteger()
        val observed = mutableLongStateOf(at)
        val mounted = mutableStateOf(true)
        lateinit var owner: Owner
        val alert = PriceAlertDto(1, 1, 1, "BTCUSDT", "120", "BTCUSDT.P", "test",
            PriceAlertGeometry("horizontal_segment", PriceAlertPoint(at - 60_000, 100.0), PriceAlertPoint(at + 62_000, 100.0), "none"),
            "cross_any", "once", "active", webhookUrl = "", messageTemplate = "{}", label = "", dataStatus = "live", createdAt = at, updatedAt = at)
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED } }
        compose.setContent { GuailiTheme {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (mounted.value) {
                    val display = rememberPullbackAlertDisplay(alert, observed.longValue, false, false) { reads.incrementAndGet(); now.get() }
                    Text(display.label, Modifier.testTag("validity-clock"))
                }
            }
        } }
        compose.waitUntil { reads.get() > 0 }
        compose.onNodeWithTag("validity-clock").assertTextEquals("1m")
        val initialReads = reads.get()
        compose.runOnIdle { observed.longValue = at + 100 }
        compose.waitForIdle()
        assertEquals("same unit/integer/boundary must retain timer", initialReads, reads.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        val pausedReads = reads.get()
        now.set(at + 3_000)
        Thread.sleep(2_100)
        assertEquals("background must not read or update time", pausedReads, reads.get())
        compose.onNodeWithTag("validity-clock").assertTextEquals("1m")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil { compose.onAllNodesWithText("<1m").fetchSemanticsNodes().isNotEmpty() }
        val resumedReads = reads.get()
        Thread.sleep(1_100)
        assertEquals("below a minute must wait for end rather than tick each second", resumedReads, reads.get())
        compose.runOnIdle { mounted.value = false }
        compose.onNodeWithTag("validity-clock").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
}
