package com.gouge.guaili.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class ServerSignalsRefreshUseCaseTest {
    private class MemorySink : ServerSignalsSnapshotSink {
        var snapshot: ServerSignalsSnapshot? = null
        var failure: ServerSignalsFailure? = null
        var device = GuailiDeviceTime(1_000_000L, 100_000L, 7)
        override suspend fun save(snapshot: ServerSignalsSnapshot) { this.snapshot = snapshot }
        override suspend fun read() = snapshot
        override fun currentDeviceTime() = device
        override suspend fun recordFailure(failure: ServerSignalsFailure?) { this.failure = failure }
    }

    @Test fun successfulRefreshCapturesTheServerClockAndIndependentSource() = runTest {
        val sink = MemorySink()
        val useCase = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher {
                sink.device = sink.device.copy(elapsedMillis = sink.device.elapsedMillis + 300)
                GuailiResult.Success(ServerSignalsResponse(true, "ready", 20_000L))
            } },
            snapshotSink = sink,
            nowMillis = { sink.device.wallMillis },
        )
        val result = (useCase.refresh(" http://server-a:3005/ ") as GuailiResult.Success).value
        assertEquals("http://server-a:3005", result.baseUrl)
        assertEquals(300L, result.serverClock!!.requestDurationMillis)
        assertEquals(20_000L, assessServerSignalsTime(result, sink.device).nowMillis)
        assertEquals(result, sink.snapshot)
        assertFalse(result.belongsTo("http://server-b:3005"))
    }

    @Test fun networkFailureKeepsTheLastSuccessAndRecordsFailureSeparately() = runTest {
        val sink = MemorySink()
        val old = ServerSignalsSnapshot(ServerSignalsResponse(true, "ready", 20_000L), 20_000L, "http://server-a")
        sink.snapshot = old
        val useCase = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher { GuailiResult.Failure("offline") } },
            snapshotSink = sink, nowMillis = { 30_000L },
        )
        assertTrue(useCase.refresh("http://server-b") is GuailiResult.Failure)
        assertEquals(old, sink.snapshot)
        assertFalse(sink.snapshot!!.belongsTo("http://server-b"))
        assertEquals("http://server-b", sink.failure!!.baseUrl)
        assertEquals("offline", sink.failure!!.message)
    }

    @Test fun disabledServerResponseReplacesPreviouslyActiveSignals() = runTest {
        val sink = MemorySink()
        sink.snapshot = ServerSignalsSnapshot(ServerSignalsResponse(true, "ready", 20_000L), 20_000L, "http://server")
        sink.failure = ServerSignalsFailure("previous failure", 21_000L, "http://server")
        val useCase = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher {
                GuailiResult.Success(ServerSignalsResponse(false, "disabled", 30_000L))
            } }, snapshotSink = sink,
        )
        assertTrue(useCase.refresh("http://server") is GuailiResult.Success)
        assertFalse(sink.snapshot!!.response.enabled)
        assertEquals("disabled", sink.snapshot!!.response.status)
        assertNull(sink.failure)
    }

    @Test fun persistenceFailureIsVisibleAndCancellationRemainsCancellation() = runTest {
        val response = ServerSignalsResponse(true, "ready", 20_000L)
        val failingDisk = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher { GuailiResult.Success(response) } },
            snapshotSink = ServerSignalsSnapshotSink { error("disk full") },
        )
        val result = failingDisk.refresh("http://server") as GuailiResult.Failure
        assertTrue(result.message.contains("保存"))
        assertTrue(failingDisk.refresh("http://server", requirePersistence = false) is GuailiResult.Success)

        val cancelled = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher { throw CancellationException("cancel") } },
        )
        assertFailsWith<CancellationException> { cancelled.refresh("http://server") }
    }

    @Test fun independentEntrypointsSerializeRefreshAndSourceChangesRecreateFetcher() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var maximumActive = 0
        val created = mutableListOf<String>()
        val factory = ServerSignalsFetcherFactory { baseUrl ->
            created += baseUrl
            ServerSignalsFetcher {
                calls++
                active++
                maximumActive = maxOf(active, maximumActive)
                if (calls == 1) { started.complete(Unit); release.await() }
                active--
                GuailiResult.Success(ServerSignalsResponse(true, "ready", 20_000L + calls))
            }
        }
        val first = ServerSignalsRefreshUseCase(fetcherFactory = factory)
        val second = ServerSignalsRefreshUseCase(fetcherFactory = factory)
        val a = async { first.refresh("http://server-a") }
        started.await()
        val b = async { second.refresh("http://server-a") }
        release.complete(Unit)
        a.await(); b.await()
        first.refresh("http://server-a/")
        first.refresh("http://server-b")
        assertEquals(1, maximumActive)
        assertEquals(listOf("http://server-a", "http://server-a", "http://server-b"), created)
    }

    @Test fun eachRefreshUsesTheCurrentUnionAndReplacesItAsOneSample() = runTest {
        val queries = mutableListOf<List<String>>()
        val sink = MemorySink()
        val useCase = ServerSignalsRefreshUseCase(
            fetcherFactory = ServerSignalsFetcherFactory { ServerSignalsFetcher { symbols ->
                queries += symbols
                GuailiResult.Success(ServerSignalsResponse(true, "ready", 20_000L + queries.size,
                    results = symbols.map { ServerSymbolSignals(it) }))
            } }, snapshotSink = sink,
        )
        useCase.refresh("http://server", symbols = listOf("BTCUSDT", "XAUUSDT"))
        assertEquals(listOf("BTCUSDT", "XAUUSDT"), sink.snapshot!!.response.results.map { it.symbol })
        useCase.refresh("http://server", symbols = listOf("BTCUSDT", "SOXLUSDT"))
        assertEquals(listOf(listOf("BTCUSDT", "XAUUSDT"), listOf("BTCUSDT", "SOXLUSDT")), queries)
        // Removed selections are not silently merged in with stale data from the prior sample.
        assertEquals(listOf("BTCUSDT", "SOXLUSDT"), sink.snapshot!!.response.results.map { it.symbol })
    }
}
