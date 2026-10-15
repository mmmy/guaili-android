package com.gouge.guaili.signals

import com.gouge.guaili.data.*

/** Foreground content is immutable between responses; only expiry and phase boundaries change it. */
internal class ServerSignalsPresentationCache {
    private var initialized = false
    private var snapshot: ServerSignalsSnapshot? = null
    private var failure: ServerSignalsFailure? = null
    private var config: ServerSignalDisplayConfig? = null
    private var baseUrl: String? = null
    private var refreshMillis = 0L
    private var timeFailure: String? = null
    private var lastNow: Long? = null
    private var expiresAt: Long? = null
    private var phaseChangesAt: Long? = null
    private var display = ServerSignalsState("unavailable", "等待服务器信号", "点击刷新获取信号 v2")
    val nextChangeAt: Long? get() = listOfNotNull(expiresAt, phaseChangesAt).minOrNull()

    fun state(value: ServerSignalsSnapshot?, error: ServerSignalsFailure?, selection: ServerSignalDisplayConfig,
        source: String, device: GuailiDeviceTime?, refreshIntervalMillis: Long): ServerSignalsState {
        val time = if (value != null && device != null) assessServerSignalsTime(value, device) else null
        val now = time?.nowMillis
        val age = time?.cacheAgeMillis
        val unavailable = if (device == null) "时间暂不可确认，请刷新" else time?.unavailableReason
        val expired = expiresAt?.let { now != null && now >= it } == true
        if (!initialized || snapshot !== value || failure != error || config != selection || baseUrl != source ||
            refreshMillis != refreshIntervalMillis || timeFailure != unavailable || expired ||
            lastNow?.let { now != null && now < it } == true) {
            initialized = true
            snapshot = value
            failure = error
            config = selection
            baseUrl = source
            refreshMillis = refreshIntervalMillis
            timeFailure = unavailable
            display = if (device == null) ServerSignalsState("time_uncertain", "时间暂不可确认，请刷新", "刷新后重新校准时间")
                else serverSignalsState(value, error, selection, source, device, refreshIntervalMillis)
            expiresAt = if (value != null && now != null && age != null) {
                val remaining = serverSignalsPhoneCacheLifetime(value.response, refreshIntervalMillis) - age
                if (remaining >= 0) saturatedAdd(now, remaining + 1) else null
            } else null
            phaseChangesAt = nextServerSignalPhaseChange(display.signals, now, display.evaluationIntervalMs)
        } else if (phaseChangesAt?.let { now != null && now >= it } == true) {
            // The selected structures and quality summary did not change. Only their phase needs a new clock.
            display = display.copy(nowMillis = now)
            phaseChangesAt = nextServerSignalPhaseChange(display.signals, now, display.evaluationIntervalMs)
        }
        lastNow = now
        return display
    }
}

/** Inclusive recent windows end one millisecond after their duration; future events start at their timestamp. */
internal fun nextServerSignalPhaseChange(items: List<ServerSignalItem>, now: Long?, intervalMillis: Long): Long? {
    if (now == null) return null
    val recent = intervalMillis.coerceIn(1_000, 300_000) * 2
    return items.minOfOrNull { item ->
        val signal = item.signal
        val events = listOfNotNull(signal.lastChangedAt.takeIf { it != signal.firstObservedAt },
            signal.formedAt ?: signal.firstObservedAt)
        val current = serverSignalPhase(signal, now, intervalMillis)
        events.flatMap { listOf(it, saturatedAdd(it, recent + 1)) }.distinct().sorted()
            .firstOrNull { it > now && serverSignalPhase(signal, it, intervalMillis) != current } ?: Long.MAX_VALUE
    }?.takeIf { it != Long.MAX_VALUE }
}

private fun saturatedAdd(value: Long, amount: Long): Long =
    if (amount > 0 && value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount
