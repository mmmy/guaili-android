package com.gouge.guaili.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PullbackPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun oldPreferencesPreserveV2SelectionAndGetIndependentPullbackDefaults() {
        val old = json.decodeFromString<MarketSignalPreferences>("""{"view":"SignalsV2","symbols":["BTCUSDT"],"onlySignalCells":true}""")
        assertEquals(MarketView.SignalsV2, old.view)
        assertEquals(listOf("BTCUSDT"), old.symbols)
        assertTrue(old.onlySignalCells); assertFalse(old.pullback.onlyCandidates); assertFalse(old.pullback.soundEnabled)
        assertEquals(PullbackRules(), old.pullback.rules)
    }
    @Test fun allRulesAndSwitchesRoundTripWithoutChangingTableOrV2Preferences() {
        val prefs = MarketSignalPreferences(view = MarketView.Pullback, symbols = listOf("BTCUSDT"), onlySignalCells = true,
            pullback = PullbackPreferences(PullbackRules(7, 20, 3, "15", 2), true, true))
        assertEquals(prefs, json.decodeFromString<MarketSignalPreferences>(json.encodeToString(prefs)))
        assertNull(prefs.pullback.rules.error())
        assertNotNull(PullbackRules(levels = 1).error()); assertNotNull(PullbackRules(reverseLimit = -1).error())
    }
}
