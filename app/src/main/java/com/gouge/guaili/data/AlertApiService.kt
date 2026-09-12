package com.gouge.guaili.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface AlertApiService {
    @GET("api/alerts")
    suspend fun listAlerts(): List<AlertDto>

    @POST("api/alerts")
    suspend fun createAlert(@Body request: AlertRequestDto): AlertDto

    @PATCH("api/alerts/{id}")
    suspend fun updateAlert(@Path("id") id: Long, @Body request: AlertPatchDto): AlertDto

    @DELETE("api/alerts/{id}")
    suspend fun deleteAlert(@Path("id") id: Long)

    @GET("api/alerts/{id}/events")
    suspend fun listEvents(@Path("id") id: Long): List<AlertEventDto>
}
