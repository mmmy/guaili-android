package com.gouge.guaili.data

import retrofit2.http.GET
import retrofit2.http.Query

fun interface ServerSignalsApiService {
    @GET("api/signals")
    suspend fun getSignals(@Query("symbols") symbols: String?): ServerSignalsResponse
}
