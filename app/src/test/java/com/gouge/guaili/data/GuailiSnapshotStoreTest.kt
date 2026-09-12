package com.gouge.guaili.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.gouge.guaili.domain.GuailiSignalDetector
import com.gouge.guaili.domain.toTable
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.widget.widgetDataStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuailiSnapshotStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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
            limit = 2, calcLimit = 500, closedOnly = true, timezone = "UTC",
            results = listOf(GuailiSymbolResult("BTCUSDT", settings.intervals.map { interval ->
                val point = GuailiPoint(value = 12, rankFilter = true, isClosed = true,
                    closeTime = Instant.ofEpochMilli(now - age).toString())
                GuailiSeries(interval = interval, latest = point, data = listOf(point))
            })),
        )
        val staleResponse = response(20 * 60_000L)
        workerStore.save(GuailiSnapshot(staleResponse.toTable(settings.symbols, settings.intervals), now - 1_000))
        widgetStore.snapshots.test {
            val initial = awaitItem()!!
            assertEquals(5, widgetDataStatus(initial, settings.symbols, now).stale)
            assertTrue(GuailiSignalDetector.detect(initial.table, nowMillis = now).isEmpty())

            val refresh = GuailiRefreshUseCase(
                fetcherFactory = GuailiFetcherFactory { GuailiFetcher { GuailiResult.Success(response(30_000)) } },
                snapshotSink = workerStore,
                nowMillis = { now },
            )
            assertTrue(refresh.refresh(settings, requirePersistence = true) is GuailiResult.Success)
            val updated = awaitItem()!!
            assertEquals(now, updated.updatedAt)
            assertFalse(widgetDataStatus(updated, settings.symbols, now).incomplete)
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
            assertEquals(5, widgetDataStatus(stillStale, settings.symbols, now + 1).stale)
            assertTrue(GuailiSignalDetector.detect(stillStale.table, nowMillis = now + 1).isEmpty())
        }
    }
}
