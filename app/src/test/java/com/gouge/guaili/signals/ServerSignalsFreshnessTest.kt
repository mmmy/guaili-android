package com.gouge.guaili.signals

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import org.junit.Assert.*
import org.junit.Test

class ServerSignalsFreshnessTest {
    private val now = 1_000_000L
    private val device = GuailiDeviceTime(now, 100_000, 1)
    private val url = "http://local"
    private val response = ServerSignalsResponse(true, "ready", now, now,
        qualityConfig = ServerSignalQualityConfig(30_000, 15_000), results = listOf(
            ServerSymbolSignals("BTCUSDT", "ready", now, signals = listOf(
                ServerSignalStructure("signal", "extreme", "positive",
                    listOf(ServerSignalRun("positive", listOf("1", "2"))), 2, 2, "2")),
                perIntervalQuality = listOf("1", "2").map { ServerIntervalEvidence(it, "ready") })))
    private fun state(wire: ServerSignalsResponse = response, elapsed: Long = 0, refresh: Long? = 30_000) =
        serverSignalsState(ServerSignalsSnapshot(wire, now, url,
            GuailiServerClock(now, device.elapsedMillis, 1, 10), true), null,
            ServerSignalDisplayConfig(listOf("BTCUSDT"), setOf(GuailiSignalKind.Extreme)), url,
            device.copy(elapsedMillis = device.elapsedMillis + elapsed), refresh)

    @Test fun phoneFreshnessCoversConfiguredCycleAndRequestTimeoutWhileWidgetExpiryStaysStrict() {
        for (interval in listOf(30_000L, 60_000L)) {
            assertEquals(1, state(elapsed = interval + 12_000, refresh = interval).signals.size)
            val expired = state(elapsed = interval + 12_001, refresh = interval)
            assertEquals("stale", expired.status)
            assertTrue(expired.message.contains("手机刷新超时"))
            assertTrue(expired.signals.isEmpty())
        }
        assertEquals("stale", state(elapsed = 15_001, refresh = null).status)
    }
    @Test fun freshHttpResponsesDoNotHideStalledSamplingOrFutureTimestamps() {
        val stale = state(response.copy(evaluatedAt = now - 15_001))
        assertTrue(stale.message.contains("后端采样停滞"))
        assertTrue(stale.signals.isEmpty())
        assertTrue(state(response.copy(evaluatedAt = now + 2_001)).message.contains("时间异常"))
        assertTrue(state(response.copy(results = response.results.map { it.copy(sampledAt = now - 15_001) })).signals.isEmpty())
    }
}
