package com.gouge.xbot.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import org.junit.Test

class ExpiryTimeTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `presets add their duration then align to the matching boundary`() {
        val now = Instant.parse("2026-08-10T02:14:00Z")

        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedPresetExpiry(15, now, zoneId),
        )
        assertEquals(
            Instant.parse("2026-08-10T03:00:00Z"),
            alignedPresetExpiry(30, now, zoneId),
        )
        assertEquals(
            Instant.parse("2026-08-10T04:00:00Z"),
            alignedPresetExpiry(60, now, zoneId),
        )
        assertEquals(
            Instant.parse("2026-08-10T05:00:00Z"),
            alignedPresetExpiry(120, now, zoneId),
        )
    }

    @Test
    fun `an exact boundary is kept after adding the preset duration`() {
        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedPresetExpiry(
                minutes = 15,
                now = Instant.parse("2026-08-10T02:15:00Z"),
                zoneId = zoneId,
            ),
        )
        assertEquals(
            Instant.parse("2026-08-10T03:00:00Z"),
            alignedPresetExpiry(
                minutes = 30,
                now = Instant.parse("2026-08-10T02:30:00Z"),
                zoneId = zoneId,
            ),
        )
        assertEquals(
            Instant.parse("2026-08-10T04:00:00Z"),
            alignedPresetExpiry(
                minutes = 60,
                now = Instant.parse("2026-08-10T03:00:00Z"),
                zoneId = zoneId,
            ),
        )
    }

    @Test
    fun `seconds are rounded up instead of shortening the selected duration`() {
        assertEquals(
            Instant.parse("2026-08-10T02:30:00Z"),
            alignedPresetExpiry(
                minutes = 15,
                now = Instant.parse("2026-08-10T02:14:30Z"),
                zoneId = zoneId,
            ),
        )
    }
}
