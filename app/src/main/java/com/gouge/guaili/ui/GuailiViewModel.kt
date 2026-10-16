package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.GuailiRefreshUseCase
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.data.GuailiSnapshotSink
import com.gouge.guaili.data.GUAILI_STALE_AFTER_MILLIS
import com.gouge.guaili.data.isGuailiSnapshotStale
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.assessGuailiTime
import com.gouge.guaili.data.signalProfile
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiIntervalDurationMillis
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.LayoutMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GuailiTableState(
    val settings: GuailiSettings = GuailiSettings.defaults(),
    val symbols: List<String> = GuailiSettings.defaults().symbols,
    val intervals: List<String> = GuailiSettings.defaults().intervals,
    val cells: Map<String, Map<String, GuailiCell>> = emptyMap(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val errorMessage: String? = null,
    val isStale: Boolean = false,
    val settingsLoaded: Boolean = false,
)

class GuailiViewModel(
    private val settingsSource: GuailiSettingsSource,
    private val fetcherFactory: GuailiFetcherFactory = GuailiFetcherFactory { baseUrl ->
        val repository = com.gouge.guaili.data.GuailiRepository.create(baseUrl)
        GuailiFetcher(repository::fetch)
    },
    private val snapshotSink: GuailiSnapshotSink = GuailiSnapshotSink { },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val autoRefreshEnabled: Boolean = true,
    private val onSnapshotUpdated: suspend () -> Unit = { },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(GuailiTableState())
    val state: StateFlow<GuailiTableState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var staleStatusJob: Job? = null
    private var refreshJob: Job? = null
    private var pendingRefreshSettings: GuailiSettings? = null
    private var pendingRefreshClearsCells: Boolean = false
    private val refresher = GuailiRefreshUseCase(fetcherFactory, snapshotSink, nowMillis, computationDispatcher)
    private val temporaryRefresher = GuailiRefreshUseCase(fetcherFactory, object : GuailiSnapshotSink {
        override fun currentDeviceTime() = snapshotSink.currentDeviceTime()
        override suspend fun read() = snapshotSink.read()
        // This independent identity never shares a save with a real widget store.
        override suspend fun save(snapshot: GuailiSnapshot) = Unit
    }, nowMillis, computationDispatcher)
    private var isForeground: Boolean = true
    private var hasObservedSettings = false
    private var latestSnapshot: GuailiSnapshot? = null
    private var latestSnapshotSettings: GuailiSettings? = null
    private var restoreJob: Job? = null
    private var settingsGeneration = 0L
    private var temporarySymbol: String? = null
    private var temporaryIntervals: List<String> = emptyList()

    private fun requestSettings(settings: GuailiSettings) = settings.copy(
        symbols = (settings.symbols + listOfNotNull(temporarySymbol)).distinct(),
        intervals = if (temporaryIntervals.any { it !in settings.intervals })
            (settings.intervals + temporaryIntervals).distinct().sortedByDescending(::guailiIntervalDurationMillis)
            else settings.intervals,
    )

    fun setTemporaryRange(symbol: String?, intervals: List<String>) {
        if (temporarySymbol == symbol && temporaryIntervals == intervals) return
        val previous = requestSettings(_state.value.settings)
        temporarySymbol = symbol
        temporaryIntervals = intervals
        val next = requestSettings(_state.value.settings)
        if (!sameDataRequest(previous, next)) requestRefresh(next, clearsCells = false)
    }

    init {
        viewModelScope.launch {
            settingsSource.settings.collect { settings ->
                val requestSettingsChanged = !hasObservedSettings ||
                    !sameDataRequest(_state.value.settings, settings)
                hasObservedSettings = true
                _state.value = _state.value.copy(
                    settings = settings,
                    settingsLoaded = true,
                    symbols = requestSettings(settings).symbols,
                    intervals = requestSettings(settings).intervals,
                    cells = if (requestSettingsChanged) emptyMap() else _state.value.cells,
                    isStale = if (requestSettingsChanged) false else _state.value.isStale,
                )
                if (requestSettingsChanged) {
                    latestSnapshot = null
                    latestSnapshotSettings = null
                    restoreCachedSnapshot(settings)
                    requestRefresh(requestSettings(settings), clearsCells = true)
                }
                if (autoRefreshEnabled) {
                    restartAutoRefresh()
                }
            }
        }
    }

    private fun restoreCachedSnapshot(settings: GuailiSettings) {
        restoreJob?.cancel()
        val generation = ++settingsGeneration
        restoreJob = viewModelScope.launch {
            val cached = try {
                withContext(computationDispatcher) {
                    val value = snapshotSink.read() ?: return@withContext null
                    if (value.signalProfile != settings.signalProfile() ||
                        value.signalRuleVersion != GuailiSignalEvolution.RuleVersion) return@withContext null
                    val device = snapshotSink.currentDeviceTime() ?: return@withContext null
                    val time = assessGuailiTime(value, device)
                    value.takeIf { time.available && time.cacheAgeMillis!! < GUAILI_STALE_AFTER_MILLIS }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null // A missing/broken cache never delays the independent network refresh.
            } ?: return@launch
            if (generation != settingsGeneration || latestSnapshot != null ||
                !sameDataRequest(requestSettings(_state.value.settings), settings)) return@launch
            val currentTime = snapshotSink.currentDeviceTime()?.let { assessGuailiTime(cached, it) } ?: return@launch
            if (!currentTime.available || currentTime.cacheAgeMillis!! >= GUAILI_STALE_AFTER_MILLIS) return@launch
            latestSnapshot = cached
            latestSnapshotSettings = settings
            _state.value = _state.value.copy(
                cells = cached.table.cells,
                lastUpdatedAt = cached.serverClock!!.serverTimeMillis,
                isLoading = false,
                isRefreshing = refreshJob?.isActive == true,
                isStale = _state.value.errorMessage != null,
            )
            scheduleStaleStatusUpdate()
        }
    }

    fun refresh() {
        requestRefresh(requestSettings(_state.value.settings), clearsCells = false)
    }

    fun saveSettings(settings: GuailiSettings) {
        viewModelScope.launch {
            try {
                saveSettingsAndAwait(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = _state.value.copy(errorMessage = "Unable to save settings. Please try again.")
            }
        }
    }

    suspend fun saveSettingsAndAwait(settings: GuailiSettings) {
        settingsSource.save(settings)
    }

    fun setLayoutMode(layoutMode: LayoutMode) {
        if (_state.value.settings.layoutMode == layoutMode) return
        saveSettings(_state.value.settings.copy(layoutMode = layoutMode))
    }

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) {
            if (foreground) updateStaleStatus()
            return
        }
        isForeground = foreground
        if (foreground) {
            val stale = updateStaleStatus()
            if (autoRefreshEnabled) {
                if (stale) refresh()
                restartAutoRefresh()
            }
        } else {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
            staleStatusJob?.cancel()
            staleStatusJob = null
        }
    }

    private fun requestRefresh(settings: GuailiSettings, clearsCells: Boolean) {
        if (refreshJob?.isActive == true) {
            pendingRefreshSettings = settings
            pendingRefreshClearsCells = pendingRefreshClearsCells || clearsCells
            return
        }

        refreshJob = viewModelScope.launch {
            var nextSettings = settings
            var nextClearsCells = clearsCells
            while (true) {
                refreshNow(nextSettings, nextClearsCells)

                val pendingSettings = pendingRefreshSettings ?: break
                nextSettings = pendingSettings
                nextClearsCells = pendingRefreshClearsCells
                pendingRefreshSettings = null
                pendingRefreshClearsCells = false
            }
        }
    }

    private suspend fun refreshNow(settings: GuailiSettings, clearsCells: Boolean = false) {
        // A matching cache may have restored while the previous configuration's request drained.
        val clearsDisplayedCells = clearsCells && latestSnapshotSettings?.let { sameDataRequest(it, settings) } != true
        val hadData = _state.value.cells.isNotEmpty() && !clearsDisplayedCells
        _state.value = _state.value.copy(
            symbols = settings.symbols,
            intervals = settings.intervals,
            cells = if (clearsDisplayedCells) emptyMap() else _state.value.cells,
            isLoading = !hadData,
            isRefreshing = hadData,
            errorMessage = null,
            isStale = false,
        )

        val persisted = _state.value.settings
        val temporaryRange = settings.symbols.any { it !in persisted.symbols } ||
            settings.intervals.any { it !in persisted.intervals }
        val selectedRefresher = if (temporaryRange) temporaryRefresher else refresher
        when (val result = selectedRefresher.refresh(settings)) {
            is GuailiResult.Success -> {
                if (!sameDataRequest(requestSettings(_state.value.settings), settings)) return

                val snapshot = result.value
                latestSnapshot = snapshot
                latestSnapshotSettings = settings
                val table = snapshot.table
                _state.value = _state.value.copy(
                    symbols = table.symbols,
                    intervals = table.intervals,
                    cells = table.cells,
                    isLoading = false,
                    isRefreshing = false,
                    lastUpdatedAt = snapshot.serverClock?.serverTimeMillis?.takeIf { it > 0 } ?: snapshot.updatedAt,
                    errorMessage = null,
                    isStale = false,
                )
                scheduleStaleStatusUpdate()
                if (!temporaryRange) onSnapshotUpdated()
            }
            is GuailiResult.Failure -> {
                if (!sameDataRequest(requestSettings(_state.value.settings), settings)) return

                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = result.message,
                    isStale = _state.value.cells.isNotEmpty(),
                )
            }
        }
    }

    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        if (!isForeground) return
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                val seconds = _state.value.settings.autoRefreshSeconds.coerceAtLeast(1)
                delay(seconds * 1000L)
                refresh()
            }
        }
    }

    private fun updateStaleStatus(): Boolean {
        val updatedAt = _state.value.lastUpdatedAt ?: return false
        val deviceTime = snapshotSink.currentDeviceTime()
        val snapshot = latestSnapshot
        val stale = if (deviceTime != null && snapshot?.serverClock != null) {
            val time = assessGuailiTime(snapshot, deviceTime)
            !time.available || time.cacheAgeMillis!! >= GUAILI_STALE_AFTER_MILLIS
        } else isGuailiSnapshotStale(updatedAt, nowMillis())
        if (_state.value.isStale != stale) {
            _state.value = _state.value.copy(isStale = stale)
        }
        scheduleStaleStatusUpdate()
        return stale
    }

    private fun scheduleStaleStatusUpdate() {
        staleStatusJob?.cancel()
        staleStatusJob = null
        if (!isForeground || !autoRefreshEnabled || _state.value.isStale) return

        val updatedAt = _state.value.lastUpdatedAt ?: return
        val deviceTime = snapshotSink.currentDeviceTime()
        val snapshot = latestSnapshot
        val cacheAge = if (deviceTime != null && snapshot?.serverClock != null) {
            assessGuailiTime(snapshot, deviceTime).cacheAgeMillis ?: GUAILI_STALE_AFTER_MILLIS
        } else nowMillis() - updatedAt
        val remainingMillis = (GUAILI_STALE_AFTER_MILLIS - cacheAge).coerceAtLeast(1L)
        staleStatusJob = viewModelScope.launch {
            delay(remainingMillis)
            updateStaleStatus()
        }
    }
}

internal fun sameDataRequest(first: GuailiSettings, second: GuailiSettings): Boolean =
    first.copy(
        symbolDisplayMode = second.symbolDisplayMode,
        symbolColumnWidthMode = second.symbolColumnWidthMode,
        tableDensity = second.tableDensity,
        layoutMode = second.layoutMode,
        groupLayoutSize = second.groupLayoutSize,
        autoRefreshSeconds = second.autoRefreshSeconds,
    ) == second
