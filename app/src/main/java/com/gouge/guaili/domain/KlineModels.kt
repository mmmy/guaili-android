package com.gouge.guaili.domain

data class Kline(
    val openTimeMillis: Long,
    val closeTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
    val tradeCount: Long,
    val isClosed: Boolean,
)

enum class ChannelTrend {
    Long,
    Short,
    Neutral,
}

data class GuailiChannelPoint(
    val ema20: Double?,
    val atr14: Double?,
    val upper: Double?,
    val lower: Double?,
    val guaili: Double?,
    val trend: ChannelTrend,
)

data class KlineChartRow(
    val candle: Kline,
    val channel: GuailiChannelPoint,
)

interface KlineIndicator<T> {
    val id: String
    val name: String

    fun calculate(candles: List<Kline>): List<T>
}
