package com.gouge.guaili.ui

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import com.gouge.xbot.ui.MainUiState
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MarketTvAlertsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val config = TvAlertConfigDto("a", cookieId = "account-a", namePre = "_A_",
        startTimeParamIndex = 0, validBarsParamIndex = 1,
        params = listOf(TvAlertParamDto("0", "0"), TvAlertParamDto("1", "1")))
    private fun alert(id: Long, ticker: String = "BINANCE:BTCUSDT.P", interval: String = "60", active: Boolean = true) =
        TvAlertDto(active, "={\"symbol\":\"$ticker\"}", "_A_$id", id, interval, now.minusSeconds(600).toString())
    private fun state(alerts: List<TvAlertDto>, configs: List<TvAlertConfigDto> = listOf(config)) = MainUiState(
        "test", true, alertConfigs = configs, visibleAlertIds = configs.mapTo(hashSetOf()) { it.id },
        tvAlertsByCookieId = mapOf("account-a" to alerts), hasLoadedAlerts = true)

    @Test fun exactTickerMatchingPreservesExchangeAndContractAndSupportsExplicitAliases() {
        val snapshot = state(listOf(alert(1), alert(2, "BINANCE:BTCUSDT"), alert(3, "BYBIT:BTCUSDT.P"),
            alert(4, "OANDA:XAUUSD"), alert(5).copy(symbol = "invalid")))
        assertEquals(listOf(1L), marketTvEntries(snapshot, defaultMarketTvTicker("BTCUSDT")).map { it.alert.alertId })
        val mapped = marketTvSummaries(snapshot, listOf("XAUUSDT"), mapOf("XAUUSDT" to "OANDA:XAUUSD"), now)
        assertEquals(1, mapped.getValue("XAUUSDT").summary.total)
        assertTrue(marketTvSummaries(snapshot, listOf("XAUUSDT"), emptyMap(), now).isEmpty())
    }

    @Test fun accountsHaveIndependentIdsAndHiddenConfigurationsNeverLeakFromCache() {
        val second = config.copy(id = "b", cookieId = "account-b")
        val hidden = config.copy(id = "hidden", cookieId = "account-hidden")
        val snapshot = state(listOf(alert(1), alert(1)), listOf(config, second, hidden)).copy(
            visibleAlertIds = setOf("a", "b"), tvAlertsByCookieId = mapOf(
                "account-a" to listOf(alert(1), alert(1)), "account-b" to listOf(alert(1)), "account-hidden" to listOf(alert(2))))
        val entries = marketTvEntries(snapshot, "BINANCE:BTCUSDT.P")
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.key }.distinct().size)
        assertTrue(marketTvEntries(snapshot.copy(isAuthenticated = false), "BINANCE:BTCUSDT.P").isEmpty())
        assertTrue(marketTvEntries(snapshot.copy(visibleAlertIds = emptySet()), "BINANCE:BTCUSDT.P").isEmpty())
    }

    @Test fun overlappingPrefixesChooseTheSpecificConfigurationOnce() {
        val specific = config.copy(id = "specific", namePre = "_A_special_")
        val snapshot = state(listOf(alert(1).copy(name = "_A_special_alert")), listOf(config, specific))
        assertEquals(listOf("specific"), marketTvEntries(snapshot, "BINANCE:BTCUSDT.P").map { it.config.id })
    }

    @Test fun dailyAndWeeklyAliasesMatchButEqualDurationMinuteBarsStaySeparate() {
        val snapshot = state(listOf(alert(1, interval = "D"), alert(2, interval = "1D"),
            alert(3, interval = "W"), alert(4, interval = "1W"), alert(5, interval = "1440"),
            alert(6, interval = "garbage")))
        val summary = marketTvSummaries(snapshot, listOf("BTCUSDT"), emptyMap(), now).getValue("BTCUSDT")
        assertEquals(6, summary.summary.total) // Unknown resolution remains visible in the symbol list.
        assertEquals(2, summary.period("D")?.total)
        assertEquals(2, summary.period("1W")?.total)
        assertEquals(1, summary.period("1440")?.total)
        assertEquals(3, summary.periods.size)
        assertNull(summary.period("bad"))
    }

    @Test fun enabledExpiredStoppedAndUnconfiguredAreCountedIndependentlyAndExpiryTicksLocally() {
        val snapshot = state(listOf(alert(1), alert(2).copy(createTime = now.minusSeconds(3600).toString()),
            alert(3, active = false), alert(4).copy(createTime = "")))
        val entries = marketTvEntries(snapshot, "BINANCE:BTCUSDT.P")
        assertEquals(PeriodTvAlerts(4, 1, 1, 1, 1), summarizeTvEntries(entries, now))
        assertEquals(PeriodTvAlerts(4, 0, 3, 1, 1), summarizeTvEntries(entries, now.plusSeconds(3600)))
    }

    @Test fun syncFailureKeepsCachedCountsAndSuccessfulDeletionClearsThem() {
        val snapshot = state(listOf(alert(1)))
        val failed = snapshot.copy(alertErrorMessage = "offline")
        assertEquals(1, marketTvSummaries(failed, listOf("BTCUSDT"), emptyMap(), now).getValue("BTCUSDT").summary.total)
        assertTrue(marketTvSyncLabel(failed).contains("同步失败"))
        assertTrue(marketTvSummaries(snapshot.copy(tvAlertsByCookieId = emptyMap()), listOf("BTCUSDT"), emptyMap(), now).isEmpty())
        assertEquals("TV 尚未同步", marketTvSyncLabel(snapshot.copy(hasLoadedAlerts = false)))
        assertEquals("TV 未登录", marketTvSyncLabel(snapshot.copy(isAuthenticated = false)))
    }
}
