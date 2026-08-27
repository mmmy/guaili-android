package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.GuailiRefreshUseCase
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.data.GuailiSnapshotSink
import com.gouge.guaili.data.GUAILI_STALE_AFTER_MILLIS
import com.gouge.guaili.data.isGuailiSnapshotStale
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.LayoutMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
)

class GuailiViewModel(
    private val settingsSource: GuailiSettingsSource,
    private val fetcherFactory: GuailiFetcherFactory = GuailiFetcherFactory { baseUrl ->
        val repository = com.gouge.guaili.data.GuailiRepository.create(baseUrl)
        GuailiFetcher(repository::fetch)
    },
    snapshotSink: GuailiSnapshotSink = GuailiSnapshotSink { },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val autoRefreshEnabled: Boolean = true,
    private val onSnapshotUpdated: suspend () -> Unit = { },
) : ViewModel() {
    private val _state = MutableStateFlow(GuailiTableState())
    val state: StateFlow<GuailiTableState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var staleStatusJob: Job? = null
    private var refreshJob: Job? = null
    private var pendingRefreshSettings: GuailiSettings? = null
    private var pendingRefreshClearsCells: Boolean = false
    private val refresher = GuailiRefreshUseCase(fetcherFactory, snapshotSink, nowMillis)
    private var isForeground: Boolean = true
    private var hasObservedSettings = false

    init {
        viewModelScope.launch {
            settingsSource.settings.collect { settings ->
                val requestSettingsChanged = !hasObservedSettings ||
                    !sameDataRequest(_state.value.settings, settings)
                hasObservedSettings = true
                _state.value = _state.value.copy(
                    settings = settings,
                    symbols = settings.symbols,
                    intervals = settings.intervals,
                    cells = if (requestSettingsChanged) emptyMap() else _state.value.cells,
                    isStale = if (requestSettingsChanged) false else _state.value.isStale,
                )
                if (requestSettingsChanged) {
                    requestRefresh(settings, clearsCells = true)
                }
                if (autoRefreshEnabled) {
                    restartAutoRefresh()
                }
            }
        }
    }

    fun refresh() {
        requestRefresh(_state.value.settings, clearsCells = false)
    }

    fun saveSettings(settings: GuailiSettings) {
        viewModelScope.launch {
            settingsSource.save(settings)
        }
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
        val hadData = _state.value.cells.isNotEmpty() && !clearsCells
        _state.value = _state.value.copy(
            symbols = settings.symbols,
            intervals = settings.intervals,
            cells = if (clearsCells) emptyMap() else _state.value.cells,
            isLoading = !hadData,
            isRefreshing = hadData,
            errorMessage = null,
            isStale = false,
        )

        when (val result = refresher.refresh(settings)) {
            is GuailiResult.Success -> {
                if (!sameDataRequest(_state.value.settings, settings)) return

                val snapshot = result.value
                val table = snapshot.table
                _state.value = _state.value.copy(
                    symbols = table.symbols,
                    intervals = table.intervals,
                    cells = table.cells,
                    isLoading = false,
                    isRefreshing = false,
                    lastUpdatedAt = snapshot.updatedAt,
                    errorMessage = null,
                    isStale = false,
                )
                scheduleStaleStatusUpdate()
                onSnapshotUpdated()
            }
            is GuailiResult.Failure -> {
                if (!sameDataRequest(_state.value.settings, settings)) return

                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = result.message,
                    isStale = hadData,
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
        val stale = isGuailiSnapshotStale(updatedAt, nowMillis())
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
        val remainingMillis = (GUAILI_STALE_AFTER_MILLIS - (nowMillis() - updatedAt)).coerceAtLeast(1L)
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
