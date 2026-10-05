package com.gouge.xbot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginTokenDto(
    @SerialName("access_token") val accessToken: String,
)

@Serializable
data class SignalViewDto(
    @SerialName("_id") val id: String,
    val name: String = "",
    val comment: String? = null,
    val symbol: String = "",
    val sort: Double = 0.0,
    val periods: List<String> = emptyList(),
    val longOn: Boolean = false,
    val shortOn: Boolean = false,
    val expireAt: String? = null,
    val levelMin: String = "",
    val levelMax: String = "",
)

@Serializable
data class UpdateSignalSettingsRequest(
    val periods: List<String>,
    val expireAt: String? = null,
)

@Serializable
data class TvAlertConfigDto(
    @SerialName("_id") val id: String,
    val title: String = "",
    val cookieId: String = "",
    val namePre: String = "",
    val tickerIds: String = "",
    val periods: String = "",
    val signalOn: Boolean = false,
    val sort: Double? = null,
    val type: String = "",
    val expHours: Double? = null,
    val strategyAlertName: String? = null,
    val price: String? = null,
    val params: List<TvAlertParamDto> = emptyList(),
    val startTimeParamIndex: Int? = null,
    val validBarsParamIndex: Int? = null,
    val webhookUrl: String? = null,
    val overwriteAlert: Boolean = false,
)

@Serializable
data class TvAlertParamDto(
    val index: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val value: String = "",
    val comment: String = "",
    val type: String = "",
    val shortcut: Boolean = false,
)

@Serializable
data class TvAlertDto(
    val active: Boolean = false,
    val symbol: String = "",
    val name: String = "",
    @SerialName("alert_id") val alertId: Long,
    val resolution: String = "",
    @SerialName("create_time") val createTime: String = "",
)

@Serializable
data class TvAlertListRequest(
    val cookieId: String,
    val namePre: String,
)

@Serializable
data class AddTvAlertRequest(
    val alertId: String,
    val symbols: String,
    val periods: String,
    val overwrite: Boolean? = null,
)

@Serializable
data class TvAlertProcessDto(
    val alertId: String,
    val startDate: Long = 0,
    val status: String = "",
    val maxAlerts: Int = 0,
    val finishedAlerts: Int = 0,
    val msg: String = "",
)

@Serializable
data class DeleteTvAlertByIdRequest(
    val cookieId: String,
    val alertId: Long,
)

@Serializable
data class RefreshAlertCacheRequest(val cookieId: String)

@Serializable
data class OperationResultDto(
    val result: Boolean = false,
    val msg: String = "",
)
