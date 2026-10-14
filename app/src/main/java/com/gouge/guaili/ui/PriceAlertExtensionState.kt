package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*

data class PriceAlertExtensionPreview(val alert: PriceAlertDto, val bars: Int,
    val plan: PriceAlertExtensionPlan? = null, val loading: Boolean = true, val error: String? = null)
data class PriceAlertExtensionUndo(val id: Long, val revision: Long, val geometry: PriceAlertGeometry, val until: Long) {
    fun validFor(alert: PriceAlertDto?, now: Long, elapsed: Long): Boolean = elapsed < until &&
        alert?.id == id && alert.revision == revision && alert.isEnabledAt(now)
}
data class PriceAlertExtensionReceipt(val mutationId: String, val alert: PriceAlertDto, val undone: Boolean, val until: Long)
data class PriceAlertConfirmation(val operation: PriceAlertOperation, val alert: PriceAlertDto? = null)
data class PriceAlertConflict(val operation: PriceAlertOperation, val existing: PriceAlertDto)

internal fun conflictingPriceAlert(op: PriceAlertOperation, alerts: List<PriceAlertDto>, now: Long): PriceAlertDto? {
    val prior = alerts.firstOrNull { it.id == op.alertId }
    fun field(key: String): String? = (op.body[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val intent = field("intent") ?: prior?.intent ?: "unclassified"
    if (intent !in PriceAlertIntents || (field("status") ?: prior?.status ?: "disabled") != "active") return null
    val expiry = field("expiresAt")?.toLongOrNull() ?: prior?.expiresAt.takeUnless { op.body.containsKey("expiresAt") }
    if (expiry != null && expiry <= now) return null
    val symbol = field("symbol") ?: prior?.symbol ?: return null
    val period = pullbackPeriodMillis(field("interval") ?: prior?.interval ?: return null) ?: return null
    return alerts.firstOrNull { it.id != op.alertId && it.symbol == symbol && it.intent == intent &&
        pullbackPeriodMillis(it.interval) == period && it.isEnabledAt(now) }
}
