package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.domain.GuailiTable
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GuailiSettingsSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuailiCacheStartupTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = 1_000_000L
    private val elapsed = 100_000L
    private val config = GuailiSettings.defaults().copy(baseUrl = "http://server-a", symbols = listOf("BTCUSDT"), intervals = listOf("1"))

    @Test fun matchingCacheIsVisibleWhileTheIndependentNetworkRequestIsSuspended() = runTest {
        val sink = Cache(snapshot(config, 12))
        val network = CompletableDeferred<GuailiResult<GuailiResponse>>()
        var calls = 0
        val vm = GuailiViewModel(Settings(config), { GuailiFetcher { calls++; network.await() } }, sink,
            computationDispatcher = StandardTestDispatcher(testScheduler), autoRefreshEnabled = false)
        try {
            runCurrent()
            assertEquals(1, calls)
            assertEquals(12, vm.state.value.cells["BTCUSDT"]?.get("1")?.value)
            assertFalse(vm.state.value.isLoading)
            assertTrue(vm.state.value.isRefreshing)
            assertEquals(now, vm.state.value.lastUpdatedAt)
            assertFalse(network.isCompleted)
            assertEquals(0, sink.saves)
        } finally { vm.close() }
    }

    @Test fun wrongProfileRuleVersionOldBootStaleAndUnknownClockCachesAreRejected() = runTest {
        val valid = snapshot(config, 12)
        val badSnapshots = listOf(
            valid.copy(signalProfile = config.copy(maLength = 50).signalProfile()),
            valid.copy(signalRuleVersion = "old-rule"),
            valid.copy(serverClock = valid.serverClock!!.copy(bootCount = 0)),
            valid.copy(serverClock = null),
        )
        for (cached in badSnapshots + valid) {
            val sink = Cache(cached)
            if (cached === valid) sink.device = sink.device.copy(elapsedMillis = elapsed + GUAILI_STALE_AFTER_MILLIS)
            val network = CompletableDeferred<GuailiResult<GuailiResponse>>()
            val vm = GuailiViewModel(Settings(config), { GuailiFetcher { network.await() } }, sink,
                computationDispatcher = StandardTestDispatcher(testScheduler), autoRefreshEnabled = false)
            try {
                runCurrent()
                assertTrue(vm.state.value.cells.isEmpty())
                assertTrue(vm.state.value.isLoading)
                assertNull(vm.state.value.lastUpdatedAt)
            } finally { vm.close() }
        }
    }

    @Test fun delayedStartupCacheCannotReplaceTheNewerNetworkSnapshot() = runTest {
        val old = snapshot(config, 12)
        val gate = CompletableDeferred<GuailiSnapshot?>()
        val sink = Cache(old).apply { firstRead = gate }
        val vm = GuailiViewModel(Settings(config), { GuailiFetcher { GuailiResult.Success(response(config, 99)) } }, sink,
            computationDispatcher = StandardTestDispatcher(testScheduler), autoRefreshEnabled = false)
        try {
            runCurrent()
            assertEquals(99, vm.state.value.cells["BTCUSDT"]?.get("1")?.value)
            gate.complete(old)
            runCurrent()
            assertEquals(99, vm.state.value.cells["BTCUSDT"]?.get("1")?.value)
            assertEquals(1, sink.saves)
        } finally { gate.complete(null); vm.close() }
    }

    @Test fun delayedOldProfileCannotReplaceTheNewSettingsCache() = runTest {
        val next = config.copy(baseUrl = "http://server-b", symbols = listOf("XAUUSDT"))
        val old = snapshot(config, 12)
        val gate = CompletableDeferred<GuailiSnapshot?>()
        val sink = Cache(old).apply { firstRead = gate }
        val source = Settings(config)
        val network = CompletableDeferred<GuailiResult<GuailiResponse>>()
        val vm = GuailiViewModel(source, { GuailiFetcher { network.await() } }, sink,
            computationDispatcher = StandardTestDispatcher(testScheduler), autoRefreshEnabled = false)
        try {
            runCurrent()
            sink.cached = snapshot(next, 34)
            source.save(next)
            runCurrent()
            assertEquals(34, vm.state.value.cells["XAUUSDT"]?.get("1")?.value)
            gate.complete(old)
            runCurrent()
            assertEquals(next, vm.state.value.settings)
            assertEquals(34, vm.state.value.cells["XAUUSDT"]?.get("1")?.value)
            assertNull(vm.state.value.cells["BTCUSDT"])
        } finally { gate.complete(null); vm.close() }
    }

    @Test fun queuedSettingsRefreshKeepsMatchingRestoredCacheVisible() = runTest {
        val next = config.copy(baseUrl = "http://server-b", symbols = listOf("XAUUSDT"))
        val source = Settings(config)
        val sink = Cache(snapshot(config, 12))
        val networkA = CompletableDeferred<GuailiResult<GuailiResponse>>()
        val networkB = CompletableDeferred<GuailiResult<GuailiResponse>>()
        var requestsB = 0
        val vm = GuailiViewModel(source, { GuailiFetcher { requested ->
            if (requested.baseUrl == next.baseUrl) { requestsB++; networkB.await() } else networkA.await()
        } }, sink, computationDispatcher = StandardTestDispatcher(testScheduler), autoRefreshEnabled = false)
        try {
            runCurrent()
            sink.cached = snapshot(next, 34)
            source.save(next)
            runCurrent()
            assertEquals(34, vm.state.value.cells["XAUUSDT"]?.get("1")?.value)
            networkA.complete(GuailiResult.Success(response(config, 99)))
            runCurrent()
            assertEquals(1, requestsB)
            assertFalse(networkB.isCompleted)
            assertEquals(34, vm.state.value.cells["XAUUSDT"]?.get("1")?.value)
            assertTrue(vm.state.value.isRefreshing)
            assertFalse(vm.state.value.isLoading)
        } finally { vm.close() }
    }

    private fun snapshot(settings: GuailiSettings, value: Int): GuailiSnapshot {
        val symbol = settings.symbols.single()
        val interval = settings.intervals.single()
        val cell = GuailiCell(symbol, interval, value, value / 10.0, null, null, null, null, false, false, false, null, null)
        return GuailiSnapshot(GuailiTable(settings.symbols, settings.intervals, mapOf(symbol to mapOf(interval to cell))), now,
            signalProfile = settings.signalProfile(), signalRuleVersion = GuailiSignalEvolution.RuleVersion,
            serverClock = GuailiServerClock(now, elapsed, 1, 0))
    }

    private fun response(settings: GuailiSettings, value: Int) = GuailiResponse(
        symbols = settings.symbols, intervals = settings.intervals, limit = settings.limit, calcLimit = settings.calcLimit,
        closedOnly = false, serverTime = now,
        results = listOf(GuailiSymbolResult(settings.symbols.single(), series = listOf(GuailiSeries(settings.intervals.single(), latest = GuailiPoint(value = value))))),
    )

    private class Settings(initial: GuailiSettings) : GuailiSettingsSource {
        override val settings = MutableStateFlow(initial)
        override suspend fun save(settings: GuailiSettings) { this.settings.value = settings }
    }

    private inner class Cache(var cached: GuailiSnapshot?) : GuailiSnapshotSink {
        var device = GuailiDeviceTime(now, elapsed, 1)
        var firstRead: CompletableDeferred<GuailiSnapshot?>? = null
        var reads = 0
        var saves = 0
        override fun currentDeviceTime() = device
        override suspend fun read(): GuailiSnapshot? {
            val gate = firstRead
            return if (++reads == 1 && gate != null) withContext(NonCancellable) { gate.await() } else cached
        }
        override suspend fun save(snapshot: GuailiSnapshot) { saves++; cached = snapshot }
    }

    private fun GuailiViewModel.close() { javaClass.getMethod("clear\$lifecycle_viewmodel").invoke(this) }
}
