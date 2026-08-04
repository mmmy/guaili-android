package com.gouge.guaili.data

import retrofit2.http.GET
import retrofit2.http.Query

interface KlineApiService {
    @GET("api/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("intervals") intervals: String,
        @Query("limit") limit: Int,
        @Query("closedOnly") closedOnly: Boolean,
    ): KlineEnvelope
}
