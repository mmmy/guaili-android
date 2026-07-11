package com.gouge.guaili.settings

data class GuailiSettings(
    val baseUrl: String,
    val symbols: List<String>,
    val symbolDisplayMode: SymbolDisplayMode,
    val symbolColumnWidthMode: SymbolColumnWidthMode,
    val tableDensity: TableDensity,
    val layoutMode: LayoutMode,
    val groupLayoutSize: GroupLayoutSize,
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
                "SKHYNIXUSDT",
            ),
            symbolDisplayMode = SymbolDisplayMode.Auto,
            symbolColumnWidthMode = SymbolColumnWidthMode.Auto,
            tableDensity = TableDensity.Compact,
            layoutMode = LayoutMode.Auto,
            groupLayoutSize = GroupLayoutSize.Standard,
            intervals = DefaultIntervals,
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

internal val DefaultIntervals = listOf(
    "10D",
    "W",
    "4D",
    "3D",
    "2D",
    "D",
    "720",
    "480",
    "360",
    "240",
    "180",
    "120",
    "90",
    "60",
    "45",
    "30",
    "20",
    "15",
    "10",
    "8",
    "5",
    "3",
    "2",
    "1",
    "45S",
    "30S",
    "15S",
    "10S",
)

internal val LegacyDefaultIntervals = listOf(
    "1", "2", "3", "5", "8", "10", "15", "20", "30", "45", "60", "90",
    "120", "180", "240", "360", "480", "720", "D", "2D", "3D", "4D", "W",
)

enum class SymbolDisplayMode(val label: String) {
    Auto("Auto"),
    Full("Full code"),
    Base("Hide quote suffix"),
}

enum class SymbolColumnWidthMode(val label: String) {
    Auto("Auto"),
    Compact("Compact"),
    Standard("Standard"),
    Wide("Wide"),
}

enum class TableDensity(val label: String) {
    Compact("Compact"),
    Comfortable("Comfortable"),
}

enum class LayoutMode(val label: String) {
    Auto("Auto"),
    Table("Table"),
    Groups("Groups"),
}

enum class GroupLayoutSize(val label: String) {
    Standard("Standard"),
    Compact("Compact"),
    TenColumns("10 columns"),
}

fun parseCsv(value: String): List<String> =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
