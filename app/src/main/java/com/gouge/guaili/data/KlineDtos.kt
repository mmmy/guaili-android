package com.gouge.guaili.data

import kotlinx.serialization.Serializable

@Serializable
data class KlineEnvelope(
    val symbol: String,
    val intervals: List<String>,
    val limit: Int,
    val closedOnly: Boolean,
    val timezone: String? = null,
    val serverTime: Long? = null,
    val series: List<KlineSeriesDto>,
)

@Serializable
data class KlineSeriesDto(
    val interval: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val count: Int = 0,
    val data: List<KlineRowDto> = emptyList(),
)

@Serializable
data class KlineRowDto(
    val symbol: String,
    val interval: String,
    val candle: KlineCandleDto,
)

@Serializable
data class KlineCandleDto(
    val openTime: String,
    val closeTime: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
    val tradeCount: Long,
    val isClosed: Boolean,
)
