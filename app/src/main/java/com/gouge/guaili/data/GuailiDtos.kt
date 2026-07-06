package com.gouge.guaili.data

import kotlinx.serialization.Serializable

@Serializable
data class GuailiResponse(
    val symbols: List<String>,
    val intervals: List<String>,
    val limit: Int,
    val calcLimit: Int,
    val closedOnly: Boolean,
    val timezone: String? = null,
    val serverTime: Long? = null,
    val results: List<GuailiSymbolResult>,
)

@Serializable
data class GuailiSymbolResult(
    val symbol: String,
    val series: List<GuailiSeries> = emptyList(),
)

@Serializable
data class GuailiSeries(
    val interval: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val count: Int = 0,
    val latest: GuailiPoint? = null,
    val data: List<GuailiPoint> = emptyList(),
)

@Serializable
data class GuailiPoint(
    val openTime: String? = null,
    val closeTime: String? = null,
    val ma: Double? = null,
    val atr14: Double? = null,
    val atrRank: Double? = null,
    val rankFilter: Boolean? = null,
    val guaili: Double? = null,
    val value: Int? = null,
    val longTrend: Boolean? = null,
    val shortTrend: Boolean? = null,
    val isClosed: Boolean? = null,
)
