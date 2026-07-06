package com.gouge.guaili.data

import retrofit2.http.GET
import retrofit2.http.Query

interface GuailiApiService {
    @GET("api/indicators/guaili")
    suspend fun getGuaili(
        @Query("symbols") symbols: String,
        @Query("intervals") intervals: String,
        @Query("limit") limit: Int,
        @Query("calcLimit") calcLimit: Int,
        @Query("closedOnly") closedOnly: Boolean,
        @Query("maLength") maLength: Int,
        @Query("maType") maType: String,
        @Query("atrLen") atrLen: Int,
        @Query("atrPercentLen") atrPercentLen: Int,
        @Query("maxAtrRank") maxAtrRank: Double,
        @Query("slopeMul") slopeMul: Double,
        @Query("useSlope") useSlope: Boolean,
    ): GuailiResponse
}
