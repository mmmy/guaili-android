package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuailiRefreshUseCaseTest {
    @Test
    fun successfulRefreshMapsAndPersistsSnapshot() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("5"),
        )
        var saved: GuailiSnapshot? = null
        val useCase = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory {
                GuailiFetcher { GuailiResult.Success(response(settings, value = 14)) }
            },
            snapshotSink = GuailiSnapshotSink { saved = it },
            nowMillis = { 42L },
        )

        val result = useCase.refresh(settings)

        assertTrue(result is GuailiResult.Success)
        assertEquals(42L, saved?.updatedAt)
        assertEquals(14, saved?.table?.cells?.get("BTCUSDT")?.get("5")?.value)
    }

    @Test
    fun cacheWriteFailureDoesNotHideFreshNetworkData() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("5"),
        )
        val useCase = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory {
                GuailiFetcher { GuailiResult.Success(response(settings, value = 8)) }
            },
            snapshotSink = GuailiSnapshotSink { error("disk full") },
            nowMillis = { 99L },
        )

        val result = useCase.refresh(settings)

        assertTrue(result is GuailiResult.Success)
        result as GuailiResult.Success
        assertEquals(8, result.value.table.cells["BTCUSDT"]?.get("5")?.value)
    }

    private fun response(settings: GuailiSettings, value: Int) = GuailiResponse(
        symbols = settings.symbols,
        intervals = settings.intervals,
        limit = settings.limit,
        calcLimit = settings.calcLimit,
        closedOnly = settings.closedOnly,
        results = listOf(
            GuailiSymbolResult(
                symbol = "BTCUSDT",
                series = listOf(
                    GuailiSeries(
                        interval = "5",
                        latest = GuailiPoint(value = value),
                    ),
                ),
            ),
        ),
    )
}
