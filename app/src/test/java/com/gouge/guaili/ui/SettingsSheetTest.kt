package com.gouge.guaili.ui

import com.gouge.guaili.settings.GuailiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSheetTest {
    @Test
    fun validFieldsBuildSettingsAndKeepAdvancedValues() {
        val original = GuailiSettings.defaults().copy(
            calcLimit = 900,
            closedOnly = true,
            maLength = 34,
            maType = "SMA",
            atrLen = 8,
            atrPercentLen = 55,
            maxAtrRank = 88.0,
            slopeMul = 0.25,
            useSlope = false,
        )

        val result = buildSettingsFromFields(
            current = original,
            baseUrl = "http://10.0.2.2:8080",
            symbols = " BTCUSDT, , ETHUSDT ",
            intervals = " 1, 5, 60 ",
            autoRefreshSeconds = "0",
        )

        val updated = (result as SettingsFormResult.Valid).settings
        assertEquals("http://10.0.2.2:8080", updated.baseUrl)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), updated.symbols)
        assertEquals(listOf("1", "5", "60"), updated.intervals)
        assertEquals(1, updated.autoRefreshSeconds)
        assertEquals(original.limit, updated.limit)
        assertEquals(original.calcLimit, updated.calcLimit)
        assertEquals(original.closedOnly, updated.closedOnly)
        assertEquals(original.maLength, updated.maLength)
        assertEquals(original.maType, updated.maType)
        assertEquals(original.atrLen, updated.atrLen)
        assertEquals(original.atrPercentLen, updated.atrPercentLen)
        assertEquals(original.maxAtrRank, updated.maxAtrRank, 0.0)
        assertEquals(original.slopeMul, updated.slopeMul, 0.0)
        assertEquals(original.useSlope, updated.useSlope)
    }

    @Test
    fun invalidOrBlankBaseUrlIsRejected() {
        assertBaseUrlError("")
        assertBaseUrlError("guaili.local:8080")
        assertBaseUrlError("ftp://guaili.local")
    }

    @Test
    fun emptySymbolsAreRejected() {
        val result = buildSettingsFromFields(
            current = GuailiSettings.defaults(),
            baseUrl = "https://guaili.local",
            symbols = " , ",
            intervals = "1,5",
            autoRefreshSeconds = "5",
        )

        val errors = (result as SettingsFormResult.Invalid).errors
        assertTrue(errors.any { it.contains("symbol", ignoreCase = true) })
    }

    @Test
    fun emptyIntervalsAreRejected() {
        val result = buildSettingsFromFields(
            current = GuailiSettings.defaults(),
            baseUrl = "https://guaili.local",
            symbols = "BTCUSDT",
            intervals = " , ",
            autoRefreshSeconds = "5",
        )

        val errors = (result as SettingsFormResult.Invalid).errors
        assertTrue(errors.any { it.contains("interval", ignoreCase = true) })
    }

    private fun assertBaseUrlError(baseUrl: String) {
        val result = buildSettingsFromFields(
            current = GuailiSettings.defaults(),
            baseUrl = baseUrl,
            symbols = "BTCUSDT",
            intervals = "1,5",
            autoRefreshSeconds = "5",
        )

        val errors = (result as SettingsFormResult.Invalid).errors
        assertTrue(errors.any { it.contains("URL", ignoreCase = true) })
    }
}
