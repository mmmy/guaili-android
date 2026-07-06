package com.gouge.guaili.settings

data class GuailiSettings(
    val baseUrl: String,
    val symbols: List<String>,
    val intervals: List<String>,
    val autoRefreshSeconds: Int,
    val limit: Int,
    val calcLimit: Int,
    val closedOnly: Boolean,
    val maLength: Int,
    val maType: String,
    val atrLen: Int,
    val atrPercentLen: Int,
    val maxAtrRank: Double,
    val slopeMul: Double,
    val useSlope: Boolean,
) {
    companion object {
        fun defaults(): GuailiSettings = GuailiSettings(
            baseUrl = "http://192.168.1.100:8080/",
            symbols = listOf(
                "BTCUSDT",
                "XAUUSDT",
                "CLUSDT",
                "QQQUSDT",
                "HKHYNIXUSDT",
            ),
            intervals = listOf(
                "1",
                "2",
                "3",
                "5",
                "8",
                "10",
                "15",
                "20",
                "30",
                "45",
                "60",
                "90",
                "120",
                "180",
                "240",
                "360",
                "480",
                "720",
                "D",
                "2D",
                "3D",
                "4D",
                "W",
            ),
            autoRefreshSeconds = 5,
            limit = 1,
            calcLimit = 500,
            closedOnly = false,
            maLength = 20,
            maType = "EMA",
            atrLen = 1,
            atrPercentLen = 20,
            maxAtrRank = 100.0,
            slopeMul = 0.1,
            useSlope = true,
        )
    }
}

fun parseCsv(value: String): List<String> =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
