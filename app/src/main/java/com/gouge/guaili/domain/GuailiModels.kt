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
)

@Serializable
data class GuailiTable(
    val symbols: List<String>,
    val intervals: List<String>,
    val cells: Map<String, Map<String, GuailiCell>>,
)
