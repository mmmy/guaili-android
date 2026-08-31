package com.gouge.guaili.widget

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DecisionReminderPreset(
    val label: String,
    val minutes: Long,
)

internal val DecisionReminderPresets = listOf(
    DecisionReminderPreset("15 分钟", 15L),
    DecisionReminderPreset("30 分钟", 30L),
    DecisionReminderPreset("1 小时", 60L),
    DecisionReminderPreset("2 小时", 120L),
    DecisionReminderPreset("4 小时", 240L),
    DecisionReminderPreset("8 小时", 480L),
    DecisionReminderPreset("12 小时", 720L),
    DecisionReminderPreset("24 小时", 1_440L),
    DecisionReminderPreset("48 小时", 2_880L),
)

/**
 * Adds the requested duration first, then rounds towards the next matching
 * boundary. This mirrors xbot-android and never shortens the selected duration.
 */
fun alignedDecisionReminderTime(
    minutes: Long,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): Instant {
    val alignmentMinutes = when {
        minutes == 15L -> 15L
        minutes == 30L -> 30L
        minutes > 0 && minutes % 60L == 0L -> 60L
        else -> throw IllegalArgumentException("Unsupported reminder preset: $minutes minutes")
    }
    val target = now.atZone(zoneId).plusMinutes(minutes)
    val minutesPastBoundary = target.minute % alignmentMinutes
    val alreadyAligned = minutesPastBoundary == 0L && target.second == 0 && target.nano == 0
    if (alreadyAligned) return target.toInstant()

    return target
        .plusMinutes(alignmentMinutes - minutesPastBoundary)
        .withSecond(0)
        .withNano(0)
        .toInstant()
}

internal fun formatDecisionCountdown(
    targetAtEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    val deltaMillis = targetAtEpochMillis - nowEpochMillis
    if (deltaMillis <= 0L) {
        val elapsedMinutes = Duration.ofMillis(-deltaMillis).toMinutes()
        if (elapsedMinutes == 0L) return "已到"
        val hours = elapsedMinutes / 60L
        val minutes = elapsedMinutes % 60L
        return when {
            hours == 0L -> "已到${minutes}分"
            minutes == 0L -> "已到${hours}时"
            else -> "已到${hours}时${minutes}分"
        }
    }

    // Match xbot-android: the compact widget uses whole hours/days instead of
    // HH:mm, while the stored target remains the exact aligned boundary.
    val remainingHours = Duration.ofMillis(deltaMillis).toHours()
    return when {
        remainingHours < 1L -> "<1小时"
        remainingHours < 24L -> "${remainingHours}小时"
        else -> "${remainingHours / 24L}天"
    }
}

internal fun formatDecisionReminderDisplay(
    targetAtEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val target = Instant.ofEpochMilli(targetAtEpochMillis).atZone(zoneId)
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
    val targetText = if (target.toLocalDate() == now.toLocalDate()) {
        SameDayTargetFormatter.format(target)
    } else {
        OtherDayTargetFormatter.format(target)
    }
    return "$targetText · ${formatDecisionCountdown(targetAtEpochMillis, nowEpochMillis)}"
}

private val SameDayTargetFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val OtherDayTargetFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun sortDecisionReminders(
    reminders: List<DecisionReminder>,
    nowEpochMillis: Long = System.currentTimeMillis(),
): List<DecisionReminder> = reminders.sortedWith { left, right ->
    val leftExpired = left.targetAtEpochMillis <= nowEpochMillis
    val rightExpired = right.targetAtEpochMillis <= nowEpochMillis
    when {
        leftExpired && rightExpired ->
            right.targetAtEpochMillis.compareTo(left.targetAtEpochMillis)
        leftExpired -> -1
        rightExpired -> 1
        else -> left.targetAtEpochMillis.compareTo(right.targetAtEpochMillis)
    }
}
