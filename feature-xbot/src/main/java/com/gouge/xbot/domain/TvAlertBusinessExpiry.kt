package com.gouge.xbot.domain

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private val alertResolutionPattern = Regex("^(?:[1-9][0-9]*[SDWM]?|[SDWM])$")
private val decimalNumberPattern = Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
private val businessExpiryFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
private val compactBusinessExpiryFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

// Match the Date range used by xbot-web, including its millisecond precision.
private const val MaxWebDateMillis = 8_640_000_000_000_000.0

fun businessExpireAt(config: TvAlertConfigDto, alert: TvAlertDto): Instant? {
    val startIndex = config.startTimeParamIndex ?: return null
    val barsIndex = config.validBarsParamIndex ?: return null
    if (startIndex < 0 || barsIndex < 0) return null

    val offsetMinutes = config.numericParam(startIndex) ?: return null
    val validBars = config.numericParam(barsIndex) ?: return null
    if (validBars < 0 || floor(validBars) != validBars) return null

    val periodMillis = resolutionMillis(alert.resolution) ?: return null
    val createdAt = runCatching {
        OffsetDateTime.parse(alert.createTime.trim()).toInstant().toEpochMilli().toDouble()
    }.getOrNull() ?: return null
    if (abs(createdAt) > MaxWebDateMillis) return null

    val expiresAt = createdAt + offsetMinutes * 60_000 + periodMillis * validBars
    if (!expiresAt.isFinite() || abs(expiresAt) > MaxWebDateMillis) return null
    return Instant.ofEpochMilli(expiresAt.toLong())
}

fun formatBusinessExpiry(
    expiresAt: Instant?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = expiresAt?.let { businessExpiryFormatter.withZone(zoneId).format(it) } ?: "未配置"

enum class BusinessExpiryState {
    Valid,
    Expired,
    Unconfigured,
}

data class BusinessExpiryPresentation(
    val state: BusinessExpiryState,
    val statusText: String,
    val expiresAtText: String?,
    val compactExpiresAtText: String? = null,
)

fun businessExpiryPresentation(
    expiresAt: Instant?,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): BusinessExpiryPresentation {
    if (expiresAt == null) {
        return BusinessExpiryPresentation(BusinessExpiryState.Unconfigured, "业务过期：未配置", null)
    }
    val expired = expiresAt <= now
    val elapsed = if (expired) Duration.between(expiresAt, now) else Duration.between(now, expiresAt)
    val status = when {
        !expired -> "业务有效 · 剩余 ${elapsed.businessDurationText()}"
        elapsed.isZero -> "业务已过期 · 刚刚过期"
        else -> "业务已过期 · 超时 ${elapsed.businessDurationText()}"
    }
    return BusinessExpiryPresentation(
        state = if (expired) BusinessExpiryState.Expired else BusinessExpiryState.Valid,
        statusText = status,
        expiresAtText = formatBusinessExpiry(expiresAt, zoneId),
        compactExpiresAtText = compactBusinessExpiryFormatter.withZone(zoneId).format(expiresAt),
    )
}

private fun Duration.businessDurationText(): String {
    val totalSeconds = seconds
    val days = totalSeconds / 86_400
    val hours = totalSeconds / 3_600 % 24
    val minutes = totalSeconds / 60 % 60
    val secondsPart = totalSeconds % 60
    return when {
        days > 0 -> "${days}天" + if (hours > 0) "${hours}小时" else ""
        hours > 0 -> "${hours}小时" + if (minutes > 0) "${minutes}分" else ""
        minutes > 0 -> "${minutes}分" + if (secondsPart > 0) "${secondsPart}秒" else ""
        totalSeconds > 0 -> "${totalSeconds}秒"
        else -> "不足1秒"
    }
}

private fun TvAlertConfigDto.numericParam(index: Int): Double? = params
    .firstOrNull { it.index.finiteNumber() == index.toDouble() }
    ?.value
    ?.finiteNumber()

private fun String.finiteNumber(): Double? {
    val value = trim()
    if (!decimalNumberPattern.matches(value)) return null
    return value.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun resolutionMillis(raw: String): Double? {
    val resolution = raw.trim().uppercase(Locale.ROOT)
    if (!alertResolutionPattern.matches(resolution)) return null
    val count = resolution.takeWhile { it in '0'..'9' }
        .let { if (it.isEmpty()) 1.0 else it.toDoubleOrNull() ?: return null }
    val unitMillis = when (resolution.last()) {
        'S' -> 1_000.0
        'D' -> 86_400_000.0
        'W' -> 604_800_000.0
        // Keep the same fixed 30-day month as xbot-web.
        'M' -> 2_592_000_000.0
        else -> 60_000.0
    }
    return (count * unitMillis).takeIf { it.isFinite() }
}
