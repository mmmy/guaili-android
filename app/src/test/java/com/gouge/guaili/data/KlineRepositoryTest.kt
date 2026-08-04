package com.gouge.guaili.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KlineRepositoryTest {
    @Test
    fun fetchRequestsOneSeriesAndMapsCandlesInTimeOrder() = runTest {
        val api = RecordingKlineApiService(
            response = KlineEnvelope(
                symbol = "BTCUSDT",
                intervals = listOf("5"),
                limit = 300,
                closedOnly = false,
                series = listOf(
                    KlineSeriesDto(
                        interval = "5",
                        data = listOf(
                            row("2026-08-04T10:05:00.000+08:00", 101.0),
                            row("2026-08-04T10:00:00.000+08:00", 100.0),
                        ),
                    ),
                ),
            ),
        )
        val repository = KlineRepository(api)

        val result = repository.fetch("BTCUSDT", "5")

        assertTrue(result is KlineResult.Success)
        val candles = (result as KlineResult.Success).value
        assertEquals(listOf(100.0, 101.0), candles.map { it.close })
        assertEquals(KlineCall("BTCUSDT", "5", 300, false), api.calls.single())
    }

    private fun row(openTime: String, price: Double) = KlineRowDto(
        symbol = "BTCUSDT",
        interval = "5",
        candle = KlineCandleDto(
            openTime = openTime,
            closeTime = openTime,
            open = price,
            high = price + 1,
            low = price - 1,
            close = price,
            volume = 10.0,
            quoteVolume = price * 10,
            tradeCount = 3,
            isClosed = true,
        ),
    )
}

private data class KlineCall(
    val symbol: String,
    val intervals: String,
    val limit: Int,
    val closedOnly: Boolean,
)

private class RecordingKlineApiService(
    private val response: KlineEnvelope,
) : KlineApiService {
    val calls = mutableListOf<KlineCall>()

    override suspend fun getKlines(
        symbol: String,
        intervals: String,
        limit: Int,
        closedOnly: Boolean,
    ): KlineEnvelope {
        calls += KlineCall(symbol, intervals, limit, closedOnly)
        return response
    }
}
