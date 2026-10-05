package com.gouge.xbot.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface XbotApiService {
    @POST("api/customer/login")
    suspend fun login(@Body request: LoginRequest): LoginTokenDto

    @GET("api/customer/signal-view/list")
    suspend fun getSignalViews(): List<SignalViewDto>

    @GET("api/customer/tv-alert/list")
    suspend fun getTvAlertConfigs(): List<TvAlertConfigDto>

    @POST("api/customer/tv-alert/all-alert-list")
    suspend fun getTvAlerts(@Body request: TvAlertListRequest): List<TvAlertDto>

    @POST("api/customer/tv-alert/add-alerts")
    suspend fun addTvAlerts(@Body request: AddTvAlertRequest): OperationResultDto

    @GET("api/customer/tv-alert/alert-process-status")
    suspend fun getTvAlertProcesses(): List<TvAlertProcessDto>

    @POST("api/customer/tv-alert/system-alert-cache/refresh")
    suspend fun refreshTradingViewCache(@Body request: RefreshAlertCacheRequest): OperationResultDto

    @POST("api/customer/tv-alert/delete-by-tv-alert-id")
    suspend fun deleteTvAlertById(
        @Body request: DeleteTvAlertByIdRequest,
    ): OperationResultDto

    @GET("api/customer/signal-view/{id}")
    suspend fun getSignalView(@Path("id") id: String): SignalViewDto

    @PATCH("api/customer/signal-view/{id}/settings")
    suspend fun updateSignalSettings(
        @Path("id") id: String,
        @Body request: UpdateSignalSettingsRequest,
    ): SignalViewDto

    @POST("api/customer/logout")
    suspend fun logout(): Boolean
}
