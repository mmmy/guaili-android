package com.gouge.guaili.data

import kotlinx.serialization.Serializable

/** The V2 endpoint uses Unix milliseconds for every time field. */
@Serializable
data class ServerSignalsResponse(
    val enabled: Boolean,
    val status: String,
    val serverTime: Long,
    val evaluatedAt: Long? = null,
    val evaluationIntervalMs: Long = 5_000L,
    val configHash: String = "",
    val ruleVersion: String = "",
    val runId: String = "",
    val snapshotVersion: Long = 0,
    val candleMode: String = "live",
    val evaluationMode: String = "sampled_live",
    val computeDurationMs: Long = 0,
    val configError: String? = null,
    val results: List<ServerSymbolSignals> = emptyList(),
    val indicatorConfig: ServerSignalIndicatorConfig? = null,
)

@Serializable
data class ServerSignalIndicatorConfig(val maType: String, val maLength: Int)

@Serializable
data class ServerSymbolSignals(
    val symbol: String,
    val dataStatus: String = "warming_up",
    val sampledAt: Long = 0,
    val marketSequence: Long? = null,
    val generation: Long? = null,
    val lastMarketEventTime: Long? = null,
    val primarySignal: String? = null,
    val signals: List<ServerSignalStructure> = emptyList(),
    val perIntervalQuality: List<ServerIntervalEvidence> = emptyList(),
    val missingIntervals: List<String> = emptyList(),
)

@Serializable
data class ServerSignalStructure(
    val id: String,
    val kind: String,
    val direction: String,
    val runs: List<ServerSignalRun> = emptyList(),
    val levelCount: Int = 0,
    val totalLevelCount: Int = levelCount,
    val anchorInterval: String = "",
    val firstObservedAt: Long? = null,
    val formedAt: Long? = null,
    val lastChangedAt: Long? = null,
)

@Serializable
data class ServerSignalRun(
    val direction: String,
    val intervals: List<String> = emptyList(),
    val minAbsValue: Int = 0,
    val maxAbsValue: Int = 0,
    val meanAbsValue: Double = 0.0,
    val maxAbsGuaili: Double? = null,
    val meanAbsGuaili: Double? = null,
)

@Serializable
data class ServerIntervalEvidence(
    val interval: String,
    val availability: String,
    val reason: String? = null,
    val value: Int? = null,
    val guaili: Double? = null,
    val ma: Double? = null,
    val atr14: Double? = null,
    val atrRank: Double? = null,
    val longTrend: Boolean? = null,
    val shortTrend: Boolean? = null,
    val historyCount: Int = 0,
    val openTime: Long? = null,
    val closeTime: Long? = null,
    val marketEventTime: Long? = null,
    val isClosed: Boolean? = null,
)
