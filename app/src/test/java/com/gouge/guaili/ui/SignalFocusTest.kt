package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.MarketSignalPreferences
import com.gouge.guaili.signals.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SignalFocusTest {
    private fun item(id: String, kind: String, periods: List<String>) = ServerSignalItem("QQQUSDT",
        ServerSignalStructure(id, kind, if (kind == "compression") "neutral" else "positive",
            listOf(ServerSignalRun(if (kind == "compression") "neutral" else "positive", periods)), periods.size, periods.size, periods.last()))
    private fun state(items: List<ServerSignalItem>, evidence: List<ServerIntervalEvidence>, status: String = "ready"): MarketSignalsUiState {
        val response = ServerSignalsResponse(true, "ready", 100, 100,
            results = listOf(ServerSymbolSignals("QQQUSDT", "ready", 100,
                signals = items.map { it.signal }, perIntervalQuality = evidence)))
        return MarketSignalsUiState(symbols = listOf("QQQUSDT"), snapshot = ServerSignalsSnapshot(response, 100, "http://server"),
            presentation = ServerSignalsState(status, "实时行情已过期，暂无法判断信号", "", items))
    }

    @Test fun eachVisibleStructureKeepsOnlyItsMembersAndUsesSameSnapshotEvidence() {
        val extreme = item("above", "extreme", listOf("60", "120"))
        val near = item("near", "compression", listOf("20", "30", "60"))
        val evidence = listOf("W", "120", "60", "30", "20").map {
            ServerIntervalEvidence(it, "ready", value = if (it == "20") 1 else 14,
                guaili = if (it == "20") 0.12 else 1.42, ma = 50.5,
                longTrend = false, shortTrend = true, openTime = 0, closeTime = 60_000, isClosed = false)
        }
        val source = state(listOf(near, extreme), evidence)
        val groups = signalFocusGroups(source, listOf("XAUUSDT", "QQQUSDT"))
        assertEquals(listOf("QQQUSDT"), groups.map { it.symbol })
        assertEquals(2, groups.single().rows.size)
        assertEquals(listOf("above", "near"), groups.single().rows.map { it.item.signal.id })
        assertEquals(listOf("120", "60"), groups.single().rows[0].members.map { it.cell.interval })
        assertEquals(listOf("60", "30", "20"), groups.single().rows[1].members.map { it.cell.interval })
        assertEquals(groups, signalFocusGroups(source.copy(presentation = source.presentation.copy(signals = listOf(extreme, near))), listOf("XAUUSDT", "QQQUSDT")))
        val cell = groups.single().rows[1].members.last().cell
        assertEquals(1, cell.value)
        assertEquals(0.12, cell.guaili!!, 0.0)
        assertEquals(false, cell.isClosed)
        assertEquals(true, cell.shortTrend)
        assertEquals("1970-01-01T00:00:00Z", cell.openTime)
        // A replacement response updates values; neither ordinary table values nor old cells are retained.
        val snapshot = source.snapshot!!
        val latestRow = snapshot.response.results.single().copy(perIntervalQuality = evidence.map { it.copy(value = 2) })
        val latest = source.copy(snapshot = snapshot.copy(response = snapshot.response.copy(results = listOf(latestRow))))
        assertEquals(2, signalFocusGroups(latest, listOf("QQQUSDT")).single().rows[1].members.last().cell.value)
    }

    @Test fun conflictRemainsOneRowWithBothPartsAndDuplicatePeriodsAcrossRowsRemainVisible() {
        val conflict = ServerSignalItem("QQQUSDT", ServerSignalStructure("conflict", "conflict", "negative",
            listOf(ServerSignalRun("negative", listOf("1", "5")), ServerSignalRun("positive", listOf("60", "120"))), 2, 4, "120"))
        val source = state(listOf(conflict), listOf("1", "5", "60", "120").map { ServerIntervalEvidence(it, "ready") })
        val row = signalFocusGroups(source, listOf("QQQUSDT")).single().rows.single()
        assertEquals(listOf("120", "60", "5", "1"), row.members.map { it.cell.interval })
        assertEquals(listOf("长+", "长+", "短−", "短−"), row.members.map { it.part })
        assertNull(row.members.first().cell.value)
    }

    @Test fun invalidEvidenceNeverProducesPartialOrExpiredSignalRows() {
        val item = item("above", "extreme", listOf("1", "5"))
        val source = state(listOf(item), listOf(ServerIntervalEvidence("1", "ready"), ServerIntervalEvidence("5", "stale")))
        assertTrue(signalFocusGroups(source, listOf("QQQUSDT")).isEmpty())
        val snapshot = source.snapshot!!
        val readyRow = snapshot.response.results.single().copy(perIntervalQuality = listOf(
            ServerIntervalEvidence("1", "ready"), ServerIntervalEvidence("5", "ready")))
        val ready = source.copy(snapshot = snapshot.copy(response = snapshot.response.copy(results = listOf(readyRow))))
        assertEquals(1, signalFocusGroups(ready, listOf("QQQUSDT")).size)
        for (status in listOf("stale", "disabled", "warming_up", "time_uncertain")) {
            assertTrue(signalFocusGroups(ready.copy(presentation = ready.presentation.copy(status = status)), listOf("QQQUSDT")).isEmpty())
        }
    }

    @Test fun oldPreferencesDefaultToFullTableAndFocusPreferenceRoundTrips() {
        val old = Json.decodeFromString<MarketSignalPreferences>("""{"view":"Table","symbols":["QQQUSDT"]}""")
        assertFalse(old.onlySignalCells)
        assertEquals(old.copy(onlySignalCells = true), Json.decodeFromString<MarketSignalPreferences>(Json.encodeToString(old.copy(onlySignalCells = true))))
        val table = GuailiTableState(isLoading = false, lastUpdatedAt = 100)
        val source = state(emptyList(), emptyList())
        assertEquals("Live", marketDataStatusLabel(table, source, true))
        assertEquals("已过期", marketDataStatusLabel(table, source.copy(presentation = source.presentation.copy(status = "stale")), true))
        assertEquals("Stale", marketDataStatusLabel(table.copy(isStale = true, isRefreshing = true), source, false))
    }
}
