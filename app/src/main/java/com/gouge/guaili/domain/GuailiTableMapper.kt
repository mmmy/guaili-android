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
            val latest = seriesByInterval[interval]?.latest ?: return@mapNotNull null
            interval to latest.toCell(symbol = symbol, interval = interval)
        }.toMap()
    }

    return GuailiTable(
        symbols = requestedSymbols,
        intervals = requestedIntervals,
        cells = cells,
    )
}

private fun GuailiPoint.toCell(symbol: String, interval: String): GuailiCell =
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
