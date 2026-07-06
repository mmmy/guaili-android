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
                "HKHYNIXUSDT",
            ),
            settings.symbols,
        )
        assertEquals(
            listOf(
                "1",
                "2",
                "3",
                "5",
                "8",
                "10",
                "15",
                "20",
                "30",
                "45",
                "60",
                "90",
                "120",
                "180",
                "240",
                "360",
                "480",
                "720",
                "D",
                "2D",
                "3D",
                "4D",
                "W",
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
}
