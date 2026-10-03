package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignalTrend
import com.gouge.guaili.domain.GuailiSignalPhase
import org.junit.Assert.*
import org.junit.Test

class ServerSignalsPresentationTest {
    private val now = 1_000_000L
    private val url = "http://localhost:3005/"
    private val intervals = listOf("1", "2", "3", "5", "8")
    private val signal = ServerSignalStructure("signal-1", "extreme", "positive",
        listOf(ServerSignalRun("positive", intervals)), 5, 5, "8", now - 500, now - 500, now - 500)
    private val row = ServerSymbolSignals("BTCUSDT", "ready", now - 1000, signals = listOf(signal),
        perIntervalQuality = intervals.map { ServerIntervalEvidence(it, "ready", isClosed = false, closeTime = now + 60_000) })
    private val response = ServerSignalsResponse(true, "ready", now, now - 1000, results = listOf(row))
    private val config = WidgetConfig(listOf("BTCUSDT"), emptyList(), WidgetMode.SignalsV2)
    private val device = GuailiDeviceTime(now - 86_000, 5_000, 1)
    private fun snapshot(value: ServerSignalsResponse = response) = ServerSignalsSnapshot(value, device.wallMillis, url,
        GuailiServerClock(now, 5_000, 1, 100))
    private fun state(value: ServerSignalsSnapshot? = snapshot(), selected: WidgetConfig = config,
        current: GuailiDeviceTime = device, failure: ServerSignalsFailure? = null) =
        serverSignalsWidgetState(value, failure, selected, url, current)

    @Test fun dynamic_candles_and_device_clock_offset_do_not_hide_valid_server_signals() {
        val state = state()
        assertEquals("ready", state.status)
        assertEquals(1, state.signals.size)
        assertEquals(now, state.nowMillis)
        assertEquals("上方乖离共振", serverSignalTitle(state.signals.single().signal))
        assertEquals("1m–8m · 5级共振", serverSignalRange(signal))
    }

    @Test fun old_mode_stays_default_and_v2_type_switches_only_filter_server_results() {
        assertEquals(WidgetMode.Signals, WidgetConfig(listOf("BTCUSDT"), intervals).mode)
        assertEquals(0, state(selected = config.copy(enabledSignalKinds = setOf(GuailiSignalKind.Compression))).signals.size)
        assertEquals(1, state().signals.size)
    }

    @Test fun expired_result_unknown_source_and_reboot_never_show_old_active_signals() {
        assertEquals("stale", state(current = device.copy(elapsedMillis = 22_000)).status)
        assertTrue(state(current = device.copy(elapsedMillis = 22_000)).signals.isEmpty())
        assertEquals("unavailable", state(snapshot().copy(baseUrl = "http://other:3005/")).status)
        assertEquals("time_uncertain", state(current = device.copy(bootCount = 2)).status)
    }

    @Test fun disabled_config_error_and_warmup_have_different_empty_states() {
        assertEquals("disabled", state(snapshot(response.copy(enabled = false, status = "disabled"))).status)
        assertEquals("config_error", state(snapshot(response.copy(enabled = false, status = "config_error"))).status)
        assertEquals("warming_up", state(snapshot(response.copy(status = "warming_up", evaluatedAt = null, results = emptyList()))).status)
    }

    @Test fun partial_unrelated_period_does_not_block_a_valid_server_run() {
        val partial = row.copy(dataStatus = "degraded", perIntervalQuality = row.perIntervalQuality + ServerIntervalEvidence("D", "warming_up"))
        assertEquals(1, state(snapshot(response.copy(status = "degraded", results = listOf(partial)))).signals.size)
        val affected = row.copy(perIntervalQuality = row.perIntervalQuality.map { if (it.interval == "3") it.copy(availability = "stale") else it })
        assertTrue(state(snapshot(response.copy(results = listOf(affected)))).signals.isEmpty())
    }

    @Test fun network_failure_is_reported_without_changing_the_successful_cache() {
        val failed = state(failure = ServerSignalsFailure("服务器连接失败", snapshot().updatedAt + 1, url))
        assertEquals(1, failed.signals.size)
        assertTrue(failed.warning!!.contains("服务器连接失败"))
    }

    @Test fun unknown_or_malformed_server_structures_are_skipped_without_crashing() {
        val bad = row.copy(signals = listOf(signal.copy(kind = "future-kind"), signal.copy(runs = emptyList())))
        assertTrue(state(snapshot(response.copy(results = listOf(bad)))).signals.isEmpty())
    }

    @Test fun server_evidence_uses_the_same_card_labels_and_actual_server_ma_parameters() {
        val evidence = row.copy(perIntervalQuality = row.perIntervalQuality.map {
            it.copy(longTrend = true, shortTrend = false)
        })
        val wire = response.copy(results = listOf(evidence), indicatorConfig = ServerSignalIndicatorConfig("SMA", 50))
        val displayed = requireNotNull(serverWidgetPresentation(ServerWidgetSignal("BTCUSDT", signal), wire, now))
        assertEquals("上方乖离共振", signalTitle(displayed))
        assertEquals("1m–8m", signalRangeLabel(displayed))
        assertEquals("SMA50 ↑", signalCompactTrend(displayed, serverMovingAverageLabel(wire)))
        assertEquals(GuailiSignalPhase.Formed, displayed.phase)
        assertEquals("新出现", signalPhaseLabel(displayed))
    }

    @Test fun missing_server_parameters_or_trend_are_explicitly_unknown() {
        val displayed = requireNotNull(serverWidgetPresentation(ServerWidgetSignal("BTCUSDT", signal), response, now))
        assertEquals(GuailiSignalTrend.Unknown, displayed.trend)
        assertEquals("均线? 未知", signalCompactTrend(displayed, serverMovingAverageLabel(response)))
    }

    @Test fun ongoing_near_zero_uses_the_existing_compact_phase_label() {
        val compression = signal.copy(kind = "compression", direction = "neutral", formedAt = null,
            firstObservedAt = now - 60_000, lastChangedAt = now - 60_000)
        val displayed = requireNotNull(serverWidgetPresentation(ServerWidgetSignal("BTCUSDT", compression), response, now))
        assertEquals("多周期近均线", signalTitle(displayed))
        assertEquals("持续近零", signalPhaseLabel(displayed))
    }
}
