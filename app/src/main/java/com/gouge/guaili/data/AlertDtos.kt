package com.gouge.guaili.data

import kotlinx.serialization.Serializable

@Serializable
data class AlertDto(
    val id: Long,
    val symbol: String,
    val interval: String,
    val price: Double,
    val direction: String,
    val status: String,
    val expiresAt: Long? = null,
    val webhookUrl: String,
    val messageTemplate: String,
    val createdAt: Long,
    val updatedAt: Long,
    val triggeredAt: Long? = null,
    val deliveryStatus: String? = null,
    val deliveryError: String? = null,
)

@Serializable
data class AlertEventDto(
    val id: Long,
    val alertId: Long,
    val triggeredAt: Long,
    val triggerPrice: Double,
    val direction: String,
    val deliveryStatus: String? = null,
    val deliveryError: String? = null,
)

@Serializable
data class AlertRequestDto(
    val symbol: String,
    val interval: String,
    val price: Double,
    val direction: String,
    val expiresAt: Long? = null,
    val webhookUrl: String,
    val messageTemplate: String,
)

@Serializable
data class AlertPatchDto(
    val price: Double? = null,
    val direction: String? = null,
    val expiresAt: Long? = null,
    val webhookUrl: String? = null,
    val messageTemplate: String? = null,
    val status: String? = null,
)
