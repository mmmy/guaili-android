package com.gouge.guaili.widget

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionReminderTimeTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun presetsAddTheirDurationThenRoundTowardsTheNextBoundary() {
        val now = Instant.parse("2026-08-10T02:14:00Z")

        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedDecisionReminderTime(15, now, zoneId),
        )
        assertEquals(
            Instant.parse("2026-08-10T03:00:00Z"),
            alignedDecisionReminderTime(30, now, zoneId),
        )
        assertEquals(
            Instant.parse("2026-08-10T04:00:00Z"),
            alignedDecisionReminderTime(60, now, zoneId),
        )
    }

    @Test
    fun exactBoundaryIsKeptAfterAddingThePresetDuration() {
        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedDecisionReminderTime(
                minutes = 15,
                now = Instant.parse("2026-08-10T02:15:00Z"),
                zoneId = zoneId,
            ),
        )
    }

    @Test
    fun secondsRoundUpWithoutShorteningTheDuration() {
        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedDecisionReminderTime(
                minutes = 15,
                now = Instant.parse("2026-08-10T02:14:30Z"),
                zoneId = zoneId,
            ),
        )
    }

    @Test
    fun countdownUsesCompactSingleLineText() {
        val now = Instant.parse("2026-08-10T02:14:00Z").toEpochMilli()

        assertEquals("1小时", formatDecisionCountdown(
            Instant.parse("2026-08-10T04:00:00Z").toEpochMilli(),
            now,
        ))
        assertEquals("2天", formatDecisionCountdown(
            Instant.parse("2026-08-12T05:14:00Z").toEpochMilli(),
            now,
        ))
        assertEquals("<1小时", formatDecisionCountdown(
            Instant.parse("2026-08-10T03:13:59Z").toEpochMilli(),
            now,
        ))
        assertEquals("已到8分", formatDecisionCountdown(
            Instant.parse("2026-08-10T02:06:00Z").toEpochMilli(),
            now,
        ))
    }

    @Test
    fun reminderDisplayIncludesTheConcreteTargetTime() {
        val now = Instant.parse("2026-08-10T02:14:00Z").toEpochMilli()

        assertEquals(
            "12:00 · 1小时",
            formatDecisionReminderDisplay(
                targetAtEpochMillis = Instant.parse("2026-08-10T04:00:00Z").toEpochMilli(),
                nowEpochMillis = now,
                zoneId = zoneId,
            ),
        )
        assertEquals(
            "08-11 12:00 · 1天",
            formatDecisionReminderDisplay(
                targetAtEpochMillis = Instant.parse("2026-08-11T04:00:00Z").toEpochMilli(),
                nowEpochMillis = now,
                zoneId = zoneId,
            ),
        )
    }

    @Test
    fun remindersPutDueItemsAndNearestUpcomingTargetFirst() {
        val now = 10_000L
        val oldExpired = reminder("old-expired", 1_000L)
        val justExpired = reminder("just-expired", 9_000L)
        val nearest = reminder("nearest", 11_000L)
        val later = reminder("later", 20_000L)

        assertEquals(
            listOf("just-expired", "old-expired", "nearest", "later"),
            sortDecisionReminders(
                listOf(later, oldExpired, nearest, justExpired),
                now,
            ).map(DecisionReminder::id),
        )
    }

    private fun reminder(id: String, targetAtEpochMillis: Long) = DecisionReminder(
        id = id,
        symbol = "BTCUSDT",
        interval = "15",
        direction = DecisionDirection.Long,
        targetAtEpochMillis = targetAtEpochMillis,
    )
}
