package com.gouge.guaili.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuailiSettingsTest {
    @Test
    fun defaultsMatchFirstRelease() {
        val settings = GuailiSettings.defaults()

        assertEquals("http://192.168.1.100:8080/", settings.baseUrl)
        assertEquals(
            listOf(
                "BTCUSDT",
                "XAUUSDT",
                "CLUSDT",
                "QQQUSDT",
                "SKHYNIXUSDT",
            ),
            settings.symbols,
        )
        assertEquals(SymbolDisplayMode.Auto, settings.symbolDisplayMode)
        assertEquals(SymbolColumnWidthMode.Auto, settings.symbolColumnWidthMode)
        assertEquals(TableDensity.Compact, settings.tableDensity)
        assertEquals(LayoutMode.Auto, settings.layoutMode)
        assertEquals(GroupLayoutSize.Standard, settings.groupLayoutSize)
        assertEquals(
            listOf(
                "10D",
                "W",
                "4D",
                "3D",
                "2D",
                "D",
                "720",
                "480",
                "360",
                "240",
                "180",
                "120",
                "90",
                "60",
                "45",
                "30",
                "20",
                "15",
                "10",
                "8",
                "5",
                "3",
                "2",
                "1",
                "45S",
                "30S",
                "15S",
                "10S",
            ),
            settings.intervals,
        )
        assertEquals(5, settings.autoRefreshSeconds)
        assertEquals(1, settings.limit)
        assertEquals(500, settings.calcLimit)
        assertFalse(settings.closedOnly)
        assertEquals(20, settings.maLength)
        assertEquals("EMA", settings.maType)
        assertEquals(1, settings.atrLen)
        assertEquals(20, settings.atrPercentLen)
        assertEquals(100.0, settings.maxAtrRank, 0.0)
        assertEquals(0.1, settings.slopeMul, 0.0)
        assertEquals(true, settings.useSlope)
    }

    @Test
    fun commaListsTrimBlankEntries() {
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), parseCsv(" BTCUSDT, ,ETHUSDT "))
    }

    @Test
    fun legacyDefaultIntervalsMigrateWithoutOverwritingCustomLists() {
        assertEquals(DefaultIntervals, parseIntervalsOrDefault(
            LegacyDefaultIntervals.joinToString(","),
            DefaultIntervals,
        ))
        assertEquals(
            DefaultIntervals,
            parseIntervalsOrDefault(DefaultIntervals.reversed().joinToString(","), DefaultIntervals),
        )
        assertEquals(
            listOf("D", "60", "15S"),
            parseIntervalsOrDefault("D,60,15S", DefaultIntervals),
        )
    }
}
