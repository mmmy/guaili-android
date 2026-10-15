package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.*
import com.gouge.guaili.signals.*
import com.gouge.guaili.pullback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class MarketSignalsUiState(
    val preferences: MarketSignalPreferences = MarketSignalPreferences(),
    val symbols: List<String> = emptyList(),
    val availableSymbols: List<String> = emptyList(),
    val snapshot: ServerSignalsSnapshot? = null,
    val presentation: ServerSignalsState = ServerSignalsState("unavailable", "等待服务器信号", "点击刷新获取信号 v2"),
    val isRefreshing: Boolean = false,
    val preferenceError: String? = null,
    val clockMessage: String = "刷新后校准时间",
    val refreshError: String? = null,
    val autoRefreshSeconds: Int = 5,
    val pullbacks: PullbackProjection = PullbackProjection(),
)

internal class ServerSignalsViewModel(
    private val settingsSource: GuailiSettingsSource,
    private val preferencesSource: MarketSignalPreferencesSource,
    private val snapshotSource: ServerSignalsSource,
    private val refresher: ServerSignalsRefreshUseCase = ServerSignalsRefreshUseCase(snapshotSink = snapshotSource),
    private val onRefreshed: suspend (GuailiSettings, GuailiResult<ServerSignalsSnapshot>) -> Unit = { _, _ -> },
) : ViewModel() {
    private val mutableState = MutableStateFlow(MarketSignalsUiState())
    val state = mutableState.asStateFlow()
    private var settings = GuailiSettings.defaults()
    private var preferences = MarketSignalPreferences()
    private var snapshot: ServerSignalsSnapshot? = null
    private var failure: ServerSignalsFailure? = null
    private var foreground = false
    private var polling: Job? = null
    private var expiry: Job? = null
    private var request: Job? = null
    private var requestGeneration = 0
    private val alerts = PullbackAlerts()
    private val mutablePullbackAlerts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pullbackAlerts = mutablePullbackAlerts.asSharedFlow()
    private var pullbackActive = false
    private var projectedSnapshot: ServerSignalsSnapshot? = null
    private var projectedRules: PullbackRules? = null
    private var projectedSymbols: List<String> = emptyList()
    private var projectedBoot: Int? = null
    private var projectedRefreshIntervalMillis: Long? = null
    private var projection: PullbackProjection = PullbackProjection()
    private val presentationCache = ServerSignalsPresentationCache()
    private var observedProjection: PullbackProjection? = null
    private var availableSnapshot: ServerSignalsSnapshot? = null
    private var availableSettingsSymbols: List<String>? = null
    private var availableSelectedSymbols: List<String>? = null
    private var availableSymbols: List<String> = emptyList()
    private var boundaryCheckDelayMillis = 1_000L

    private fun resetAlerts() { alerts.reset(); observedProjection = null }

    init {
        viewModelScope.launch {
            combine(settingsSource.settings, preferencesSource.preferences,
                snapshotSource.snapshots, snapshotSource.failures) { config, prefs, value, error ->
                Observed(config, prefs, value, error)
            }.collect { observed ->
                val sourceChanged = normalizeServerSignalsBaseUrl(settings.baseUrl) !=
                    normalizeServerSignalsBaseUrl(observed.settings.baseUrl)
                val refreshIntervalChanged = settings.autoRefreshSeconds != observed.settings.autoRefreshSeconds
                if (sourceChanged || preferences.pullback.rules != observed.preferences.pullback.rules ||
                    preferences.pullback.soundEnabled != observed.preferences.pullback.soundEnabled) resetAlerts()
                settings = observed.settings
                preferences = observed.preferences
                snapshot = observed.snapshot?.takeIf { it.belongsTo(settings.baseUrl) }
                failure = observed.failure
                rebuild()
                if (sourceChanged) {
                    request?.cancel()
                    if (foreground) restartPolling()
                } else if (refreshIntervalChanged && foreground) {
                    restartPolling(refreshImmediately = false)
                }
            }
        }
    }

    fun setView(view: MarketView) = updatePreferences { it.copy(view = view) }
    fun setOnlySignalCells(enabled: Boolean) = updatePreferences { it.copy(onlySignalCells = enabled) }
    fun setWidePaneCollapsed(collapsed: Boolean) = updatePreferences { it.copy(widePaneCollapsed = collapsed) }
    fun setOnlyPullbackCandidates(enabled: Boolean) = updatePreferences {
        it.copy(pullback = it.pullback.copy(onlyCandidates = enabled))
    }
    fun setPullbackSound(enabled: Boolean) = updatePreferences {
        it.copy(pullback = it.pullback.copy(soundEnabled = enabled))
    }
    fun setPullbackRules(rules: PullbackRules) {
        if (rules.error() == null) updatePreferences { it.copy(pullback = it.pullback.copy(rules = rules)) }
    }
    fun setPullbackParameters(rules: PullbackRules, extensionBars: Int) {
        if (rules.error() == null && extensionBars in 1..1000) updatePreferences {
            it.copy(pullback = it.pullback.copy(rules = rules, extensionBars = extensionBars))
        }
    }
    fun setPullbackPrimary(key: String, id: Long) = updatePreferences {
        it.copy(pullback = it.pullback.copy(primaryAlerts = it.pullback.primaryAlerts + (key to id)))
    }
    fun setPullbackActive(active: Boolean) {
        if (pullbackActive == active) return
        pullbackActive = active
        resetAlerts()
        rebuild()
    }
    fun toggleSymbol(symbol: String) = updatePreferences { prefs ->
        val selected = prefs.symbols ?: settings.symbols
        prefs.copy(symbols = if (symbol in selected) selected - symbol else selected + symbol)
    }
    fun selectAllSymbols() = updatePreferences { it.copy(symbols = mutableState.value.availableSymbols) }
    fun toggleKind(kind: GuailiSignalKind) = updatePreferences { prefs ->
        prefs.copy(kinds = if (kind in prefs.kinds) prefs.kinds - kind else prefs.kinds + kind)
    }

    private fun updatePreferences(transform: (MarketSignalPreferences) -> MarketSignalPreferences) {
        viewModelScope.launch {
            try {
                preferencesSource.update(transform)
                mutableState.value = mutableState.value.copy(preferenceError = null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(preferenceError = "无法保存显示设置，请重试")
            }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        resetAlerts()
        polling?.cancel()
        expiry?.cancel()
        request?.cancel()
        if (active) {
            rebuild()
            restartPolling()
            // The cheap clock-health guard remains active when requests fail or hang.
            // Presentation content and phases are cached until their actual time boundaries.
            expiry = viewModelScope.launch { while (foreground) { delay(boundaryCheckDelayMillis); rebuild() } }
        }
    }

    private fun refreshIntervalMillis() = settings.autoRefreshSeconds.coerceAtLeast(1).toLong() * 1_000L

    private fun restartPolling(refreshImmediately: Boolean = true) {
        polling?.cancel()
        polling = viewModelScope.launch {
            if (!refreshImmediately) {
                request?.join()
                delay(refreshIntervalMillis())
            }
            while (foreground) {
                refresh()
                request?.join()
                delay(refreshIntervalMillis())
            }
        }
    }

    fun refresh() {
        if (request?.isActive == true) return
        val requestedSettings = settings
        val generation = ++requestGeneration
        request = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshing = true)
            try {
                val result = refresher.refresh(requestedSettings.baseUrl, reuseWithinMillis = 1_000L)
                if (normalizeServerSignalsBaseUrl(settings.baseUrl) !=
                    normalizeServerSignalsBaseUrl(requestedSettings.baseUrl)) return@launch
                when (result) {
                    is GuailiResult.Success -> { snapshot = result.value; failure = null }
                    is GuailiResult.Failure -> failure = ServerSignalsFailure(result.message,
                        System.currentTimeMillis(), requestedSettings.baseUrl)
                }
                rebuild()
                try {
                    onRefreshed(requestedSettings, result)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A launcher rendering failure does not invalidate the foreground snapshot.
                }
            } finally {
                if (generation == requestGeneration) mutableState.value = mutableState.value.copy(isRefreshing = false)
            }
        }
    }

    private fun rebuild() {
        val symbols = preferences.symbols ?: settings.symbols
        val device = snapshotSource.currentDeviceTime()
        val display = presentationCache.state(snapshot, failure, ServerSignalDisplayConfig(symbols, preferences.kinds),
            settings.baseUrl, device, refreshIntervalMillis())
        val time = if (snapshot != null && device != null) assessServerSignalsTime(snapshot!!, device) else null
        val now = time?.nowMillis
        val rules = preferences.pullback.rules
        // Reuse sorted evidence between responses; expiry only rebuilds at a quality boundary.
        if (projectedSnapshot !== snapshot || projectedRules != rules || projectedSymbols != settings.symbols ||
            projectedBoot != device?.bootCount || projectedRefreshIntervalMillis != refreshIntervalMillis() ||
            now == null && projection.ready ||
            projection.nextChangeAt?.let { now != null && now >= it } == true) {
            projection = projectPullbacks(snapshot, rules, settings.symbols, device, refreshIntervalMillis())
            projectedSnapshot = snapshot
            projectedRules = rules
            projectedSymbols = settings.symbols
            projectedBoot = device?.bootCount
            projectedRefreshIntervalMillis = refreshIntervalMillis()
        }
        val appeared = if (observedProjection !== projection) {
            observedProjection = projection
            alerts.observe(projection)
        } else false
        boundaryCheckDelayMillis = listOfNotNull(presentationCache.nextChangeAt, projection.nextChangeAt)
            .minOrNull()?.let { at -> now?.let { (at - it).coerceIn(1L, 1_000L) } } ?: 1_000L
        if (appeared && foreground && pullbackActive && preferences.pullback.soundEnabled) mutablePullbackAlerts.tryEmit(Unit)
        if (availableSnapshot !== snapshot || availableSettingsSymbols != settings.symbols || availableSelectedSymbols != symbols) {
            availableSymbols = (settings.symbols + snapshot?.response?.results.orEmpty().map { it.symbol } + symbols).distinct()
            availableSnapshot = snapshot
            availableSettingsSymbols = settings.symbols
            availableSelectedSymbols = symbols
        }
        mutableState.value = mutableState.value.copy(
            preferences = preferences, symbols = symbols, snapshot = snapshot,
            availableSymbols = availableSymbols,
            presentation = display,
            pullbacks = projection,
            autoRefreshSeconds = settings.autoRefreshSeconds.coerceAtLeast(1),
            refreshError = failure?.takeIf {
                normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(settings.baseUrl) &&
                    it.updatedAt >= (snapshot?.updatedAt ?: 0L)
            }?.message,
            clockMessage = time?.let {
                it.unavailableReason ?: it.correctionMessage ?: "已按服务器时间校准"
            } ?: "刷新后校准时间",
        )
    }

    private data class Observed(val settings: GuailiSettings, val preferences: MarketSignalPreferences,
        val snapshot: ServerSignalsSnapshot?, val failure: ServerSignalsFailure?)
}
