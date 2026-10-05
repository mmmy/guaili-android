package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.signals.*

// Compatibility adapters keep widget renderers and their tests on the shared rules.
internal typealias ServerWidgetSignal = ServerSignalItem
internal typealias ServerSignalsWidgetState = ServerSignalsState
internal typealias ServerSignalDataIssue = com.gouge.guaili.signals.ServerSignalDataIssue

internal fun serverSignalsWidgetState(snapshot: ServerSignalsSnapshot?, failure: ServerSignalsFailure?,
    config: WidgetConfig, baseUrl: String, deviceTime: GuailiDeviceTime) =
    serverSignalsState(snapshot, failure, ServerSignalDisplayConfig(config.symbols, config.enabledSignalKinds), baseUrl, deviceTime)
internal fun serverSignalDataIssue(evidence: ServerIntervalEvidence) = com.gouge.guaili.signals.serverSignalDataIssue(evidence)
internal fun serverSignalQualityGroups(row: ServerSymbolSignals) = com.gouge.guaili.signals.serverSignalQualityGroups(row)
internal fun serverSignalQualityLines(row: ServerSymbolSignals) = com.gouge.guaili.signals.serverSignalQualityLines(row)
internal fun serverWidgetCandidates(signals: List<ServerSignalStructure>) = serverSignalCandidates(signals)
internal fun serverSignalsCacheLifetime(response: ServerSignalsResponse) = com.gouge.guaili.signals.serverSignalsCacheLifetime(response)
internal fun serverSignalKind(kind: String): GuailiSignalKind? = com.gouge.guaili.signals.serverSignalKind(kind)
internal fun serverSignalTitle(signal: ServerSignalStructure) = com.gouge.guaili.signals.serverSignalTitle(signal)
internal fun serverSignalRange(signal: ServerSignalStructure) = com.gouge.guaili.signals.serverSignalRange(signal)
internal fun serverSignalPhase(signal: ServerSignalStructure, nowMillis: Long?, intervalMillis: Long) =
    com.gouge.guaili.signals.serverSignalPhase(signal, nowMillis, intervalMillis)
internal fun serverWidgetPresentation(item: ServerWidgetSignal, response: ServerSignalsResponse, nowMillis: Long?) =
    serverSignalPresentation(item, response, nowMillis)
internal fun serverMovingAverageLabel(response: ServerSignalsResponse) = com.gouge.guaili.signals.serverMovingAverageLabel(response)
internal fun serverWidgetStatusLabel(state: ServerSignalsWidgetState, narrow: Boolean) = serverSignalStatusLabel(state, narrow)
