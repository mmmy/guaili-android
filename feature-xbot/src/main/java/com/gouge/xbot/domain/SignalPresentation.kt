package com.gouge.xbot.domain

import com.gouge.xbot.data.SignalViewDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DirectionState(val label: String) {
    LongOnly("做多"),
    ShortOnly("做空"),
    Both("双向"),
    Disabled("已关闭"),
}

enum class SignalCommentType(val label: String) {
    OpenLong("做多"),
    OpenShort("做空"),
    CloseLong("平多"),
    CloseShort("平空"),
}

data class SignalCommentItem(
    val type: SignalCommentType?,
    val text: String,
)

private val SignalCommentPattern = Regex("(做多|做空|平多|平空)[:：]")

fun parseSignalComment(comment: String?): List<SignalCommentItem> {
    val text = comment?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()

    val matches = SignalCommentPattern.findAll(text).toList()
    if (matches.isEmpty()) return listOf(SignalCommentItem(type = null, text = text))

    return matches.mapIndexed { index, match ->
        val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
        SignalCommentItem(
            type = when (match.groupValues[1]) {
                "做多" -> SignalCommentType.OpenLong
                "做空" -> SignalCommentType.OpenShort
                "平多" -> SignalCommentType.CloseLong
                else -> SignalCommentType.CloseShort
            },
            text = text.substring(match.range.last + 1, nextStart).trim(),
        )
    }
}

data class ExpiryPresentation(
    val text: String,
    val isExpired: Boolean,
    val isExpiringSoon: Boolean = false,
)

fun directionState(longOn: Boolean, shortOn: Boolean): DirectionState = when {
    longOn && shortOn -> DirectionState.Both
    longOn -> DirectionState.LongOnly
    shortOn -> DirectionState.ShortOnly
    else -> DirectionState.Disabled
}

fun SignalViewDto.levelText(): String {
    val periodsText = periods.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "-"
    val range = when {
        levelMin.isNotBlank() && levelMax.isNotBlank() -> "$levelMin–$levelMax"
        levelMin.isNotBlank() -> "≥$levelMin"
        levelMax.isNotBlank() -> "≤$levelMax"
        else -> ""
    }
    return if (range.isBlank()) periodsText else "$periodsText  L$range"
}

fun formatExpiry(
    expireAt: String?,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ExpiryPresentation {
    if (expireAt.isNullOrBlank()) return ExpiryPresentation("● 运行中 · 长期有效", false)
    val instant = parseInstant(expireAt, zoneId)
        ?: return ExpiryPresentation("● 运行中 · $expireAt", false)
    val formatted = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(zoneId)
        .format(instant)
    return if (instant <= now) {
        ExpiryPresentation("已过期 · $formatted", true)
    } else if (Duration.between(now, instant) <= Duration.ofHours(1)) {
        ExpiryPresentation("即将过期 · $formatted", false, true)
    } else {
        ExpiryPresentation("● 运行中 · 有效至 $formatted", false)
    }
}

fun formatWidgetExpiry(
    expireAt: String?,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ExpiryPresentation {
    val exactExpiry = formatExpiry(expireAt, now, zoneId)
    if (expireAt.isNullOrBlank() || exactExpiry.isExpired) return exactExpiry
    val instant = parseInstant(expireAt, zoneId)
        ?: return exactExpiry

    val remainingHours = Duration.between(now, instant).toHours()
    val countdown = when {
        remainingHours < 1 -> "<1小时"
        remainingHours < 24 -> "${remainingHours}小时"
        else -> "${remainingHours / 24}天"
    }
    return exactExpiry.copy(text = "${exactExpiry.text} · $countdown")
}

private fun parseInstant(value: String, zoneId: ZoneId): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).atZone(zoneId).toInstant() }.getOrNull()
