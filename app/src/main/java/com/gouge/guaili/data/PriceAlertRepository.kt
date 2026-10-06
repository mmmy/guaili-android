package com.gouge.guaili.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.*

interface PriceAlertApi {
    @GET("api/price-alerts") suspend fun list(@Query("symbol") symbol: String): List<PriceAlertDto>
    @GET("api/price-alerts/{id}") suspend fun get(@Path("id") id: Long): PriceAlertDto
    @POST("api/price-alerts") suspend fun create(@Body body: JsonObject): JsonElement
    @PATCH("api/price-alerts/{id}") suspend fun patch(@Path("id") id: Long, @Body body: JsonObject): JsonElement
    @DELETE("api/price-alerts/{id}") suspend fun delete(@Path("id") id: Long, @Query("mutationId") mutationId: String, @Query("expectedRevision") revision: Long): JsonElement
    @GET("api/price-alerts/mutations/{mutationId}") suspend fun mutation(@Path("mutationId") mutationId: String): JsonElement
    @GET("api/price-alerts/market") suspend fun market(@Query("symbol") symbol: String): PriceAlertMarket
    @GET("api/price-alerts/{id}/events") suspend fun events(@Path("id") id: Long, @Query("limit") limit: Int, @Query("beforeArmGeneration") before: Long?): List<PriceAlertEvent>
}

sealed interface PriceAlertResult<out T> {
    data class Success<T>(val value: T) : PriceAlertResult<T>
    data class Failure(val message: String, val code: Int? = null) : PriceAlertResult<Nothing> {
        val uncertain: Boolean get() = code == null || code >= 500 || code == 408
    }
}

interface PriceAlertGateway {
    suspend fun list(symbol: String): PriceAlertResult<List<PriceAlertDto>>
    suspend fun get(id: Long): PriceAlertResult<PriceAlertDto>
    suspend fun market(symbol: String): PriceAlertResult<PriceAlertMarket>
    suspend fun events(id: Long, before: Long? = null): PriceAlertResult<List<PriceAlertEvent>>
    suspend fun send(operation: PriceAlertOperation): PriceAlertResult<JsonElement>
    suspend fun recover(mutationId: String): PriceAlertResult<JsonElement>
}

class PriceAlertRepository(private val api: PriceAlertApi) : PriceAlertGateway {
    override suspend fun list(symbol: String) = call { api.list(symbol) }
    override suspend fun get(id: Long) = call { api.get(id) }
    override suspend fun market(symbol: String) = call { api.market(symbol) }
    override suspend fun events(id: Long, before: Long?) = call { api.events(id, 200, before) }
    override suspend fun recover(mutationId: String) = call { api.mutation(mutationId) }
    override suspend fun send(operation: PriceAlertOperation) = call {
        when (operation.method) {
            "create" -> api.create(operation.body)
            "patch" -> api.patch(requireNotNull(operation.alertId), operation.body)
            "delete" -> api.delete(requireNotNull(operation.alertId), operation.mutationId, operation.expectedRevision)
            else -> error("操作类型无效")
        }
    }
    private suspend fun <T> call(action: suspend () -> T): PriceAlertResult<T> = try {
        PriceAlertResult.Success(action())
    } catch (error: CancellationException) { throw error
    } catch (error: HttpException) {
        val text = error.response()?.errorBody()?.string()?.take(500).orEmpty()
        PriceAlertResult.Failure(when (error.code()) {
            409 -> "警报已在其他位置更新，请刷新后重试"
            404 -> "警报或新接口不存在，请确认服务版本"
            503 -> "品种精度暂不可用，请稍后重试"
            else -> text.ifBlank { "服务返回 HTTP ${error.code()}" }
        }, error.code())
    } catch (_: Exception) { PriceAlertResult.Failure("连接中断，正在核对服务器是否已保存") }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun create(baseUrl: String): PriceAlertRepository {
            val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
            return PriceAlertRepository(Retrofit.Builder().baseUrl(baseUrl.trimEnd('/') + "/")
                .client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(PriceAlertApi::class.java))
        }
    }
}
