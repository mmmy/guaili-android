package com.gouge.guaili.pullback

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.PullbackRules
import org.junit.Assert.*
import org.junit.Test

class PullbackProjectionTest {
    @Test fun fixedEntryPeriodsMatchDesktopAndChooseNearestStrictlySmaller() {
        assertEquals(28, MarketPeriods.all.size)
        MarketPeriods.all.forEach { assertEquals(it.durationMillis, pullbackPeriodMillis(it.interval)) }
        assertTrue(MarketPeriods.all.zipWithNext().all { (a, b) -> a.durationMillis < b.durationMillis })
        fun entry(period: String, divisor: Int = 20) = MarketPeriods.nearestSmaller(pullbackPeriodMillis(period)!!, divisor)
        assertEquals("120", entry("2D")); assertEquals("180", entry("2D", 16))
        assertEquals("120", entry("2D", 24)); assertEquals("2", entry("5", 2))
        assertEquals("10S", entry("15S")); assertNull(entry("10S"))
        assertNull(entry("2D", 0)); assertNull(entry("2D", 1001))
    }
    @Test fun projectionCachesRecommendedPeriodIndependentOfAvailableSmallEvidence() {
        val base = fixture(listOf(1.8, 1.4, 0.6, 0.3, 0.0))
        val source = change(base) { row -> row.copy(perIntervalQuality =
            row.perIntervalQuality.zip(listOf("10D", "W", "4D", "3D", "2D")).map { (e, period) -> e.copy(interval = period) }) }
        assertEquals("120", project(source).rows.single().long!!.entryInterval)
        assertEquals("180", project(source, PullbackRules(entryDivisor = 16)).rows.single().long!!.entryInterval)
    }
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
    @Test fun phoneKeepsEvidenceAcrossThirtyAndSixtySecondPollingCyclesThenTimesOut() {
        val s = fixture(listOf(0.8, 0.6, 0.5, 0.3, 0.0))
        for (interval in listOf(30_000L, 60_000L)) {
            fun at(elapsed: Long) = projectPullbacks(s, PullbackRules(), listOf("BTCUSDT"),
                device.copy(elapsedMillis = device.elapsedMillis + elapsed), interval)
            for (elapsed in listOf(16_000L, interval, interval + 12_000)) {
                val p = at(elapsed)
                assertTrue(p.ready)
                assertTrue("Polling interval must also preserve per-period market evidence", p.rows.single().hasCandidate)
            }
            assertEquals(now + interval + 12_001, at(0).nextChangeAt)
            assertEquals("手机刷新超时，请检查连接后刷新", at(interval + 12_001).message)
            assertFalse(at(interval + 12_001).ready)
        }
    }
    @Test fun longPhoneIntervalNeverMakesAlreadyStaleServerEvidenceValid() {
        val s = fixture(listOf(0.8, 0.6, 0.5, 0.3, 0.0))
        fun phone(snapshot: ServerSignalsSnapshot) = projectPullbacks(snapshot, PullbackRules(),
            listOf("BTCUSDT"), device, 60_000)
        assertEquals("后端采样停滞，请检查后端服务",
            phone(s.copy(response = s.response.copy(evaluatedAt = now - 15_001))).message)
        assertEquals("后端采样时间异常，请刷新",
            phone(s.copy(response = s.response.copy(evaluatedAt = now + 2_001))).message)
        assertFalse(phone(s.copy(response = s.response.copy(evaluatedAt = null))).ready)
        val staleSymbol = phone(change(s) { it.copy(sampledAt = now - 15_001) }).rows.single()
        assertEquals("品种采样异常或停滞", staleSymbol.issue)
        assertFalse(staleSymbol.hasCandidate)
        val staleMarket = phone(change(s) { row -> row.copy(perIntervalQuality = row.perIntervalQuality.map {
            it.copy(marketEventTime = now - 30_001)
        }) }).rows.single()
        assertFalse(staleMarket.hasCandidate)
        assertNotNull(staleMarket.issue)
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
