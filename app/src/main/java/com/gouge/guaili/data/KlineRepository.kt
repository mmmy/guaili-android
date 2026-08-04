package com.gouge.guaili.data

import com.gouge.guaili.domain.Kline
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

sealed interface KlineResult<out T> {
    data class Success<T>(val value: T) : KlineResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : KlineResult<Nothing>
}

class KlineRepository(
    private val api: KlineApiService,
) {
    suspend fun fetch(
        symbol: String,
        interval: String,
        limit: Int = 300,
        closedOnly: Boolean = false,
    ): KlineResult<List<Kline>> = try {
        val response = api.getKlines(
            symbol = symbol,
            intervals = interval,
            limit = limit,
            closedOnly = closedOnly,
        )
        val series = response.series.firstOrNull { it.interval == interval }
            ?: response.series.firstOrNull()
        val candles = series.orEmptyData()
            .map { it.candle.toDomain() }
            .sortedBy(Kline::openTimeMillis)
        KlineResult.Success(candles)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        KlineResult.Failure(error.message ?: "Kline request failed", error)
    }

    companion object {
        fun create(baseUrl: String): KlineRepository {
            val json = Json { ignoreUnknownKeys = true }
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(ensureKlineTrailingSlash(baseUrl))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return KlineRepository(retrofit.create(KlineApiService::class.java))
        }
    }
}

private fun KlineSeriesDto?.orEmptyData(): List<KlineRowDto> = this?.data.orEmpty()

private fun KlineCandleDto.toDomain(): Kline = Kline(
    openTimeMillis = OffsetDateTime.parse(openTime).toInstant().toEpochMilli(),
    closeTimeMillis = OffsetDateTime.parse(closeTime).toInstant().toEpochMilli(),
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    quoteVolume = quoteVolume,
    tradeCount = tradeCount,
    isClosed = isClosed,
)

private fun ensureKlineTrailingSlash(baseUrl: String): String =
    if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
