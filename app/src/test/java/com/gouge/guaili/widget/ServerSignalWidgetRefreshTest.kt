package com.gouge.guaili.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSignalWidgetRefreshTest {
    @Test fun allV2WidgetsShareTheirCombinedSelectionWithoutLegacySymbols() {
        val configurations = listOf(
            WidgetConfig(listOf("BTCUSDT", "XAUUSDT"), emptyList(), WidgetMode.SignalsV2),
            WidgetConfig(listOf("SOXLUSDT", "BTCUSDT"), emptyList(), WidgetMode.SignalsV2),
            WidgetConfig(listOf("LEGACYUSDT"), emptyList(), WidgetMode.Signals),
            WidgetConfig(listOf("MATRIXUSDT"), emptyList(), WidgetMode.Matrix),
        )
        assertEquals(listOf("BTCUSDT", "XAUUSDT", "SOXLUSDT"), serverSignalQuerySymbols(configurations))
    }

    @Test fun sharedQueryIsNotLimitedToOneWidgetsTenSymbols() {
        val first = (1..10).map { "ASSET${it}USDT" }
        val second = (6..15).map { "ASSET${it}USDT" }
        assertEquals((1..15).map { "ASSET${it}USDT" }, serverSignalQuerySymbols(listOf(
            WidgetConfig(first, emptyList(), WidgetMode.SignalsV2),
            WidgetConfig(second, emptyList(), WidgetMode.SignalsV2),
        )))
    }

    @Test fun noV2WidgetsMeansNoServerSymbolsToQuery() {
        assertEquals(emptyList<String>(), serverSignalQuerySymbols(listOf(
            WidgetConfig(listOf("BTCUSDT"), emptyList(), WidgetMode.Signals),
        )))
    }
}
