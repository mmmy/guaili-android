package com.gouge.xbot.widget

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.domain.businessExpireAt
import com.gouge.xbot.domain.matches
import com.gouge.xbot.domain.tickerLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable enum class AlertWidgetMode { Time, Grouped }
@Serializable enum class AlertWidgetOrder { Earliest, Latest }
@Serializable enum class AlertWidgetDensity { Normal, Compact }

@Serializable
data class AlertWidgetSettings(
    val mode: AlertWidgetMode = AlertWidgetMode.Grouped,
    val order: AlertWidgetOrder = AlertWidgetOrder.Earliest,
    val density: AlertWidgetDensity = AlertWidgetDensity.Compact,
    // null uses the first home group; an empty set means all groups are collapsed.
    val expandedGroupIds: Set<String>? = null,
)

@Serializable
data class AlertSnapshot(
    val configs: List<TvAlertConfigDto> = emptyList(),
    val alertsByCookieId: Map<String, List<TvAlertDto>> = emptyMap(),
    val updatedAtMillis: Long = 0,
    val error: String? = null,
)

data class AlertWidgetEntry(
    val config: TvAlertConfigDto,
    val alert: TvAlertDto,
    val expiresAt: Instant?,
)

sealed interface AlertWidgetRow {
    val key: String
    data class Group(
        val config: TvAlertConfigDto,
        val entries: List<AlertWidgetEntry>,
        val expanded: Boolean,
    ) : AlertWidgetRow { override val key = "group:${config.id}" }
    data class Alert(val entry: AlertWidgetEntry, val grouped: Boolean) : AlertWidgetRow {
        override val key = "alert:${entry.config.id}:${entry.alert.alertId}"
    }
}

fun alertWidgetEntries(snapshot: AlertSnapshot, visibleIds: Set<String>): List<AlertWidgetEntry> =
    snapshot.configs.filter { it.id in visibleIds }.flatMap { config ->
        snapshot.alertsByCookieId[config.cookieId].orEmpty().filter(config::matches).map {
            AlertWidgetEntry(config, it, businessExpireAt(config, it))
        }
    }

fun alertWidgetRows(
    snapshot: AlertSnapshot,
    visibleIds: Set<String>,
    settings: AlertWidgetSettings,
): List<AlertWidgetRow> {
    val configs = snapshot.configs.filter { it.id in visibleIds }
    val entries = alertWidgetEntries(snapshot, visibleIds)
    val comparator = Comparator<AlertWidgetEntry> { a, b ->
        val left = a.expiresAt
        val right = b.expiresAt
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            settings.order == AlertWidgetOrder.Latest -> right.compareTo(left)
            else -> left.compareTo(right)
        }
    }.thenBy { it.alert.alertId }
    if (settings.mode == AlertWidgetMode.Time) {
        return entries.sortedWith(comparator).map { AlertWidgetRow.Alert(it, grouped = false) }
    }
    val expanded = settings.expandedGroupIds ?: configs.take(1).mapTo(hashSetOf()) { it.id }
    return buildList {
        configs.forEach { config ->
            val groupEntries = entries.filter { it.config.id == config.id }.sortedWith(comparator)
            add(AlertWidgetRow.Group(config, groupEntries, config.id in expanded))
            if (config.id in expanded) groupEntries.forEach { add(AlertWidgetRow.Alert(it, grouped = true)) }
        }
    }
}

fun coarseAlertDuration(seconds: Long): String = when {
    seconds < 3_600 -> "不足1小时"
    seconds < 86_400 -> "${seconds / 3_600}小时"
    else -> "${seconds / 86_400}天"
}

fun alertWidgetRemaining(expiresAt: Instant?, now: Instant): String {
    if (expiresAt == null) return ""
    val expired = expiresAt <= now
    val duration = if (expired) Duration.between(expiresAt, now) else Duration.between(now, expiresAt)
    return (if (expired) "超时" else "剩余") + coarseAlertDuration(duration.seconds)
}

private val widgetAlertDate = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

fun alertWidgetExpiryText(expiresAt: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    expiresAt?.let {
        (if (it <= now) "已过期 " else "有效至 ") + widgetAlertDate.withZone(zone).format(it)
    } ?: "业务过期：未配置"

fun AlertWidgetRow.Group.summary(now: Instant): String {
    val expiredCount = entries.count { it.expiresAt?.let { time -> time <= now } == true }
    if (expiredCount > 0) return "${entries.size}条 · 过期$expiredCount"
    val nearest = entries.mapNotNull { it.expiresAt }.minOrNull()
    return when {
        entries.isEmpty() -> "暂无警报"
        nearest == null -> "${entries.size}条 · 未配置"
        else -> "${entries.size}条 · 最早${coarseAlertDuration(Duration.between(now, nearest).seconds)}"
    }
}

fun AlertWidgetRow.Alert.title(): String = if (grouped) entry.alert.tickerLabel() else
    "${entry.alert.tickerLabel()} · ${entry.config.title.ifBlank { "未命名" }}"

// A local, inexact repaint is enough for coarse day/hour labels; it does not fetch data.
fun nextAlertWidgetChange(expiresAt: Instant, now: Instant): Instant {
    val expired = expiresAt <= now
    val millis = if (expired) Duration.between(expiresAt, now).toMillis() else Duration.between(now, expiresAt).toMillis()
    val unit = if (millis >= 86_400_000) 86_400_000L else 3_600_000L
    val delay = when {
        expired -> unit - millis % unit
        millis < 3_600_000 -> millis
        else -> millis % unit + 1
    }
    return now.plusMillis(delay.coerceAtLeast(60_000))
}
