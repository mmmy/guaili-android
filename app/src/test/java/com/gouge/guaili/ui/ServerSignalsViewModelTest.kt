package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSignalsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val now = 1_000_000L
    private val periods = listOf("1", "2", "3", "5", "8")
    private fun response(at: Long = now) = ServerSignalsResponse(true, "ready", at, at, results = listOf(
        ServerSymbolSignals("BTCUSDT", "ready", at,
            signals = listOf(ServerSignalStructure("btc", "extreme", "positive",
                listOf(ServerSignalRun("positive", periods)), 5, 5, "8")),
            perIntervalQuality = periods.map { ServerIntervalEvidence(it, "ready") }),
        ServerSymbolSignals("XAUUSDT", "ready", at, perIntervalQuality = periods.map { ServerIntervalEvidence(it, "ready") }),
    ))
    private class Settings : GuailiSettingsSource {
        override val settings = MutableStateFlow(GuailiSettings.defaults().copy(baseUrl = "http://server-a", symbols = listOf("BTCUSDT")))
        override suspend fun save(settings: GuailiSettings) { this.settings.value = settings }
    }
    private class Preferences : MarketSignalPreferencesSource {
        override val preferences = MutableStateFlow(MarketSignalPreferences())
        override suspend fun update(transform: (MarketSignalPreferences) -> MarketSignalPreferences) {
            preferences.value = transform(preferences.value)
        }
    }
    private class Cache(private val elapsedOffset: () -> Long = { 0L }) : ServerSignalsSource {
        override val snapshots = MutableStateFlow<ServerSignalsSnapshot?>(null)
        override val failures = MutableStateFlow<ServerSignalsFailure?>(null)
        var time = GuailiDeviceTime(1_000_000, 100_000, 1)
        override suspend fun read() = snapshots.value
        override suspend fun readFailure() = failures.value
        override suspend fun save(snapshot: ServerSignalsSnapshot) { snapshots.value = snapshot; failures.value = null }
        override suspend fun recordFailure(failure: ServerSignalsFailure?) { failures.value = failure }
        override fun currentDeviceTime() = time.copy(elapsedMillis = time.elapsedMillis + elapsedOffset())
    }
    private fun ServerSignalsViewModel.close() { javaClass.getMethod("clear\$lifecycle_viewmodel").invoke(this) }

    @Test fun configuredPhoneIntervalOverridesServerSamplingAndChangesWithoutDuplicateRequests() = runTest {
        val settings = Settings()
        settings.save(settings.settings.value.copy(autoRefreshSeconds = 10))
        val cache = Cache { testScheduler.currentTime }
        var calls = 0
        val vm = ServerSignalsViewModel(settings, Preferences(), cache, ServerSignalsRefreshUseCase(
            ServerSignalsFetcherFactory { ServerSignalsFetcher {
                calls++; GuailiResult.Success(response(now + testScheduler.currentTime))
            } }, cache,
        ))
        try {
            runCurrent(); vm.setForeground(true); runCurrent()
            assertEquals(1, calls)
            assertEquals(10, vm.state.value.autoRefreshSeconds)
            assertEquals(5_000L, vm.state.value.presentation.evaluationIntervalMs)
            advanceTimeBy(9_999); runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, calls)
            settings.save(settings.settings.value.copy(autoRefreshSeconds = 3)); runCurrent()
            assertEquals(2, calls)
            assertEquals(3, vm.state.value.autoRefreshSeconds)
            advanceTimeBy(2_999); runCurrent()
            assertEquals(2, calls)
            advanceTimeBy(1); runCurrent()
            assertEquals(3, calls)
            vm.setForeground(false)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(3, calls)
        } finally { vm.close() }
    }

    @Test fun slowPhoneRefreshStillExpiresOldSignalsAndAllowsManualRefresh() = runTest {
        val settings = Settings()
        settings.save(settings.settings.value.copy(autoRefreshSeconds = 60))
        val cache = Cache { testScheduler.currentTime }
        var calls = 0
        val vm = ServerSignalsViewModel(settings, Preferences(), cache, ServerSignalsRefreshUseCase(
            ServerSignalsFetcherFactory { ServerSignalsFetcher {
                calls++; GuailiResult.Success(response(now + testScheduler.currentTime))
            } }, cache,
        ))
        try {
            runCurrent(); vm.setForeground(true); runCurrent()
            assertEquals(1, vm.state.value.presentation.signals.size)
            advanceTimeBy(16_000); runCurrent()
            assertEquals(1, calls)
            assertEquals("stale", vm.state.value.presentation.status)
            assertTrue(vm.state.value.presentation.signals.isEmpty())
            vm.refresh(); runCurrent()
            assertEquals(2, calls)
            assertEquals(1, vm.state.value.presentation.signals.size)
            advanceTimeBy(44_000); runCurrent()
            assertEquals(3, calls)
        } finally { vm.close() }
    }

    @Test fun foregroundRefreshWorksWithoutWidgetsAndStopsWhenHidden() = runTest {
        val cache = Cache()
        var calls = 0
        val vm = ServerSignalsViewModel(Settings(), Preferences(), cache, ServerSignalsRefreshUseCase(
            ServerSignalsFetcherFactory { ServerSignalsFetcher { symbols ->
                assertTrue("The shared cache must always contain the full server universe", symbols.isEmpty())
                calls++; GuailiResult.Success(response())
            } }, cache,
        ))
        try {
            runCurrent()
            assertEquals(0, calls)
            vm.setForeground(true); runCurrent()
            assertEquals(1, calls)
            assertEquals(1, vm.state.value.presentation.signals.size)
            assertEquals(listOf("BTCUSDT", "XAUUSDT"), cache.snapshots.value!!.response.results.map { it.symbol })
            cache.time = cache.time.copy(elapsedMillis = 106_000)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(2, calls)
            vm.setForeground(false)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(2, calls)
        } finally { vm.close() }
    }

    @Test fun failedRefreshKeepsValidCacheAndExpiryTimerHidesItWithoutAnotherResponse() = runTest {
        val cache = Cache()
        var calls = 0
        val vm = ServerSignalsViewModel(Settings(), Preferences(), cache, ServerSignalsRefreshUseCase(
            ServerSignalsFetcherFactory { ServerSignalsFetcher {
                if (++calls == 1) GuailiResult.Success(response()) else GuailiResult.Failure("offline")
            } }, cache, nowMillis = { now + testScheduler.currentTime },
        ))
        try {
            runCurrent(); vm.setForeground(true); runCurrent()
            val saved = cache.snapshots.value
            cache.time = cache.time.copy(elapsedMillis = 106_000)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(1, vm.state.value.presentation.signals.size)
            assertTrue(vm.state.value.presentation.warning!!.contains("offline"))
            assertEquals("offline", vm.state.value.refreshError)
            cache.time = cache.time.copy(elapsedMillis = 116_000)
            advanceTimeBy(1_000); runCurrent()
            assertEquals("stale", vm.state.value.presentation.status)
            assertTrue(vm.state.value.presentation.signals.isEmpty())
            assertEquals(saved, cache.snapshots.value)
        } finally { vm.close() }
    }

    @Test fun sourceChangeHidesPreviousServerAndCancelsItsInFlightRequest() = runTest {
        val settings = Settings()
        val cache = Cache()
        val waiting = CompletableDeferred<GuailiResult<ServerSignalsResponse>>()
        val created = mutableListOf<String>()
        val vm = ServerSignalsViewModel(settings, Preferences(), cache, ServerSignalsRefreshUseCase(
            ServerSignalsFetcherFactory { url -> created += url; ServerSignalsFetcher {
                if (url == "http://server-a") GuailiResult.Success(response()) else waiting.await()
            } }, cache,
        ))
        try {
            runCurrent(); vm.setForeground(true); runCurrent()
            assertEquals(1, vm.state.value.presentation.signals.size)
            settings.save(settings.settings.value.copy(baseUrl = "http://server-b")); runCurrent()
            assertNull(vm.state.value.snapshot)
            assertTrue(vm.state.value.presentation.signals.isEmpty())
            assertEquals(listOf("http://server-a", "http://server-b"), created)
            vm.setForeground(false); runCurrent()
            assertFalse(vm.state.value.isRefreshing)
        } finally { vm.close() }
    }

    @Test fun preferencesAreIndependentOfTableAndSharedSnapshot() = runTest {
        val settings = Settings()
        val prefs = Preferences()
        val cache = Cache()
        cache.save(ServerSignalsSnapshot(response(), now, "http://server-a", GuailiServerClock(now, 100_000, 1, 0), true))
        val vm = ServerSignalsViewModel(settings, prefs, cache)
        try {
            runCurrent()
            vm.setView(MarketView.SignalsV2)
            vm.toggleSymbol("XAUUSDT")
            vm.toggleKind(GuailiSignalKind.Extreme)
            runCurrent()
            assertEquals(MarketView.SignalsV2, prefs.preferences.value.view)
            assertEquals(listOf("BTCUSDT", "XAUUSDT"), vm.state.value.symbols)
            assertTrue(vm.state.value.presentation.signals.isEmpty())
            assertEquals(listOf("BTCUSDT"), settings.settings.value.symbols)
            assertEquals(2, cache.snapshots.value!!.response.results.size)
            settings.save(settings.settings.value.copy(symbols = listOf("QQQUSDT"))); runCurrent()
            assertEquals(listOf("BTCUSDT", "XAUUSDT"), vm.state.value.symbols)
            vm.toggleKind(GuailiSignalKind.Extreme); runCurrent()
            assertEquals(1, vm.state.value.presentation.signals.size)
        } finally { vm.close() }
    }
}
