package com.gouge.guaili.signals

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import org.junit.Assert.*
import org.junit.Test

class ServerSignalsPresentationCacheTest {
    private val at = 1_000_000L
    private val source = "http://server"
    private val config = ServerSignalDisplayConfig(listOf("BTCUSDT"), setOf(GuailiSignalKind.Extreme))
    private val structure = ServerSignalStructure("btc", "extreme", "positive",
        listOf(ServerSignalRun("positive", listOf("1", "2"))), 2, 2, "2", formedAt = at)
    private val response = ServerSignalsResponse(true, "ready", at, at,
        qualityConfig = ServerSignalQualityConfig(30_000, 15_000), results = listOf(
            ServerSymbolSignals("BTCUSDT", "ready", at, signals = listOf(structure),
                perIntervalQuality = listOf("1", "2").map { ServerIntervalEvidence(it, "ready") })))
    private val snapshot = ServerSignalsSnapshot(response, at, source, GuailiServerClock(at, 100_000, 1, 10), true)
    private fun device(age: Long = 0) = GuailiDeviceTime(at + age, 100_000 + age, 1)

    @Test fun healthyClockTicksReuseContentAndOnlyPhaseBoundaryChangesDisplay() {
        val cache = ServerSignalsPresentationCache()
        val first = cache.state(snapshot, null, config, source, device(), 30_000)
        assertEquals(at + 10_001, cache.nextChangeAt)
        for (age in listOf(1L, 1_000L, 9_999L, 10_000L)) {
            assertSame(first, cache.state(snapshot, null, config, source, device(age), 30_000))
        }
        val ongoing = cache.state(snapshot, null, config, source, device(10_001), 30_000)
        assertNotSame(first, ongoing)
        assertSame(first.signals, ongoing.signals)
        assertEquals("动态 · 持续", serverSignalPhase(structure, ongoing.nowMillis, ongoing.evaluationIntervalMs))
        assertEquals(at + 42_001, cache.nextChangeAt)
        assertSame(ongoing, cache.state(snapshot, null, config, source, device(42_000), 30_000))
        assertEquals("stale", cache.state(snapshot, null, config, source, device(42_001), 30_000).status)
        assertNull(cache.nextChangeAt)
    }

    @Test fun settingsAndClockHealthInvalidateImmediatelyWithoutWaitingForTheOldDeadline() {
        val cache = ServerSignalsPresentationCache()
        cache.state(snapshot, null, config, source, device(), 30_000)
        assertEquals("stale", cache.state(snapshot, null, config, source, device(20_000), 5_000).status)
        assertEquals("ready", cache.state(snapshot, null, config, source, device(20_000), 60_000).status)
        assertTrue(cache.state(snapshot, null, config.copy(symbols = listOf("XAUUSDT")), source,
            device(20_000), 60_000).signals.isEmpty())
        val uncertain = cache.state(snapshot, null, config, source, device(20_000).copy(bootCount = 2), 60_000)
        assertEquals("time_uncertain", uncertain.status)
        assertTrue(uncertain.signals.isEmpty())
        assertEquals("ready", cache.state(snapshot, null, config, source, device(20_000), 60_000).status)
        assertEquals("unavailable", cache.state(snapshot, null, config, "http://other", device(20_000), 60_000).status)
    }

    @Test fun failuresInvalidateWarningsAndWallClockEditsDoNotExtendServerFreshness() {
        val cache = ServerSignalsPresentationCache()
        val first = cache.state(snapshot, null, config, source, device(), 30_000)
        assertSame(first, cache.state(snapshot, null, config, source, device(1_000).copy(wallMillis = Long.MIN_VALUE), 30_000))
        val failed = cache.state(snapshot, ServerSignalsFailure("offline", at, source), config, source, device(1_000), 30_000)
        assertTrue(failed.warning!!.contains("offline"))
        assertEquals("stale", cache.state(snapshot, null, config, source,
            device(42_001).copy(wallMillis = at), 30_000).status)
    }

    @Test fun phaseSchedulingHandlesFutureStartsPriorityAndInclusiveEnds() {
        val future = structure.copy(formedAt = at + 2_000)
        val items = listOf(ServerSignalItem("BTCUSDT", future))
        assertEquals(at + 2_000, nextServerSignalPhaseChange(items, at, 5_000))
        assertEquals(at + 12_001, nextServerSignalPhaseChange(items, at + 2_000, 5_000))
        assertEquals(at + 12_001, nextServerSignalPhaseChange(items, at + 12_000, 5_000))
        assertNull(nextServerSignalPhaseChange(items, at + 12_001, 5_000))
        val changed = future.copy(firstObservedAt = at - 2_000, lastChangedAt = at)
        // The formed event starts inside the higher-priority range-changed phase and does not need a wake-up.
        assertEquals(at + 10_001, nextServerSignalPhaseChange(listOf(ServerSignalItem("BTCUSDT", changed)), at, 5_000))
        assertEquals(at + 12_001, nextServerSignalPhaseChange(listOf(ServerSignalItem("BTCUSDT", changed)), at + 10_001, 5_000))
    }
}
