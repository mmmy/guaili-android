package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    @Test
    fun refreshesFromDifferentEntrypointsDoNotRunConcurrently() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("5"),
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var requestCount = 0
        var activeRequests = 0
        var maxActiveRequests = 0
        val fetcherFactory = GuailiFetcherFactory {
            GuailiFetcher {
                requestCount += 1
                activeRequests += 1
                maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
                if (requestCount == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                activeRequests -= 1
                GuailiResult.Success(response(settings, value = requestCount))
            }
        }
        val firstUseCase = GuailiRefreshUseCase(fetcherFactory = fetcherFactory)
        val secondUseCase = GuailiRefreshUseCase(fetcherFactory = fetcherFactory)

        val first = async { firstUseCase.refresh(settings) }
        firstStarted.await()
        val second = async { secondUseCase.refresh(settings) }

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, requestCount)
        assertEquals(1, maxActiveRequests)
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
