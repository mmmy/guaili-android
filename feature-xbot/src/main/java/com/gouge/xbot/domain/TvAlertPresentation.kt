package com.gouge.xbot.domain

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val symbolJson = Json { ignoreUnknownKeys = true }
private val tradingViewTickerPattern = Regex("^[A-Z0-9_.-]+:[A-Z0-9_.-]+$")

fun normalizeTradingViewTicker(
    input: String,
    defaultExchange: String = "BINANCE",
): String {
    val value = input.trim().uppercase()
    require(value.isNotEmpty()) { "请输入品种" }
    require(value.none { it.isWhitespace() || it == ',' }) { "一次只能设置一个品种" }
    val ticker = if (':' in value) value else "${defaultExchange.uppercase()}:$value"
    require(tradingViewTickerPattern.matches(ticker)) {
        "品种格式应为 交易所:代码，例如 BINANCE:ETHUSDT.P"
    }
    return ticker
}

fun TvAlertDto.tickerId(): String {
    val raw = symbol.removePrefix("=").trim()
    if (raw.isBlank()) return ""
    return runCatching {
        symbolJson.parseToJsonElement(raw).jsonObject["symbol"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")
}

fun TvAlertDto.tickerLabel(): String = tickerId().substringAfterLast(':').ifBlank { "-" }

fun TvAlertConfigDto.matches(alert: TvAlertDto): Boolean =
    namePre.isNotBlank() && alert.name.startsWith(namePre)
