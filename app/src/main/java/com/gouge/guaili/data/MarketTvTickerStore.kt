package com.gouge.guaili.data

import android.content.Context
import com.gouge.xbot.domain.normalizeTradingViewTicker
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Market-server-scoped explicit aliases; never infer a spot / futures or exchange substitution. */
internal class MarketTvTickerStore(context: Context, baseUrl: String) {
    private val preferences = context.applicationContext.getSharedPreferences("market_tv_tickers", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.trim().trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }

    fun read(): Map<String, String> = runCatching {
        Json.decodeFromString<Map<String, String>>(preferences.getString(key, null) ?: "{}")
    }.getOrDefault(emptyMap())

    suspend fun save(symbol: String, ticker: String?): Map<String, String> = withContext(Dispatchers.IO) { writes.withLock {
        val next = if (ticker == null) read() - symbol else {
            require(':' in ticker) { "请输入完整 TV 代码，例如 BINANCE:BTCUSDT.P" }
            read() + (symbol to normalizeTradingViewTicker(ticker))
        }
        check(preferences.edit().putString(key, Json.encodeToString(next)).commit()) { "品种映射保存失败，请重试" }
        next
    } }

    companion object {
        // Different screen instances can edit the same SharedPreferences file.
        private val writes = Mutex()
    }
}
