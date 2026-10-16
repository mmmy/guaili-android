package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.*
import com.gouge.guaili.signals.*

internal data class MarketProjectionRequest(
    val generation: Long,
    val settings: GuailiSettings,
    val preferences: MarketSignalPreferences,
    val snapshot: ServerSignalsSnapshot?,
    val failure: ServerSignalsFailure?,
    val needsPullbacks: Boolean,
)

internal data class MarketProjectionResult(
    val presentation: ServerSignalsState,
    val pullbacks: PullbackProjection,
    val availableSymbols: List<String>,
    val clockMessage: String,
    val nextCheckDelayMillis: Long,
    val bootCount: Int?,
    val validUntilElapsedMillis: Long?,
)

/** Confined to one background worker; clock checks reuse content until its actual boundary. */
internal class MarketSignalsProjector {
    private val presentationCache = ServerSignalsPresentationCache()
    private var projectedSnapshot: ServerSignalsSnapshot? = null
    private var projectedRules: PullbackRules? = null
    private var projectedSymbols: List<String> = emptyList()
    private var projectedBoot: Int? = null
    private var projectedRefreshMillis: Long? = null
    private var projectedTimeFailure: String? = null
    private var projection = PullbackProjection()
    private val hiddenProjection = PullbackProjection()
    private var availableSnapshot: ServerSignalsSnapshot? = null
    private var availableSettingsSymbols: List<String>? = null
    private var availableSelectedSymbols: List<String>? = null
    private var availableSymbols: List<String> = emptyList()

    fun project(input: MarketProjectionRequest, device: GuailiDeviceTime?): MarketProjectionResult {
        val settings = input.settings
        val prefs = input.preferences
        val snapshot = input.snapshot
        val symbols = prefs.symbols ?: settings.symbols
        val refreshMillis = settings.autoRefreshSeconds.coerceAtLeast(1).toLong() * 1_000L
        val display = presentationCache.state(snapshot, input.failure, ServerSignalDisplayConfig(symbols, prefs.kinds),
            settings.baseUrl, device, refreshMillis)
        val time = if (snapshot != null && device != null) assessServerSignalsTime(snapshot, device) else null
        val now = time?.nowMillis
        val timeFailure = if (device == null) "时间暂不可确认" else time?.unavailableReason
        if (input.needsPullbacks && (projectedSnapshot !== snapshot || projectedRules != prefs.pullback.rules ||
                projectedSymbols != settings.symbols || projectedBoot != device?.bootCount ||
                projectedRefreshMillis != refreshMillis || projectedTimeFailure != timeFailure ||
                now == null && projection.ready ||
                projection.nextChangeAt?.let { now != null && now >= it } == true)) {
            projection = projectPullbacks(snapshot, prefs.pullback.rules, settings.symbols, device, refreshMillis)
            projectedSnapshot = snapshot
            projectedRules = prefs.pullback.rules
            projectedSymbols = settings.symbols
            projectedBoot = device?.bootCount
            projectedRefreshMillis = refreshMillis
            projectedTimeFailure = timeFailure
        }
        if (availableSnapshot !== snapshot || availableSettingsSymbols != settings.symbols || availableSelectedSymbols != symbols) {
            availableSymbols = (settings.symbols + snapshot?.response?.results.orEmpty().map { it.symbol } + symbols).distinct()
            availableSnapshot = snapshot
            availableSettingsSymbols = settings.symbols
            availableSelectedSymbols = symbols
        }
        val pullbacks = if (input.needsPullbacks) projection else hiddenProjection
        val nextBoundary = listOfNotNull(presentationCache.nextChangeAt, pullbacks.nextChangeAt).minOrNull()
        val untilBoundary = nextBoundary?.let { at -> now?.let { (at - it).coerceAtLeast(1L) } }
        val nextDelay = untilBoundary?.coerceAtMost(1_000L) ?: 1_000L
        val validUntil = if (untilBoundary != null && device != null) {
            if (device.elapsedMillis > Long.MAX_VALUE - untilBoundary) Long.MAX_VALUE else device.elapsedMillis + untilBoundary
        } else null
        return MarketProjectionResult(display, pullbacks, availableSymbols,
            time?.let { it.unavailableReason ?: it.correctionMessage ?: "已按服务器时间校准" } ?: "刷新后校准时间",
            nextDelay, device?.bootCount, validUntil)
    }
}
