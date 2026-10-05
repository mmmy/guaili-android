package com.gouge.guaili.domain

import kotlinx.serialization.Serializable

@Serializable
data class GuailiCell(
    val symbol: String,
    val interval: String,
    val value: Int?,
    val guaili: Double?,
    val ma: Double?,
    val atr14: Double?,
    val atrRank: Double?,
    val rankFilter: Boolean?,
    val longTrend: Boolean?,
    val shortTrend: Boolean?,
    val isClosed: Boolean?,
    val openTime: String?,
    val closeTime: String?,
    // Matrix trends intentionally refer to the preceding candle. Signal context
    // must instead use the trend of the same candle as its guaili value.
    val signalLongTrend: Boolean? = null,
    val signalShortTrend: Boolean? = null,
    val signalAtrReady: Boolean? = null,
    val availability: String? = null,
    val reasonCode: String? = null,
    val reason: String? = null,
    val historyCount: Int? = null,
)

@Serializable
data class GuailiTable(
    val symbols: List<String>,
    val intervals: List<String>,
    val cells: Map<String, Map<String, GuailiCell>>,
    val closedCells: Map<String, Map<String, GuailiCell>> = emptyMap(),
    // Kept independently when the matrix is configured to display closed candles.
    val dynamicCells: Map<String, Map<String, GuailiCell>>? = null,
)
