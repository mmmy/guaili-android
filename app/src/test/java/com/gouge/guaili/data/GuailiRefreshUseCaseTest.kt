package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.domain.GuailiSignalPhase
import com.gouge.guaili.domain.guailiIntervalDurationMillis
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
                        val live = closed.copy(isClosed = false,
                            openTime = Instant.ofEpochMilli(closedAt + 1).toString(),
                            closeTime = Instant.ofEpochMilli(closedAt + duration).toString())
                        GuailiSeries(interval, latest = live, data = listOf(closed, live))
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
                        val live = closed.copy(isClosed = false,
                            openTime = Instant.ofEpochMilli(clock / duration * duration).toString(),
                            closeTime = Instant.ofEpochMilli((clock / duration + 1) * duration - 1).toString())
                        GuailiSeries(interval, latest = live, data = listOf(closed, live))
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
    fun identicalRefreshesFromDifferentEntrypointsShareOneObservation() = runTest {
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
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstUseCase = GuailiRefreshUseCase(fetcherFactory = fetcherFactory, computationDispatcher = dispatcher)
        val secondUseCase = GuailiRefreshUseCase(fetcherFactory = fetcherFactory, computationDispatcher = dispatcher)

        val first = async { firstUseCase.refresh(settings) }
        firstStarted.await()
        val second = async { secondUseCase.refresh(settings.copy(autoRefreshSeconds = 30,
            tableDensity = com.gouge.guaili.settings.TableDensity.Comfortable)) }
        runCurrent()

        releaseFirst.complete(Unit)
        val initial = first.await()
        assertEquals(initial, second.await())

        assertEquals(1, requestCount)
        assertEquals(1, maxActiveRequests)
    }

    @Test fun allDataInputsAndReturnedOrderingSeparateConcurrentRequests() = runTest {
        val settings = GuailiSettings.defaults().copy(symbols = listOf("BTCUSDT", "XAUUSDT"),
            intervals = listOf("5", "1"))
        val variations = listOf(settings.copy(baseUrl = "http://another-server/"),
            settings.copy(symbols = settings.symbols.reversed()), settings.copy(intervals = settings.intervals.reversed()),
            settings.copy(closedOnly = true), settings.copy(limit = 4), settings.copy(calcLimit = 600),
            settings.copy(maType = "SMA"), settings.copy(maLength = 21), settings.copy(atrLen = 2),
            settings.copy(atrPercentLen = 21), settings.copy(maxAtrRank = 50.0),
            settings.copy(slopeMul = 0.2), settings.copy(useSlope = false))
        for (other in variations) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val useCase = GuailiRefreshUseCase(GuailiFetcherFactory { GuailiFetcher {
                calls++
                if (calls == 1) { started.complete(Unit); release.await() }
                GuailiResult.Success(response(it, calls))
            } }, computationDispatcher = StandardTestDispatcher(testScheduler))
            val first = async { useCase.refresh(settings) }
            started.await()
            val second = async { useCase.refresh(other) }
            runCurrent()
            release.complete(Unit)
            first.await(); second.await()
            assertEquals("must distinguish $other", 2, calls)
        }
    }

    @Test fun sharedWriteFailureIsInterpretedPerCallerAndAnotherDestinationRemainsIndependent() = runTest {
        val settings = GuailiSettings.defaults()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var writes = 0
        val factory = GuailiFetcherFactory { GuailiFetcher {
            calls++
            if (calls == 1) { started.complete(Unit); release.await() }
            GuailiResult.Success(response(settings, calls))
        } }
        val diskFull = GuailiSnapshotSink { writes++; error("disk full") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screen = GuailiRefreshUseCase(factory, diskFull, computationDispatcher = dispatcher)
        val widget = GuailiRefreshUseCase(factory, diskFull, computationDispatcher = dispatcher)
        val otherStore = GuailiRefreshUseCase(factory, GuailiSnapshotSink { writes++ }, computationDispatcher = dispatcher)
        val first = async { screen.refresh(settings) }
        started.await()
        val required = async { widget.refresh(settings, requirePersistence = true) }
        val separate = async { otherStore.refresh(settings) }
        runCurrent()
        release.complete(Unit)
        assertTrue(first.await() is GuailiResult.Success)
        assertTrue(required.await() is GuailiResult.Failure)
        assertTrue(separate.await() is GuailiResult.Success)
        assertEquals(2, calls)
        assertEquals(2, writes)
    }

    @Test fun screenAndStrictWidgetShareOneSuccessfulNetworkCalculationAndSave() = runTest {
        val settings = GuailiSettings.defaults()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val destination = Any()
        var calls = 0
        val saved = mutableListOf<GuailiSnapshot>()
        // Separate wrappers represent separate Store instances with one backing DataStore.
        fun sink() = object : GuailiSnapshotSink {
            override fun refreshIdentity() = destination
            override suspend fun save(snapshot: GuailiSnapshot) { saved += snapshot }
        }
        val factory = GuailiFetcherFactory { GuailiFetcher {
            calls++; started.complete(Unit); release.await()
            GuailiResult.Success(response(settings, 12))
        } }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screen = GuailiRefreshUseCase(factory, sink(), computationDispatcher = dispatcher)
        val worker = GuailiRefreshUseCase(factory, sink(), computationDispatcher = dispatcher)
        val first = async { screen.refresh(settings) }
        started.await()
        val strict = async { worker.refresh(settings, requirePersistence = true) }
        runCurrent()
        release.complete(Unit)
        val snapshot = (first.await() as GuailiResult.Success).value
        assertEquals(snapshot, (strict.await() as GuailiResult.Success).value)
        assertEquals(listOf(snapshot), saved)
        assertEquals(1, calls)
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
