package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.PullbackRules
import org.junit.Assert.*
import org.junit.Test

class PullbackWidgetPresentationTest {
    @Test fun symbolRowIdsStayOutsideGlanceReservedRangeAndDoNotDependOnListOrder() {
        // ADAUSDT and SUIUSDT produced reserved negative IDs and crashed the entire widget.
        val symbols = listOf("BTCUSDT", "ETHUSDT", "ADAUSDT", "SUIUSDT", "SOLUSDT", "BNBUSDT",
            "XRPUSDT", "DOGEUSDT", "LINKUSDT", "XAUUSDT") + (0 until 10_000).map { "SYMBOL${it}USDT" }
        val ids = symbols.associateWith(::stablePullbackRowId)
        assertTrue("All symbol IDs must be in Glance's application-owned range", ids.values.all { it >= 0 })
        assertEquals("Distinct symbols must retain distinct IDs", symbols.size, ids.values.toSet().size)
        assertEquals(ids, symbols.reversed().associateWith(::stablePullbackRowId))
    }

    private val now = 1_000_000L
    private val rules = PullbackRules()
    private val device = GuailiDeviceTime(now, 100_000, 1)
    private fun snapshot(values: List<Double> = listOf(1.8, 1.4, .6, .3, 0.0)) = ServerSignalsSnapshot(
        ServerSignalsResponse(true, "ready", now, now, qualityConfig = ServerSignalQualityConfig(30_000, 15_000),
            results = listOf(ServerSymbolSignals("BTCUSDT", "ready", now, perIntervalQuality =
                values.zip(listOf("10D", "W", "4D", "3D", "2D")).map { (value, interval) ->
                    ServerIntervalEvidence(interval, "ready", value = (value * 10).toInt(), guaili = value,
                        marketEventTime = now, isClosed = false)
                }))), now, "http://one", GuailiServerClock(now, device.elapsedMillis, 1, 10), true)
    private val config = WidgetConfig(listOf("BTCUSDT"), emptyList(), WidgetMode.PullbackObservation)

    @Test fun originalReceiptIsValidatedAndOldCandidatesAreExplicitlyHistorical() {
        val value = snapshot()
        val projector = PullbackWidgetProjector()
        val projection = projector.project(value, rules)
        assertTrue(projection.rows.single().long!!.highlighted)
        assertEquals("120", projection.rows.single().long!!.entryInterval)
        assertSame(projection, projector.project(value, rules))
        assertFalse(pullbackWidgetState(value, null, projection, config, value.baseUrl, device).historical)
        val historical = pullbackWidgetState(value, null, projection, config, value.baseUrl,
            device.copy(elapsedMillis = device.elapsedMillis + 900_000))
        assertTrue(historical.historical)
        assertTrue("Keep the recorded observation, never reclassify it as current", historical.rows.single().hasCandidate)
        assertFalse(pullbackWidgetState(value, null, projection, config, "http://two", device).projection.ready)
        assertTrue(pullbackWidgetState(value, null, projection, config, value.baseUrl, device.copy(bootCount = 2)).historical)
    }

    @Test fun staleOnArrivalAndIncompleteOrUncalibratedSourcesNeverProduceCandidates() {
        val good = snapshot()
        listOf(good.copy(fullUniverse = false), good.copy(serverClock = null),
            good.copy(serverClock = good.serverClock!!.copy(requestDurationMillis = 6_000)),
            good.copy(response = good.response.copy(evaluatedAt = now - 15_001))).forEach {
            assertFalse(PullbackWidgetProjector().project(it, rules).ready)
        }
    }

    @Test fun freezeRetainsTheClickedEvidenceAndRuleVersionAcrossNewObservations() {
        val old = snapshot()
        val projector = PullbackWidgetProjector()
        val candidate = projector.project(old, rules).rows.single().long!!
        val payload = freezePullbackWidgetEvidence("BTCUSDT", candidate, rules, old)
        val encoded = PullbackWidgetJson.encodeToString(PullbackWidgetEvidence.serializer(), payload)
        projector.project(snapshot(listOf(1.8, 1.4, .6, .3, .7)), rules.copy(entryDivisor = 16))
        val restored = PullbackWidgetJson.decodeFromString<PullbackWidgetEvidence>(encoded)
        assertEquals(0.0, restored.candidate().target.guaili!!, 0.0)
        assertEquals("120", restored.entryInterval)
        assertEquals(20, restored.rules.entryDivisor)
        assertEquals(now, restored.sampledAt)
        assertTrue(restored.highlighted)
    }

    @Test fun truncatedZeroMissingDataAndSelectionsStayDistinct() {
        val near = snapshot(listOf(1.8, 1.4, .6, .3, .02))
        val projection = PullbackWidgetProjector().project(near, rules)
        assertFalse(projection.rows.single().hasCandidate)
        val selected = config.copy(symbols = listOf("MISSING", "BTCUSDT"))
        val state = pullbackWidgetState(near, null, projection, selected, near.baseUrl, device)
        assertEquals(listOf("MISSING", "BTCUSDT"), state.rows.map { it.symbol })
        assertEquals("服务器未计算", state.rows.first().issue)
        assertNotNull(state.rows.last().long)
    }
}
