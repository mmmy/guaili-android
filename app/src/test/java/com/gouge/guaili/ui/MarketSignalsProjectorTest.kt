package com.gouge.guaili.ui

import com.gouge.guaili.data.GuailiDeviceTime
import com.gouge.guaili.data.GuailiServerClock
import com.gouge.guaili.data.ServerIntervalEvidence
import com.gouge.guaili.data.ServerSignalQualityConfig
import com.gouge.guaili.data.ServerSignalsResponse
import com.gouge.guaili.data.ServerSignalsSnapshot
import com.gouge.guaili.data.ServerSymbolSignals
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.MarketSignalPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSignalsProjectorTest {
    private val now = 1_000_000L
    private val elapsed = 100_000L
    private val settings = GuailiSettings.defaults().copy(baseUrl = "http://server-a", symbols = listOf("BTCUSDT"), autoRefreshSeconds = 60)
    private val device = GuailiDeviceTime(now, elapsed, 1)

    private fun request(needsPullbacks: Boolean = true): MarketProjectionRequest {
        val response = ServerSignalsResponse(true, "ready", now, now,
            qualityConfig = ServerSignalQualityConfig(30_000, 15_000), results = listOf(
                ServerSymbolSignals("BTCUSDT", "ready", now, perIntervalQuality =
                    listOf(0.8, 0.6, 0.5, 0.3, 0.0).mapIndexed { index, value ->
                        ServerIntervalEvidence((5 - index).toString(), "ready", value = (value * 10).toInt(), guaili = value,
                            marketEventTime = now, isClosed = false)
                    }),
            ))
        val snapshot = ServerSignalsSnapshot(response, now, settings.baseUrl, GuailiServerClock(now, elapsed, 1, 0), true)
        return MarketProjectionRequest(1, settings, MarketSignalPreferences(), snapshot, null, needsPullbacks)
    }

    @Test fun unchangedClockTicksAndDisplayFiltersReuseThePullbackProjection() {
        val projector = MarketSignalsProjector()
        val input = request()
        val first = projector.project(input, device)
        assertTrue(first.pullbacks.ready)
        assertEquals(1, first.pullbacks.longHits)
        val second = projector.project(input.copy(generation = 2), device.copy(elapsedMillis = elapsed + 1_000, wallMillis = now + 1_000))
        assertSame(first.pullbacks, second.pullbacks)
        val filtered = projector.project(input.copy(preferences = input.preferences.copy(symbols = emptyList())), device)
        assertSame(first.pullbacks, filtered.pullbacks)
        val differentRules = input.preferences.pullback.rules.copy(threshold = 1)
        val changed = projector.project(input.copy(preferences = input.preferences.copy(pullback = input.preferences.pullback.copy(rules = differentRules))), device)
        assertNotSame(first.pullbacks, changed.pullbacks)
    }

    @Test fun hiddenPullbacksAreComputedOnDemandAndExpireAtThePhoneBoundary() {
        val projector = MarketSignalsProjector()
        val input = request(needsPullbacks = false)
        val hidden = projector.project(input, device)
        assertTrue(hidden.pullbacks.rows.isEmpty())
        val active = projector.project(input.copy(needsPullbacks = true), device)
        assertTrue(active.pullbacks.ready)
        val expiry = requireNotNull(active.pullbacks.nextChangeAt)
        val expiredDevice = device.copy(wallMillis = expiry, elapsedMillis = elapsed + expiry - now)
        val expired = projector.project(input.copy(needsPullbacks = true), expiredDevice)
        assertFalse(expired.pullbacks.ready)
        assertTrue(expired.pullbacks.message.contains("手机刷新超时"))
        assertSame(hidden.pullbacks, projector.project(input, expiredDevice).pullbacks)
    }

    @Test fun unavailableClockCanRecoverWithoutAChangedSnapshotOrBootCount() {
        val projector = MarketSignalsProjector()
        val input = request()
        val unavailable = projector.project(input, device.copy(elapsedMillis = elapsed - 1))
        assertFalse(unavailable.pullbacks.ready)
        assertTrue(unavailable.pullbacks.message.contains("设备已重启"))
        val recovered = projector.project(input.copy(generation = 2), device)
        assertTrue(recovered.pullbacks.ready)
        assertEquals(1, recovered.pullbacks.longHits)
    }
}
