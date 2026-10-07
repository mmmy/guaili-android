package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.*
import com.gouge.guaili.signals.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MarketLinkTest {
    private val key = MarketLinkKey("BTCUSDT", "same-id")
    private fun item(periods: List<String>) = ServerSignalItem(key.symbol,
        ServerSignalStructure(key.id, "extreme", "positive", listOf(ServerSignalRun("positive", periods)),
            periods.size, periods.size, periods.last()))
    private fun state(item: ServerSignalItem? = item(listOf("1", "2", "3", "5", "8")), status: String = "ready") =
        MarketSignalsUiState(symbols = listOf(key.symbol), presentation = ServerSignalsState(status, "数据过期", "", listOfNotNull(item)))

    @Test fun sameIdentityUsesLatestCoverageAndDropsUnavailableMembers() {
        val original = resolveLiveMarketLink(key, state())!!
        val expanded = resolveLiveMarketLink(key, state(item(listOf("1", "2", "3", "5", "8", "10"))))!!
        assertEquals(5, original.members.size)
        assertEquals(6, expanded.members.size)
        assertEquals("共+6级", expanded.message)
        assertTrue(resolveLiveMarketLink(key, state(null, "stale"))!!.members.isEmpty())
        assertEquals("数据过期", resolveLiveMarketLink(key, state(null, "stale"))!!.message)
        assertEquals("所选信号已不在当前结果", resolveLiveMarketLink(key, state(null))!!.message)
    }

    @Test fun conflictsKeepDistinctShortAndLongLabels() {
        val conflict = ServerSignalItem(key.symbol, ServerSignalStructure(key.id, "conflict", "mixed",
            listOf(ServerSignalRun("negative", listOf("1", "2")), ServerSignalRun("positive", listOf("60", "120"))), 4, 4, "120"))
        val link = resolveLiveMarketLink(key, state(conflict))!!
        assertEquals("短−", link.members["1"])
        assertEquals("长+", link.members["120"])
    }

    @Test fun hiddenRangeOnlyRevealsAvailableMembersAndKeepsFilterIntact() {
        val filter = listOf("D")
        assertEquals(filter, linkedVisibleIntervals(filter, listOf("D", "8", "5"), setOf("8", "5", "10"), false))
        assertEquals(listOf("D", "8", "5"), linkedVisibleIntervals(filter, listOf("D", "8", "5"), setOf("8", "5", "10"), true))
        assertEquals(listOf("D"), filter)
    }

    @Test fun summariesDistinguishUnmonitoredAndMissingSymbolsWithoutFilteringTable() {
        val state = state().copy(symbols = listOf(key.symbol, "XAUUSDT"),
            snapshot = ServerSignalsSnapshot(ServerSignalsResponse(true, "ready", 1, results =
                listOf(ServerSymbolSignals(key.symbol, "ready", 1))), 1, "http://server"))
        val summaries = marketSignalSummaries(state, listOf(key.symbol, "XAUUSDT", "QQQUSDT"))
        assertEquals(1, summaries.getValue(key.symbol).items.size)
        assertEquals("服务器未计算", summaries.getValue("XAUUSDT").status)
        assertEquals("未监控", summaries.getValue("QQQUSDT").status)
    }

    @Test fun sessionRestoresIdentityAndBoundedNavigationWithoutMarketResponses() {
        val origin = MarketLinkOrigin(MarketView.SignalsV2, key, true, key.symbol, null, true, "Long", "Groups",
            table = MarketScrollAnchor("BTCUSDT", 2, 13), groups = MarketScrollAnchor(),
            signals = MarketScrollAnchor("BTCUSDT/same-id", 8, 17), pane = MarketScrollAnchor(),
            horizontalInterval = "8", horizontalOffset = 3)
        var session = MarketLinkSession(baseUrl = "http://server", selection = key)
        repeat(12) { session = session.push(origin) }
        assertEquals(8, session.origins.size)
        val encoded = Json.encodeToString(session)
        assertFalse(encoded.contains("perIntervalQuality"))
        assertEquals(session, Json.decodeFromString<MarketLinkSession>(encoded))
    }
}
