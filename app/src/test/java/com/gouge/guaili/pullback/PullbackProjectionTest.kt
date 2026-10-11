package com.gouge.guaili.pullback

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.PullbackRules
import org.junit.Assert.*
import org.junit.Test

class PullbackProjectionTest {
    private val now = 1000000L
    private val device = GuailiDeviceTime(now, 100000, 1)
    private fun fixture(values: List<Double>): ServerSignalsSnapshot {
        val evidence = values.mapIndexed { index, value -> ServerIntervalEvidence(
            (values.size - index).toString(), "ready", value = (value * 10).toInt(), guaili = value,
            marketEventTime = now, isClosed = false) }
        return ServerSignalsSnapshot(ServerSignalsResponse(true, "ready", now, now,
            qualityConfig = ServerSignalQualityConfig(30000, 15000), results = listOf(
                ServerSymbolSignals("BTCUSDT", "ready", now, perIntervalQuality = evidence))), now, "http://server",
            GuailiServerClock(now, device.elapsedMillis, 1, 10), true)
    }
    private fun project(snapshot: ServerSignalsSnapshot, rules: PullbackRules = PullbackRules(), elapsed: Long = 0) =
        projectPullbacks(snapshot, rules, listOf("BTCUSDT"), device.copy(elapsedMillis = device.elapsedMillis + elapsed))
    private fun change(snapshot: ServerSignalsSnapshot, transform: (ServerSymbolSignals) -> ServerSymbolSignals) =
        snapshot.copy(response = snapshot.response.copy(results = snapshot.response.results.map(transform)))

    @Test fun weakTouchAndTruncatedZeroRemainDistinctInBothDirections() {
        for (sign in listOf(1, -1)) for ((last, phase, hit) in listOf(
            Triple(0.2, PullbackPhase.Waiting, false), Triple(0.0, PullbackPhase.Touch, true), Triple(0.04, PullbackPhase.Near, false))) {
            val projection = project(fixture(listOf(0.8, 0.6, 0.5, 0.3, last).map { it * sign }))
            val c = projection.rows.single().candidate(if (sign == 1) PullbackDirection.Long else PullbackDirection.Short)!!
            assertEquals(PullbackStrength.Weak, c.strength); assertEquals(phase, c.phase); assertEquals(hit, c.highlighted)
        }
    }
    @Test fun strongReverseBoundariesOrdinaryAndTargetExcluded() {
        for (sign in listOf(1, -1)) for ((last, hit) in listOf(0.0 to true, -0.3 to true, -0.301 to false, 0.5 to false)) {
            val row = project(fixture(listOf(1.0, 1.4, 0.6, 0.3, last).map { it * sign })).rows.single()
            val c = row.candidate(if (sign == 1) PullbackDirection.Long else PullbackDirection.Short)!!
            assertEquals(PullbackStrength.Strong, c.strength); assertEquals(hit, c.highlighted)
        }
        val c = project(fixture(listOf(1.8, 0.6, 0.5, 0.3, -2.0))).rows.single().long!!
        assertEquals(PullbackStrength.Ordinary, c.strength); assertFalse(c.highlighted)
    }
    @Test fun tenLevelRunWatchesFixedFifthInsteadOfChasingSmallestTouch() {
        val p = project(fixture(List(10) { 0.5 } + 0.0))
        assertEquals("7", p.rows.single().long!!.target.interval)
        assertFalse(p.rows.single().hasCandidate)
        assertNull(project(fixture(listOf(0.8, 0.6, 0.5, 0.3, 0.0)), PullbackRules(minInterval = "2")).rows.single().long)
    }
    @Test fun missingFilteredDuplicatesAndInvalidEvidenceNeverBridgeLevels() {
        val original = fixture(listOf(1.8, 1.4, 0.6, 0.3, 0.0))
        for (availability in listOf("filtered", "stale", "missing", "invalid")) {
            val p = project(change(original) { row -> row.copy(perIntervalQuality = row.perIntervalQuality.mapIndexed { i, e ->
                if (i == 2) e.copy(availability = availability) else e }) })
            assertNull(p.rows.single().long); assertNotNull(p.rows.single().issue)
        }
        assertNull(project(change(original) { it.copy(perIntervalQuality = it.perIntervalQuality.filterIndexed { i, _ -> i != 2 }, missingIntervals = listOf("3")) }).rows.single().long)
        assertNull(project(change(original) { it.copy(perIntervalQuality = it.perIntervalQuality + it.perIntervalQuality[2]) }).rows.single().long)
        assertNull(project(change(original) { it.copy(perIntervalQuality = it.perIntervalQuality.mapIndexed { i, e -> if (i == 2) e.copy(value = 99) else e }) }).rows.single().long)
        assertNull(project(change(original) { it.copy(perIntervalQuality = it.perIntervalQuality + ServerIntervalEvidence("nonsense", "ready")) }).rows.single().long)
    }
    @Test fun serverExpiryBootClockMetadataAndSubsetDoNotProduceCandidates() {
        val s = fixture(listOf(0.8, 0.6, 0.5, 0.3, 0.0))
        assertTrue(project(s, elapsed = 15000).ready)
        assertFalse(project(s, elapsed = 15001).ready)
        assertFalse(project(s.copy(fullUniverse = false)).ready)
        assertFalse(project(s.copy(response = s.response.copy(qualityConfig = null))).ready)
        assertFalse(project(s.copy(response = s.response.copy(evaluationMode = "closed"))).ready)
        assertFalse(projectPullbacks(s, PullbackRules(), emptyList(), device.copy(bootCount = 2)).ready)
        assertEquals(now + 15001, project(s).nextChangeAt)
        assertTrue(project(s.copy(response = s.response.copy(status = "degraded"))).rows.single().hasCandidate)
    }
    @Test fun candidateSoundIsBatchedDeduplicatedAndUnknownDoesNotRearm() {
        val s = fixture(listOf(1.8, 1.4, 0.6, 0.3, 0.0))
        val p = project(s)
        val alerts = PullbackAlerts()
        assertFalse(alerts.observe(p)); assertFalse(alerts.observe(p))
        assertFalse(alerts.observe(p.copy(ready = false, rows = emptyList())))
        assertFalse(alerts.observe(p.copy(rows = listOf(PullbackRow("BTCUSDT", issue = "行情过期")))))
        assertFalse(alerts.observe(p))
        val clear = p.copy(rows = p.rows.map { it.copy(long = it.long!!.copy(highlighted = false)) })
        assertFalse(alerts.observe(clear)); assertTrue(alerts.observe(p)); assertFalse(alerts.observe(p))
        val multiple = p.copy(rows = p.rows + p.rows.single().copy(symbol = "ETHUSDT") + p.rows.single().copy(symbol = "SOLUSDT"))
        assertTrue(alerts.observe(multiple)); assertFalse(alerts.observe(multiple))
        alerts.reset(); assertFalse(alerts.observe(multiple))
        assertEquals(pullbackPeriodMillis("D"), pullbackPeriodMillis("1D"))
        assertNull(pullbackPeriodMillis("9999999999999999999D")); assertNotNull(PullbackRules(strongLevels = 5).error())
    }
}
