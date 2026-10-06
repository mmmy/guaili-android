package com.gouge.guaili.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

const val PriceAlertWebhook = "http://139.180.203.107/api/web-hook/signal"
const val PriceAlertTemplate = """{
  "des":"{{interval}}底部合约多{{ticker}}下穿{{close}}",
  "name":"PRE-LONG",
  "side":"BUY",
  "exchange":"BINANCE",
  "symbol":"{{ticker}}",
  "price":"{{close}}",
  "period":"{{interval}}",
  "noConfirm":false
}"""

@Serializable
data class PriceAlertPoint(val timeMs: Long, val price: Double)

@Serializable
data class PriceAlertGeometry(
    val kind: String,
    val first: PriceAlertPoint,
    val second: PriceAlertPoint,
    val extend: String = "right",
) {
    fun normalized(): PriceAlertGeometry = if (first.timeMs <= second.timeMs) this else copy(first = second, second = first)
    fun valid(): Boolean = kind in listOf("horizontal_segment", "trend_segment") &&
        extend in listOf("none", "right", "both") && first.timeMs >= 0 && second.timeMs > first.timeMs &&
        first.price.isFinite() && first.price > 0 && second.price.isFinite() && second.price > 0 &&
        (kind != "horizontal_segment" || first.price == second.price)
    fun priceAt(time: Long): Double? {
        if (!valid() || (extend != "both" && time < first.timeMs) || (extend == "none" && time > second.timeMs)) return null
        val price = if (kind == "horizontal_segment") first.price else
            first.price + (second.price - first.price) * ((time.toDouble() - first.timeMs) / (second.timeMs.toDouble() - first.timeMs))
        return price.takeIf { it.isFinite() && it > 0 }
    }
}

@Serializable
data class PriceAlertDto(
    val id: Long,
    val revision: Long,
    val armGeneration: Long,
    val symbol: String,
    val interval: String,
    val tvSymbol: String,
    val name: String,
    val geometry: PriceAlertGeometry,
    val direction: String,
    val frequency: String,
    val status: String,
    val expiresAt: Long? = null,
    val webhookUrl: String,
    val messageTemplate: String,
    val label: String,
    val color: String = "#22AB94",
    val lineWidth: Float = 2f,
    val triggeredAt: Long? = null,
    val deliveryStatus: String? = null,
    val deliveryError: String? = null,
    val dataStatus: String = "waiting_for_price",
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun stateLabel(now: Long = System.currentTimeMillis()): String = when {
        status == "disabled" && expiresAt != null && expiresAt <= now -> "暂停 · 已到期"
        status == "disabled" -> "手动暂停"
        status == "triggered" -> "已触发"
        status == "expired" || (expiresAt != null && expiresAt <= now) -> "已过期"
        status != "active" -> "状态未知"
        dataStatus == "live" -> "监测中"
        dataStatus == "disconnected" -> "行情断开"
        dataStatus == "waiting_for_range" -> "等待线段起始"
        dataStatus == "range_ended" -> "线段范围已结束"
        dataStatus == "invalid_line" -> "线价无效"
        dataStatus in listOf("waiting_for_metadata", "unavailable") -> "等待品种精度"
        else -> "等待行情"
    }
}

@Serializable
data class PriceAlertMarket(
    val symbol: String,
    val tvSymbol: String,
    val tickSize: String? = null,
    val status: String,
    val reason: String? = null,
) {
    fun step(): BigDecimal? = if (status == "ready") tickSize?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } else null
}

fun alignAlertPrice(price: Double, step: BigDecimal?): Double {
    if (!price.isFinite() || price <= 0 || step == null) return price
    return BigDecimal.valueOf(price).divide(step, 0, RoundingMode.HALF_UP).multiply(step).toDouble()
}

@Serializable
data class PriceAlertEvent(
    val id: String,
    val alertId: Long,
    val armGeneration: Long,
    val triggeredAt: Long,
    val triggerPrice: Double,
    val linePrice: Double,
    val direction: String,
    val payload: JsonElement,
    val deliveryStatus: String,
    val deliveryError: String? = null,
)

@Serializable
data class PriceAlertPreset(
    val name: String = "穿过多",
    val direction: String = "cross_any",
    val webhookUrl: String = PriceAlertWebhook,
    val messageTemplate: String = PriceAlertTemplate,
    val label: String = "做多",
    val color: String = "#22AB94",
    val lineWidth: Float = 2f,
    val extend: String = "right",
)

fun newAlertMutationId(): String = "android-${UUID.randomUUID()}"

fun validateAlertTemplate(template: String): String? = try {
    val value = Json.parseToJsonElement(template) as? JsonObject
    when {
        value == null -> "消息模板必须是 JSON 对象"
        value["noConfirm"] is JsonPrimitive && (value["noConfirm"] as JsonPrimitive).booleanOrNull == null -> "noConfirm 必须是布尔值 true 或 false"
        value["noConfirm"] is JsonPrimitive && (value["noConfirm"] as JsonPrimitive).isString -> "noConfirm 必须是布尔值，不能加引号"
        else -> null
    }
} catch (_: Exception) { "消息模板不是有效 JSON" }
