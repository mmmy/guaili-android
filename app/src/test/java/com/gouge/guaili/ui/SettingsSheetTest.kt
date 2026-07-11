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
        assertEquals(original.symbolDisplayMode, updated.symbolDisplayMode)
        assertEquals(original.symbolColumnWidthMode, updated.symbolColumnWidthMode)
        assertEquals(original.tableDensity, updated.tableDensity)
        assertEquals(original.layoutMode, updated.layoutMode)
        assertEquals(original.groupLayoutSize, updated.groupLayoutSize)
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

    @Test
    fun completeFormUpdatesAdvancedValues() {
        val result = buildSettingsFromValues(
            current = GuailiSettings.defaults(),
            baseUrl = "https://guaili.local",
            symbols = listOf(" btcusdt ", "BTCUSDT", "ethusdt"),
            symbolDisplayMode = com.gouge.guaili.settings.SymbolDisplayMode.Base,
            symbolColumnWidthMode = com.gouge.guaili.settings.SymbolColumnWidthMode.Compact,
            tableDensity = com.gouge.guaili.settings.TableDensity.Comfortable,
            intervals = listOf("1", "d"),
            autoRefreshSeconds = "10",
            calcLimit = "800",
            closedOnly = true,
            maLength = "34",
            maType = "SMA",
            atrLen = "7",
            atrPercentLen = "50",
            maxAtrRank = "85.5",
            slopeMul = "0.25",
            useSlope = false,
        )

        val settings = (result as SettingsFormResult.Valid).settings
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), settings.symbols)
        assertEquals(com.gouge.guaili.settings.SymbolDisplayMode.Base, settings.symbolDisplayMode)
        assertEquals(
            com.gouge.guaili.settings.SymbolColumnWidthMode.Compact,
            settings.symbolColumnWidthMode,
        )
        assertEquals(com.gouge.guaili.settings.TableDensity.Comfortable, settings.tableDensity)
        assertEquals(listOf("1", "D"), settings.intervals)
        assertEquals(10, settings.autoRefreshSeconds)
        assertEquals(800, settings.calcLimit)
        assertTrue(settings.closedOnly)
        assertEquals(34, settings.maLength)
        assertEquals("SMA", settings.maType)
        assertEquals(7, settings.atrLen)
        assertEquals(50, settings.atrPercentLen)
        assertEquals(85.5, settings.maxAtrRank, 0.0)
        assertEquals(0.25, settings.slopeMul, 0.0)
        assertEquals(false, settings.useSlope)
    }

    @Test
    fun completeFormReportsNumericFieldErrors() {
        val result = buildSettingsFromValues(
            current = GuailiSettings.defaults(),
            baseUrl = "https://guaili.local",
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
            autoRefreshSeconds = "0",
            calcLimit = "",
            closedOnly = false,
            maLength = "0",
            maType = "EMA",
            atrLen = "0",
            atrPercentLen = "0",
            maxAtrRank = "101",
            slopeMul = "",
            useSlope = true,
        )

        val errors = (result as SettingsFormResult.Invalid).fieldErrors
        assertTrue("autoRefreshSeconds" in errors)
        assertTrue("calcLimit" in errors)
        assertTrue("maLength" in errors)
        assertTrue("atrLen" in errors)
        assertTrue("atrPercentLen" in errors)
        assertTrue("maxAtrRank" in errors)
        assertTrue("slopeMul" in errors)
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
