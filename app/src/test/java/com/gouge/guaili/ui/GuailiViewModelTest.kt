package com.gouge.guaili.ui

import com.gouge.guaili.data.GuailiPoint
import com.gouge.guaili.data.GuailiResponse
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.data.GuailiSeries
import com.gouge.guaili.data.GuailiSymbolResult
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.GroupLayoutSize
import com.gouge.guaili.settings.LayoutMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class GuailiViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun refreshSuccessMapsCellsAndSetsLastUpdatedAt() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        val settingsSource = FakeSettingsSource(settings)
        val fetcher = QueueingFetcher(
            GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 12)),
        )
        val viewModel = GuailiViewModel(
            settingsSource = settingsSource,
            fetcherFactory = { fetcher },
            nowMillis = { 1234L },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("BTCUSDT"), state.symbols)
            assertEquals(listOf("1"), state.intervals)
            assertEquals(12, state.cells["BTCUSDT"]?.get("1")?.value)
            assertEquals(1234L, state.lastUpdatedAt)
            assertFalse(state.isLoading)
            assertFalse(state.isRefreshing)
            assertFalse(state.isStale)
            assertNull(state.errorMessage)
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun failureWithPriorCellsPreservesDataAndMarksStaleForSameSettings() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        val settingsSource = FakeSettingsSource(settings)
        val fetcher = QueueingFetcher(
            GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 12)),
            GuailiResult.Failure("offline"),
        )
        val viewModel = GuailiViewModel(
            settingsSource = settingsSource,
            fetcherFactory = { fetcher },
            nowMillis = { 1234L },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(12, state.cells["BTCUSDT"]?.get("1")?.value)
            assertEquals("offline", state.errorMessage)
            assertTrue(state.isStale)
            assertFalse(state.isLoading)
            assertFalse(state.isRefreshing)
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun returningToForegroundMarksDataOlderThanThirtyMinutesStale() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        var now = 1_000L
        val viewModel = GuailiViewModel(
            settingsSource = FakeSettingsSource(settings),
            fetcherFactory = {
                QueueingFetcher(
                    GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 12)),
                )
            },
            nowMillis = { now },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()
            viewModel.setForeground(false)
            now += 30 * 60 * 1000L

            viewModel.setForeground(true)

            assertTrue(viewModel.state.value.isStale)
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun returningToForegroundKeepsDataYoungerThanThirtyMinutesLive() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        var now = 1_000L
        val viewModel = GuailiViewModel(
            settingsSource = FakeSettingsSource(settings),
            fetcherFactory = {
                QueueingFetcher(
                    GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 12)),
                )
            },
            nowMillis = { now },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()
            viewModel.setForeground(false)
            now += 30 * 60 * 1000L - 1L

            viewModel.setForeground(true)

            assertFalse(viewModel.state.value.isStale)
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun repositoryFactoryExceptionBecomesErrorState() = runTest {
        val settings = GuailiSettings.defaults().copy(
            baseUrl = "not a url",
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        val settingsSource = FakeSettingsSource(settings)
        val viewModel = GuailiViewModel(
            settingsSource = settingsSource,
            fetcherFactory = { throw IllegalArgumentException("bad base url") },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("bad base url", state.errorMessage)
            assertFalse(state.isLoading)
            assertFalse(state.isRefreshing)
            assertFalse(state.isStale)
            assertTrue(state.cells.isEmpty())
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun overlappingManualRefreshesDoNotRunConcurrentFetches() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        val settingsSource = FakeSettingsSource(settings)
        val firstFetch = CompletableDeferred<GuailiResult<GuailiResponse>>()
        val fetcher = BlockingFetcher(firstFetch)
        val viewModel = GuailiViewModel(
            settingsSource = settingsSource,
            fetcherFactory = { fetcher },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()
            assertEquals(1, fetcher.startedCalls)

            viewModel.refresh()
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, fetcher.startedCalls)

            firstFetch.complete(GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 7)))
            advanceUntilIdle()

            assertEquals(2, fetcher.startedCalls)
        } finally {
            viewModel.clearViewModel()
        }
    }

    @Test
    fun presentationChangesKeepCellsAndDoNotFetchAgain() = runTest {
        val settings = GuailiSettings.defaults().copy(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1"),
        )
        val settingsSource = FakeSettingsSource(settings)
        val fetcher = QueueingFetcher(
            GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 12)),
        )
        val viewModel = GuailiViewModel(
            settingsSource = settingsSource,
            fetcherFactory = { fetcher },
            autoRefreshEnabled = false,
        )
        try {
            advanceUntilIdle()

            viewModel.setLayoutMode(LayoutMode.Groups)
            advanceUntilIdle()
            viewModel.saveSettings(
                viewModel.state.value.settings.copy(
                    groupLayoutSize = GroupLayoutSize.TenColumns,
                ),
            )
            advanceUntilIdle()

            assertEquals(LayoutMode.Groups, viewModel.state.value.settings.layoutMode)
            assertEquals(
                GroupLayoutSize.TenColumns,
                viewModel.state.value.settings.groupLayoutSize,
            )
            assertEquals(12, viewModel.state.value.cells["BTCUSDT"]?.get("1")?.value)
            assertEquals(1, fetcher.calls)
        } finally {
            viewModel.clearViewModel()
        }
    }
}

private fun GuailiViewModel.clearViewModel() {
    javaClass.getMethod("clear\$lifecycle_viewmodel").invoke(this)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakeSettingsSource(
    initialSettings: GuailiSettings,
) : GuailiSettingsSource {
    private val settingsFlow = MutableStateFlow(initialSettings)

    override val settings: Flow<GuailiSettings> = settingsFlow

    override suspend fun save(settings: GuailiSettings) {
        settingsFlow.value = settings
    }
}

private class QueueingFetcher(
    private vararg val results: GuailiResult<GuailiResponse>,
) : GuailiFetcher {
    private var index = 0
    var calls = 0
        private set

    override suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse> {
        calls += 1
        return results[index++]
    }
}

private class BlockingFetcher(
    private val firstResult: CompletableDeferred<GuailiResult<GuailiResponse>>,
) : GuailiFetcher {
    var startedCalls = 0
        private set

    override suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse> {
        startedCalls += 1
        return if (startedCalls == 1) {
            firstResult.await()
        } else {
            GuailiResult.Success(responseFor(settings, "BTCUSDT", "1", value = 9))
        }
    }
}

private fun responseFor(
    settings: GuailiSettings,
    symbol: String,
    interval: String,
    value: Int,
): GuailiResponse = GuailiResponse(
    symbols = settings.symbols,
    intervals = settings.intervals,
    limit = settings.limit,
    calcLimit = settings.calcLimit,
    closedOnly = settings.closedOnly,
    results = listOf(
        GuailiSymbolResult(
            symbol = symbol,
            series = listOf(
                GuailiSeries(
                    interval = interval,
                    latest = GuailiPoint(value = value),
                ),
            ),
        ),
    ),
)
