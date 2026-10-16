package com.gouge.guaili.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PullbackWidgetConfigTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun filtersArePerInstanceAndAnUnchangedEditorCannotUndoADirectToggle() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temporary.newFolder().resolve("config.preferences_pb") }
        val store = WidgetConfigStore(data)
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT", "ETHUSDT"))
        val initial = WidgetConfig(settings.symbols, emptyList(), WidgetMode.PullbackObservation)
        store.save(1, initial)
        store.save(2, initial)
        store.togglePullbackCandidates(1)
        assertTrue(store.read(1, settings).pullbackOnlyCandidates)
        assertFalse(store.read(2, settings).pullbackOnlyCandidates)
        val saved = store.save(1, initial.copy(symbols = listOf("ETHUSDT")), baseline = initial)
        assertTrue(saved.pullbackOnlyCandidates)
        assertEquals(listOf("ETHUSDT"), saved.symbols)
        store.delete(1)
        assertFalse(store.read(1, settings).pullbackOnlyCandidates)
    }
    @Test fun pullbacksUseTheSharedServerSourceAndKeepTheirTenSymbolLimit() {
        assertFalse(WidgetMode.PullbackObservation.usesLegacyMarketSnapshot())
        assertTrue(WidgetMode.PullbackObservation.usesServerSignalsSnapshot())
        assertEquals(10, normalizeWidgetSymbols((1..12).map { "S$it" }, WidgetMode.PullbackObservation).size)
    }
}
