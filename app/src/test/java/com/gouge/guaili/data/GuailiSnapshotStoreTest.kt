package com.gouge.guaili.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.gouge.guaili.domain.GuailiSignalDetector
import com.gouge.guaili.domain.toTable
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.widget.widgetDataStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuailiSnapshotStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun compactSnapshotsPersistEachGridOnceAndRestoreTheSelectedView() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFolder().resolve("compact.preferences_pb")
        }
        val store = GuailiSnapshotStore(dataStore)
        for (closedOnly in listOf(false, true)) {
            val snapshot = sampleSnapshot(closedOnly)
            store.save(snapshot)
            val encoded = dataStore.data.first()[stringPreferencesKey("latest_snapshot")]!!
            val table = Json.parseToJsonElement(encoded).jsonObject.getValue("table").jsonObject
            assertFalse(table.containsKey("cells"))
            assertTrue(table.containsKey("dynamicCells"))
            assertTrue(table.containsKey("closedCells"))
            assertEquals(if (closedOnly) "closed" else "dynamic", table.getValue("displayCells").jsonPrimitive.content)
            val restored = Json.decodeFromString<PersistedGuailiSnapshot>(encoded).restore()
            assertEquals(snapshot, restored)
            assertSame(if (closedOnly) restored.table.closedCells else restored.table.dynamicCells, restored.table.cells)
        }
    }

    @Test fun legacySnapshotsRemainReadableAndSeparateStoresShareOneDecodedInstance() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFolder().resolve("legacy.preferences_pb")
        }
        val first = GuailiSnapshotStore(dataStore)
        val second = GuailiSnapshotStore(dataStore)
        val legacy = sampleSnapshot()
        dataStore.edit { it[stringPreferencesKey("latest_snapshot")] = Json.encodeToString(GuailiSnapshot.serializer(), legacy) }
        val parsed = first.read()!!
        assertEquals(legacy, parsed)
        assertSame(parsed, second.read())
        assertSame(parsed.table.cells, parsed.table.dynamicCells)

        // The earliest format did not have either additional grid.
        val oldest = legacy.copy(table = legacy.table.copy(closedCells = emptyMap(), dynamicCells = null))
        dataStore.edit { it[stringPreferencesKey("latest_snapshot")] = Json.encodeToString(GuailiSnapshot.serializer(), oldest) }
        assertEquals(oldest, second.read())
    }

    @Test fun aCustomDisplayGridSurvivesCompactPersistence() = runTest {
        val original = sampleSnapshot()
        val customCells = original.table.cells.mapValues { (_, row) -> row.mapValues { (_, cell) -> cell.copy(value = 99) } }
        val snapshot = original.copy(table = original.table.copy(cells = customCells))
        val persisted = PersistedGuailiSnapshot.from(snapshot)
        val restored = Json.decodeFromString<PersistedGuailiSnapshot>(
            Json.encodeToString(PersistedGuailiSnapshot.serializer(), persisted)).restore()
        assertEquals(snapshot, restored)
        assertEquals(99, restored.table.cells.getValue("BTCUSDT").getValue("1").value)
        assertNotEquals(restored.table.cells, restored.table.dynamicCells)
    }

    private fun sampleSnapshot(closedOnly: Boolean = false): GuailiSnapshot {
        val point = GuailiPoint(value = 12, guaili = 1.2, atr14 = 1.0, rankFilter = true, isClosed = false,
            openTime = "2026-10-15T00:00:00Z", closeTime = "2026-10-15T00:00:59.999Z")
        val response = GuailiResponse(listOf("BTCUSDT"), listOf("1"), 3, 500, false,
            results = listOf(GuailiSymbolResult("BTCUSDT", listOf(GuailiSeries("1", latest = point,
                data = listOf(point.copy(value = 10, isClosed = true), point))))))
        return GuailiSnapshot(response.toTable(response.symbols, response.intervals, closedOnly), 100L)
    }

    @Test fun activeObserverReceivesRefreshedCandlesFromAnotherStoreInstance() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFolder().resolve("snapshot.preferences_pb")
        }
        val widgetStore = GuailiSnapshotStore(dataStore)
        val workerStore = GuailiSnapshotStore(dataStore)
        val now = Instant.parse("2026-09-12T02:01:00Z").toEpochMilli()
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"), intervals = listOf("1", "2", "3", "5", "8"),
        )
        fun response(age: Long) = GuailiResponse(
            symbols = settings.symbols, intervals = settings.intervals,
            limit = 2, calcLimit = 500, closedOnly = false, timezone = "UTC",
            results = listOf(GuailiSymbolResult("BTCUSDT", settings.intervals.map { interval ->
                val duration = com.gouge.guaili.domain.guailiIntervalDurationMillis(interval)
                val start = (if (age <= 30_000) now else now - age) / duration * duration
                val point = GuailiPoint(value = 12, rankFilter = true, isClosed = false, atr14 = 1.0,
                    openTime = Instant.ofEpochMilli(start).toString(),
                    closeTime = Instant.ofEpochMilli(start + duration - 1).toString())
                GuailiSeries(interval = interval, latest = point, data = listOf(point.copy(isClosed = true,
                    openTime = Instant.ofEpochMilli(start - duration).toString(),
                    closeTime = Instant.ofEpochMilli(start - 1).toString()), point))
            })),
        )
        val staleResponse = response(20 * 60_000L)
        workerStore.save(GuailiSnapshot(staleResponse.toTable(settings.symbols, settings.intervals), now - 1_000))
        widgetStore.snapshots.test {
            val initial = awaitItem()!!
            assertEquals(5, widgetDataStatus(initial, settings.symbols, now, dynamicSignals = true).stale)
            assertTrue(GuailiSignalDetector.detect(initial.table, nowMillis = now).isEmpty())

            val refresh = GuailiRefreshUseCase(
                fetcherFactory = GuailiFetcherFactory { GuailiFetcher { GuailiResult.Success(response(30_000)) } },
                snapshotSink = workerStore,
                nowMillis = { now },
            )
            assertTrue(refresh.refresh(settings, requirePersistence = true) is GuailiResult.Success)
            val updated = awaitItem()!!
            assertEquals(now, updated.updatedAt)
            assertFalse(widgetDataStatus(updated, settings.symbols, now, dynamicSignals = true).incomplete)
            assertTrue(GuailiSignalDetector.detect(updated.table, nowMillis = now).isNotEmpty())
            assertEquals(updated, widgetStore.read())

            // A successful request must not make old server candles appear fresh.
            val oldServerData = GuailiRefreshUseCase(
                fetcherFactory = GuailiFetcherFactory { GuailiFetcher { GuailiResult.Success(staleResponse) } },
                snapshotSink = workerStore,
                nowMillis = { now + 1 },
            )
            assertTrue(oldServerData.refresh(settings, requirePersistence = true) is GuailiResult.Success)
            val stillStale = awaitItem()!!
            assertEquals(5, widgetDataStatus(stillStale, settings.symbols, now + 1, dynamicSignals = true).stale)
            assertTrue(GuailiSignalDetector.detect(stillStale.table, nowMillis = now + 1).isEmpty())
        }
    }
}
