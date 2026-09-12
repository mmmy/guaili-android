package com.gouge.guaili.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

sealed interface AlertResult<out T> {
    data class Success<T>(val value: T) : AlertResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : AlertResult<Nothing>
}

class AlertRepository(private val api: AlertApiService) {
    suspend fun list(): AlertResult<List<AlertDto>> = call { api.listAlerts() }
    suspend fun events(id: Long): AlertResult<List<AlertEventDto>> = call { api.listEvents(id) }
    suspend fun create(request: AlertRequestDto): AlertResult<AlertDto> = call { api.createAlert(request) }
    suspend fun update(id: Long, request: AlertPatchDto): AlertResult<AlertDto> = call { api.updateAlert(id, request) }
    suspend fun delete(id: Long): AlertResult<Unit> = call { api.deleteAlert(id) }

    private suspend fun <T> call(block: suspend () -> T): AlertResult<T> = try {
        AlertResult.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AlertResult.Failure(error.message ?: "Alert request failed", error)
    }

    companion object {
        fun create(baseUrl: String): AlertRepository {
            val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            return AlertRepository(retrofit.create(AlertApiService::class.java))
        }
    }
}
