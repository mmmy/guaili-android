package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

sealed interface GuailiResult<out T> {
    data class Success<T>(val value: T) : GuailiResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : GuailiResult<Nothing>
}

class GuailiRepository(
    private val api: GuailiApiService,
) {
    suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse> =
        try {
            GuailiResult.Success(
                api.getGuaili(
                    symbols = settings.symbols.joinToString(","),
                    intervals = settings.intervals.joinToString(","),
                    limit = settings.limit,
                    calcLimit = settings.calcLimit,
                    closedOnly = settings.closedOnly,
                    maLength = settings.maLength,
                    maType = settings.maType,
                    atrLen = settings.atrLen,
                    atrPercentLen = settings.atrPercentLen,
                    maxAtrRank = settings.maxAtrRank,
                    slopeMul = settings.slopeMul,
                    useSlope = settings.useSlope,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GuailiResult.Failure(
                message = error.message ?: "Request failed",
                cause = error,
            )
        }

    companion object {
        fun create(baseUrl: String): GuailiRepository {
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
                .baseUrl(ensureTrailingSlash(baseUrl))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()

            return GuailiRepository(retrofit.create(GuailiApiService::class.java))
        }
    }
}

private fun ensureTrailingSlash(baseUrl: String): String =
    if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
