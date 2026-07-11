package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.GuailiResponse
import com.gouge.guaili.data.GuailiRepository
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.toTable
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.LayoutMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface GuailiFetcher {
    suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse>
}

fun interface GuailiFetcherFactory {
    fun create(baseUrl: String): GuailiFetcher
}

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
        val repository = GuailiRepository.create(baseUrl)
        GuailiFetcher { settings -> repository.fetch(settings) }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val autoRefreshEnabled: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(GuailiTableState())
    val state: StateFlow<GuailiTableState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null
    private var pendingRefreshSettings: GuailiSettings? = null
    private var pendingRefreshClearsCells: Boolean = false
    private var fetcher: GuailiFetcher? = null
    private var fetcherBaseUrl: String? = null
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
        if (isForeground == foreground) return
        isForeground = foreground
        if (foreground && autoRefreshEnabled) {
            restartAutoRefresh()
        } else {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
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

        val currentFetcher = try {
            fetcherFor(settings.baseUrl)
        } catch (error: Exception) {
            if (sameDataRequest(_state.value.settings, settings)) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = error.message ?: "Invalid base URL",
                    isStale = hadData,
                )
            }
            return
        }

        when (val result = currentFetcher.fetch(settings)) {
            is GuailiResult.Success -> {
                if (!sameDataRequest(_state.value.settings, settings)) return

                val table = result.value.toTable(settings.symbols, settings.intervals)
                _state.value = _state.value.copy(
                    symbols = table.symbols,
                    intervals = table.intervals,
                    cells = table.cells,
                    isLoading = false,
                    isRefreshing = false,
                    lastUpdatedAt = nowMillis(),
                    errorMessage = null,
                    isStale = false,
                )
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

    private fun fetcherFor(baseUrl: String): GuailiFetcher {
        if (fetcher == null || fetcherBaseUrl != baseUrl) {
            fetcher = fetcherFactory.create(baseUrl)
            fetcherBaseUrl = baseUrl
        }
        return checkNotNull(fetcher)
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
