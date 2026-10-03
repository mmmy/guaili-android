package com.gouge.guaili.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.GuailiTable
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.GuailiSettings
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidgetConfigObservationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun serverSignalModePersistsTenSymbolsAndKindSelectionWithoutIntervals() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val editorStore = WidgetConfigStore(dataStore)
        val settings = GuailiSettings.defaults().copy(symbols = (1..12).map { "S$it" })
        val draft = WidgetConfig(
            symbols = settings.symbols + "S1",
            intervals = emptyList(),
            mode = WidgetMode.SignalsV2,
            enabledSignalKinds = setOf(GuailiSignalKind.Extreme, GuailiSignalKind.Conflict),
        )

        val saved = editorStore.save(21, draft)
        val restored = WidgetConfigStore(dataStore).read(21, settings)

        assertEquals(WidgetMode.SignalsV2, restored.mode)
        assertEquals(settings.symbols.take(10), restored.symbols)
        assertEquals(emptyList<String>(), restored.intervals)
        assertEquals(draft.enabledSignalKinds, restored.enabledSignalKinds)
        assertEquals(saved, restored)
    }

    @Test fun serverSignalModePreservesExplicitlyDisabledKinds() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = WidgetConfigStore(dataStore)
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT"))
        store.save(22, WidgetConfig(
            symbols = settings.symbols,
            intervals = emptyList(),
            mode = WidgetMode.SignalsV2,
            enabledSignalKinds = emptySet(),
        ))

        assertEquals(emptySet<GuailiSignalKind>(), store.read(22, settings).enabledSignalKinds)
    }

    @Test fun originalSavedSignalModeIsNotMigratedToServerMode() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT", "ETHUSDT"))
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("widget_23_mode")] = "Signals"
            preferences[stringPreferencesKey("widget_23_symbols")] = "ETHUSDT,BTCUSDT"
            preferences[stringPreferencesKey("widget_23_signal_kinds")] = "Extreme"
        }

        val restored = WidgetConfigStore(dataStore).read(23, settings)

        assertEquals(WidgetMode.Signals, restored.mode)
        assertEquals(listOf("ETHUSDT", "BTCUSDT"), restored.symbols)
        assertEquals(setOf(GuailiSignalKind.Extreme), restored.enabledSignalKinds)
    }

    @Test fun unknownSavedModeFallsBackToOriginalSignalModeAndKeepsSavedTargets() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT"))
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("widget_24_mode")] = "FutureUnknownMode"
            preferences[stringPreferencesKey("widget_24_symbols")] = "OLDUSDT,BTCUSDT"
        }

        val restored = WidgetConfigStore(dataStore).read(24, settings)

        assertEquals(WidgetMode.Signals, restored.mode)
        assertEquals(listOf("OLDUSDT", "BTCUSDT"), restored.symbols)
        assertEquals(DefaultWidgetSignalKinds, restored.enabledSignalKinds)
    }

    @Test fun unconfiguredWidgetStillUsesOriginalSignalDefaults() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val settings = GuailiSettings.defaults().copy(symbols = (1..12).map { "S$it" })

        val restored = WidgetConfigStore(dataStore).read(25, settings)

        assertEquals(WidgetMode.Signals, restored.mode)
        assertEquals(settings.symbols.take(10), restored.symbols)
        assertEquals(DefaultWidgetSignalKinds, restored.enabledSignalKinds)
    }

    @Test fun serverModeChangesAreObservedWithoutShrinkingTheSelectionToMatrixLimits() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = WidgetConfigStore(dataStore)
        val settings = MutableStateFlow(GuailiSettings.defaults().copy(symbols = (1..10).map { "S$it" }))
        val oldConfig = WidgetConfig(settings.value.symbols, emptyList(), WidgetMode.Signals)
        store.save(26, oldConfig)

        store.observe(26, settings).test {
            assertEquals(WidgetMode.Signals, awaitItem().config.mode)
            store.save(26, oldConfig.copy(mode = WidgetMode.SignalsV2))
            val serverConfig = awaitItem().config
            assertEquals(WidgetMode.SignalsV2, serverConfig.mode)
            assertEquals(settings.value.symbols, serverConfig.symbols)

            settings.value = settings.value.copy(symbols = settings.value.symbols.take(5))
            val updatedSettings = awaitItem()
            assertEquals(serverConfig, updatedSettings.config)
            assertEquals(10, updatedSettings.config.symbols.size)
        }
    }

    @Test fun savedRemovalUpdatesActiveWidgetWithoutRefreshingOrReplacingItsSnapshot() = runTest {
        val file = temporaryFolder.newFolder().resolve("config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val widgetStore = WidgetConfigStore(dataStore)
        val editorStore = WidgetConfigStore(dataStore)
        val settings = MutableStateFlow(GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT", "SKHYNIXUSDT"), intervals = listOf("1"),
        ))
        val now = Instant.parse("2026-09-12T16:00:00Z").toEpochMilli()
        fun cell(symbol: String, age: Long) = GuailiCell(
            symbol, "1", 12, 1.2, 100.0, 1.0, 50.0, true, false, false, true,
            null, Instant.ofEpochMilli(now - age).toString(),
        )
        val cells = mapOf("BTCUSDT" to mapOf("1" to cell("BTCUSDT", 30_000)),
            "SKHYNIXUSDT" to mapOf("1" to cell("SKHYNIXUSDT", 600_000)))
        val snapshot = GuailiSnapshot(GuailiTable(settings.value.symbols, listOf("1"), cells, cells), now)
        val original = WidgetConfig(settings.value.symbols, listOf("1"), WidgetMode.Signals)
        editorStore.save(17, original)
        widgetStore.observe(17, settings).test {
            val initial = awaitItem()
            assertEquals("部分行情过期：SKHYNIX（1m）", widgetDataStatus(snapshot, initial.config.symbols, now).warning)
            editorStore.save(17, original.copy(symbols = listOf("BTCUSDT")))
            val removed = awaitItem()
            assertEquals(listOf("BTCUSDT"), removed.config.symbols)
            assertNull(widgetDataStatus(snapshot, removed.config.symbols, now).warning)
            assertEquals(1, widgetDataStatus(snapshot, removed.config.symbols, now).expectedCells)

            editorStore.save(14, original)
            expectNoEvents()
            settings.value = settings.value.copy(symbols = listOf("BTCUSDT"), intervals = listOf("1", "5"))
            val changedSettings = awaitItem()
            assertEquals(settings.value, changedSettings.settings)
            assertEquals(removed.config, changedSettings.config)

            editorStore.save(17, removed.config.copy(mode = WidgetMode.Matrix, intervals = listOf("5")))
            val changedMode = awaitItem()
            assertEquals(WidgetMode.Matrix, changedMode.config.mode)
            assertEquals(listOf("5"), changedMode.config.intervals)
        }
    }
}
