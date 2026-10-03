package com.gouge.guaili.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit

class ServerSignalsRepository(private val api: ServerSignalsApiService) {
    suspend fun fetch(symbols: List<String> = emptyList()): GuailiResult<ServerSignalsResponse> = try {
        val query = symbols.map(String::trim).filter(String::isNotEmpty).distinct()
            .joinToString(",").ifEmpty { null }
        val response = try {
            api.getSignals(query)
        } catch (error: HttpException) {
            // One Android-only symbol must not block valid symbols in other widgets.
            // The full response lets presentation retain an explicit unsupported-symbol warning.
            if (query == null || error.code() != 400) throw error
            api.getSignals(null)
        }
        GuailiResult.Success(response)
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpException) {
        GuailiResult.Failure(
            if (error.code() == 404) "此服务器暂不支持信号V2，请升级后端或使用原信号模式"
            else "服务器信号请求失败（HTTP ${error.code()}），请重试",
            error,
        )
    } catch (error: Exception) {
        GuailiResult.Failure("无法获取服务器信号，请检查服务器地址和网络", error)
    }

    companion object {
        private val wireJson = Json { ignoreUnknownKeys = true }
        private val sharedClient by lazy {
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
        }
        fun create(baseUrl: String): ServerSignalsRepository {
            val retrofit = Retrofit.Builder()
                .baseUrl(normalizeServerSignalsBaseUrl(baseUrl) + "/")
                .client(sharedClient)
                .addConverterFactory(
                    wireJson
                        .asConverterFactory("application/json".toMediaType()),
                )
                .build()
            return ServerSignalsRepository(retrofit.create(ServerSignalsApiService::class.java))
        }
    }
}

fun normalizeServerSignalsBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')
