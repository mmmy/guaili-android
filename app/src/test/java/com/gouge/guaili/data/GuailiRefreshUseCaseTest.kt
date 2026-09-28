package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.guailiIntervalDurationMillis
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuailiRefreshUseCaseTest {
    @Test
    fun refreshAndPersistedEvolutionUseServerTimeAfterDeviceClockJumps() = runTest {
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT"),
            intervals = listOf("1", "2", "3", "5", "8"))
        var serverTime = Instant.parse("2026-09-28T00:00:01Z").toEpochMilli()
        var device = GuailiDeviceTime(serverTime - 86_000L, 100_000L, 7)
        var stored: GuailiSnapshot? = null
        val sink = object : GuailiSnapshotSink {
            override fun currentDeviceTime() = device
            override suspend fun read() = stored
            override suspend fun save(snapshot: GuailiSnapshot) { stored = snapshot }
        }
        fun useCase() = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory { GuailiFetcher {
                device = device.copy(elapsedMillis = device.elapsedMillis + 300L)
                GuailiResult.Success(GuailiResponse(settings.symbols, settings.intervals, 3, 500, true,
                    timezone = "UTC", serverTime = serverTime,
                    results = listOf(GuailiSymbolResult("BTCUSDT", settings.intervals.map { interval ->
                        val duration = guailiIntervalDurationMillis(interval)
                        val closedAt = serverTime / duration * duration - 1
                        val closed = GuailiPoint(value = 12, guaili = 1.2, atr14 = 1.0, isClosed = true,
                            rankFilter = true, longTrend = true, shortTrend = false,
                            closeTime = Instant.ofEpochMilli(closedAt).toString())
                        GuailiSeries(interval, latest = closed, data = listOf(closed.copy(
                            closeTime = Instant.ofEpochMilli(closedAt - duration).toString()), closed))
                    }))))
            } }, snapshotSink = sink, nowMillis = { device.wallMillis },
        )
        val first = (useCase().refresh(settings) as GuailiResult.Success).value
        assertEquals(serverTime, first.signals.single().observedAt)
        assertEquals(300L, first.serverClock!!.requestDurationMillis)
        val status = com.gouge.guaili.widget.widgetDataStatus(first, settings.symbols,
            time = assessGuailiTime(first, device))
        assertEquals(0, status.stale)
        assertEquals(5, status.ready)
        serverTime += 60_000L
        device = device.copy(wallMillis = serverTime + 16 * 86_400_000L,
            elapsedMillis = device.elapsedMillis + 60_000L)
        val next = (useCase().refresh(settings) as GuailiResult.Success).value
        assertEquals(GuailiSignalPhase.Ongoing, next.signals.single().phase)
        assertEquals(serverTime, next.signals.single().observedAt)
        assertEquals(next, stored)
    }

    @Test
    fun persistedObservationSurvivesNewUseCaseAndResetsForIndicatorSettings() = runTest {
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT"), intervals = listOf("1", "2", "3", "5", "8"))
        val midnight = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
        var clock = midnight - 1_000
        var value = 7
        var stored: GuailiSnapshot? = null
        val sink = object : GuailiSnapshotSink {
            override suspend fun read() = stored
            override suspend fun save(snapshot: GuailiSnapshot) {
                stored = Json.decodeFromString<GuailiSnapshot>(Json.encodeToString(GuailiSnapshot.serializer(), snapshot))
            }
        }
        fun useCase() = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory { GuailiFetcher {
                GuailiResult.Success(GuailiResponse(settings.symbols, settings.intervals, 3, 500, true, timezone = "UTC",
                    results = listOf(GuailiSymbolResult("BTCUSDT", settings.intervals.map { interval ->
                        val duration = guailiIntervalDurationMillis(interval)
                        val closed = GuailiPoint(value = value, guaili = value / 10.0, atr14 = 1.0,
                            isClosed = true, rankFilter = true, longTrend = true, shortTrend = false,
                            closeTime = Instant.ofEpochMilli(clock / duration * duration - 1).toString())
                        GuailiSeries(interval, latest = closed, data = listOf(closed.copy(
                            closeTime = Instant.ofEpochMilli(clock / duration * duration - duration - 1).toString()), closed))
                    }))))
            } }, snapshotSink = sink, nowMillis = { clock },
        )
        useCase().refresh(settings)
        assertTrue(stored!!.signals.isEmpty())
        clock = midnight + 1_000
        value = 12
        useCase().refresh(settings)
        assertEquals(GuailiSignalPhase.Formed, stored!!.signals.single().phase)
        val eventTime = stored!!.signals.single().observedAt
        clock += 5_000
        useCase().refresh(settings.copy(autoRefreshSeconds = 10))
        assertEquals(GuailiSignalPhase.Formed, stored!!.signals.single().phase)
        assertEquals(eventTime, stored!!.signals.single().observedAt)
        useCase().refresh(settings.copy(maLength = 50))
        assertEquals(GuailiSignalPhase.FirstObserved, stored!!.signals.single().phase)
    }

    @Test fun profileIncludesEveryCalculationInputButNotPresentationChoices() {
        val settings = GuailiSettings.defaults()
        assertEquals(settings.signalProfile(), settings.copy(autoRefreshSeconds = 30,
            intervals = settings.intervals.reversed()).signalProfile())
        for (other in listOf(settings.copy(maType = "SMA"), settings.copy(maLength = 50),
            settings.copy(atrLen = 14), settings.copy(atrPercentLen = 30), settings.copy(maxAtrRank = 50.0),
            settings.copy(slopeMul = 0.3), settings.copy(useSlope = false), settings.copy(calcLimit = 1000),
            settings.copy(closedOnly = true), settings.copy(baseUrl = "http://localhost:3005/"),
            settings.copy(limit = 1000), settings.copy(intervals = listOf("1")))) {
            assertTrue(settings.signalProfile() != other.signalProfile())
        }
    }

    @Test
    fun widgetRefreshReportsCacheFailureInsteadOfClaimingSuccess() = runTest {
        val settings = GuailiSettings.defaults()
        val useCase = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory { GuailiFetcher { GuailiResult.Success(response(settings, 8)) } },
            snapshotSink = GuailiSnapshotSink { error("disk full") },
        )
        val result = useCase.refresh(settings, requirePersistence = true)
        assertTrue(result is GuailiResult.Failure)
        assertTrue((result as GuailiResult.Failure).message.contains("保存"))
    }

    @Test
    fun snapshotRecordsActualIndicatorParameters() = runTest {
        val settings = GuailiSettings.defaults().copy(maType = "SMA", maLength = 50)
        val useCase = GuailiRefreshUseCase(
            fetcherFactory = GuailiFetcherFactory { GuailiFetcher { GuailiResult.Success(response(settings, 8).copy(timezone = "Asia/Shanghai")) } },
        )
        val snapshot = (useCase.refresh(settings) as GuailiResult.Success).value
        assertEquals("SMA", snapshot.maType)
        assertEquals(50, snapshot.maLength)
        assertEquals("Asia/Shanghai", snapshot.timezone)
    }
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
