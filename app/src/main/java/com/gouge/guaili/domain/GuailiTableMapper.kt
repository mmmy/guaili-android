package com.gouge.guaili.domain

import com.gouge.guaili.data.GuailiPoint
import com.gouge.guaili.data.GuailiResponse

fun GuailiResponse.toTable(
    requestedSymbols: List<String>,
    requestedIntervals: List<String>,
): GuailiTable {
    val bySymbol = results.associateBy { it.symbol }
    val cells = requestedSymbols.associateWith { symbol ->
        val seriesByInterval = bySymbol[symbol]?.series.orEmpty().associateBy { it.interval }
        requestedIntervals.mapNotNull { interval ->
            val series = seriesByInterval[interval] ?: return@mapNotNull null
            val latest = series.latest ?: return@mapNotNull null
            val previous = series.data.getOrNull(series.data.lastIndex - 1)
            interval to latest.toCell(
                symbol = symbol,
                interval = interval,
                longTrend = previous?.longTrend,
                shortTrend = previous?.shortTrend,
            )
        }.toMap()
    }
    val closedCells = requestedSymbols.associateWith { symbol ->
        val seriesByInterval = bySymbol[symbol]?.series.orEmpty().associateBy { it.interval }
        requestedIntervals.mapNotNull { interval ->
            val series = seriesByInterval[interval] ?: return@mapNotNull null
            val closedIndex = series.data.indexOfLast { it.isClosed == true }
            val closed = series.data.getOrNull(closedIndex)
                ?: series.latest?.takeIf { it.isClosed == true }
                ?: return@mapNotNull null
            val previousClosed = series.data
                .take(closedIndex.coerceAtLeast(0))
                .lastOrNull { it.isClosed == true }
            interval to closed.toCell(
                symbol = symbol,
                interval = interval,
                longTrend = previousClosed?.longTrend,
                shortTrend = previousClosed?.shortTrend,
            )
        }.toMap()
    }

    return GuailiTable(
        symbols = requestedSymbols,
        intervals = requestedIntervals,
        cells = cells,
        closedCells = closedCells,
    )
}

private fun GuailiPoint.toCell(
    symbol: String,
    interval: String,
    longTrend: Boolean?,
    shortTrend: Boolean?,
): GuailiCell =
    GuailiCell(
        symbol = symbol,
        interval = interval,
        value = value,
        guaili = guaili,
        ma = ma,
        atr14 = atr14,
        atrRank = atrRank,
        rankFilter = rankFilter,
        longTrend = longTrend,
        shortTrend = shortTrend,
        isClosed = isClosed,
        openTime = openTime,
        closeTime = closeTime,
    )
