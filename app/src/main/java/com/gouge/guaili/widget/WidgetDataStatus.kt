package com.gouge.guaili.widget

import com.gouge.guaili.data.CellAvailability
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.isGuailiSnapshotStale
import com.gouge.guaili.data.parseGuailiTime
import com.gouge.guaili.data.signalCellAvailability
import com.gouge.guaili.domain.GuailiCell

data class WidgetDataStatus(
    val ready: Int,
    val missing: Int,
    val stale: Int,
    val filtered: Int,
    val latestClosedAt: Long?,
    val snapshotStale: Boolean,
    val expectedCells: Int,
    val staleSymbols: List<String> = emptyList(),
    val staleIntervalsBySymbol: Map<String, List<String>> = emptyMap(),
    val unavailable: Int = 0,
    val noDataSymbols: List<String> = emptyList(),
) {
    val incomplete: Boolean get() = missing > 0 || stale > 0 || snapshotStale || expectedCells == 0 || noDataSymbols.isNotEmpty()
    val description: String get() = when {
        snapshotStale -> "缓存已过期，刷新后再判断"
        expectedCells == 0 -> "没有可判断的品种或周期"
        noDataSymbols.isNotEmpty() -> "部分品种暂无行情"
        else -> listOfNotNull(
            if (stale > 0) "${stale}级过期" else null,
            if (missing > 0) "${missing}级尚无有效收线数据" else null,
            if (filtered > 0) "${filtered}级被过滤" else null,
        ).joinToString(" · ").ifEmpty { "已返回周期可用" }
    }

    val warning: String? get() = when {
        snapshotStale -> "缓存过期，请刷新"
        stale > 0 -> (if (stale == expectedCells) "行情过期：" else "部分行情过期：") +
            staleSymbols.take(2).joinToString("、") { symbol ->
                val periods = staleIntervalsBySymbol[symbol].orEmpty()
                symbol.removeSuffix("USDT") + if (periods.isEmpty()) "" else {
                    "（" + periods.take(2).joinToString("/") { if (it.all(Char::isDigit)) "${it}m" else it } +
                        (if (periods.size > 2) "等${periods.size}级" else "") + "）"
                }
            }
                .ifEmpty { "${stale}个周期" } +
            if (staleSymbols.size > 2) "等${staleSymbols.size}个品种" else ""
        noDataSymbols.isNotEmpty() -> "暂无行情：" + noDataSymbols.joinToString("、") { it.removeSuffix("USDT") }
        missing > 0 -> "部分周期暂不可判断：${missing}个"
        else -> null
    }
}

fun widgetDataStatus(
    snapshot: GuailiSnapshot,
    symbols: List<String>,
    now: Long = System.currentTimeMillis(),
    intervals: List<String> = snapshot.table.intervals,
): WidgetDataStatus {
    val requestedIntervals = intervals.distinct()
    val cellsBySymbol = symbols.distinct().associateWith { symbol ->
        val latest = snapshot.table.cells[symbol].orEmpty()
        val closed = snapshot.table.closedCells[symbol] ?: snapshot.table.cells[symbol].orEmpty()
        // Omitted/empty backend periods are normal. A returned live period without a
        // closed candle is different: it still cannot be used for closed-candle signals.
        requestedIntervals.filter { closed[it] != null || latest[it] != null }.associateWith { closed[it] }
    }
    val cells = cellsBySymbol.values.flatMap { it.values }
    val statusesBySymbol = cellsBySymbol.mapValues { (_, periods) ->
        periods.mapValues { (_, cell) -> signalCellAvailability(cell, now, snapshot.timezone) }
    }
    val statuses = statusesBySymbol.values.flatMap { it.values }
    val staleIntervals = statusesBySymbol.mapValues { (_, periods) ->
        periods.filterValues { it == CellAvailability.Stale }.keys.toList()
    }.filterValues { it.isNotEmpty() }
    return WidgetDataStatus(
        ready = statuses.count { it == CellAvailability.Ready },
        missing = statuses.count { it in setOf(CellAvailability.Missing, CellAvailability.Unclosed, CellAvailability.UnknownTime) },
        stale = statuses.count { it == CellAvailability.Stale },
        filtered = statuses.count { it == CellAvailability.Filtered },
        latestClosedAt = cells.mapNotNull { parseGuailiTime(it?.closeTime, snapshot.timezone) }.filter { it <= now + 2_000 }.maxOrNull(),
        snapshotStale = isGuailiSnapshotStale(snapshot.updatedAt, now),
        expectedCells = cells.size,
        staleSymbols = staleIntervals.keys.toList(),
        staleIntervalsBySymbol = staleIntervals,
        unavailable = symbols.distinct().size * requestedIntervals.size - cells.size,
        noDataSymbols = if (requestedIntervals.isEmpty()) emptyList() else
            cellsBySymbol.filterValues { it.isEmpty() }.keys.toList(),
    )
}

internal fun matrixIntervalGroups(intervals: List<String>, widthDp: Int): List<List<String>> =
    intervals.chunked(if (widthDp < 250) 2 else WidgetConfigStore.MaxIntervals)

internal fun movingAverageLabel(snapshot: GuailiSnapshot): String =
    if (snapshot.maType != null && snapshot.maLength != null) "${snapshot.maType.uppercase()}${snapshot.maLength}" else "均线（参数未知）"

internal fun widgetCellValue(cell: GuailiCell?): String =
    cell?.value?.let { "$it${if (cell.isClosed != true) "·" else ""}" } ?: "--"

internal fun widgetConfigurationIssue(config: WidgetConfig, symbols: List<String>, intervals: List<String>): String? {
    val missingSymbols = config.symbols.filterNot(symbols::contains)
    val missingIntervals = if (config.mode == WidgetMode.Matrix) config.intervals.filterNot(intervals::contains) else emptyList()
    return listOfNotNull(
        missingSymbols.takeIf { it.isNotEmpty() }?.let { "品种已移除：${it.joinToString()}" },
        missingIntervals.takeIf { it.isNotEmpty() }?.let { "周期已移除：${it.joinToString()}" },
    ).joinToString("；").takeIf { it.isNotEmpty() }
}
