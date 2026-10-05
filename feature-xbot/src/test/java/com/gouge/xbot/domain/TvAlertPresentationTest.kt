package com.gouge.xbot.domain

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TvAlertPresentationTest {
    @Test
    fun `extracts ticker from TradingView symbol payload`() {
        val alert = alert(
            symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\",\"adjustment\":\"splits\"}",
        )

        assertEquals("BINANCE:BTCUSDT.P", alert.tickerId())
        assertEquals("BTCUSDT.P", alert.tickerLabel())
    }

    @Test
    fun `malformed symbol payload has safe fallback`() {
        val alert = alert(symbol = "not-json")

        assertEquals("", alert.tickerId())
        assertEquals("-", alert.tickerLabel())
    }

    @Test
    fun `normalizes full TradingView ticker`() {
        assertEquals(
            "NASDAQ:AAPL",
            normalizeTradingViewTicker(" nasdaq:aapl "),
        )
    }

    @Test
    fun `adds Binance to ticker shorthand`() {
        assertEquals(
            "BINANCE:ETHUSDT.P",
            normalizeTradingViewTicker("ethusdt.p"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects multiple tickers`() {
        normalizeTradingViewTicker("BTCUSDT.P ETHUSDT.P")
    }

    @Test
    fun `matches alerts by configured name prefix`() {
        val config = TvAlertConfigDto(id = "1", namePre = "_MA_")

        assertTrue(config.matches(alert(name = "_MA_BTC")))
        assertFalse(config.matches(alert(name = "_ATR_BTC")))
    }

    private fun alert(
        symbol: String = "",
        name: String = "",
    ) = TvAlertDto(
        alertId = 1,
        symbol = symbol,
        name = name,
    )
}
