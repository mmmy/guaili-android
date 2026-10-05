package com.gouge.xbot.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class BusinessExpiryPresentationTest {
    private val now = Instant.parse("2026-09-12T01:00:00Z")
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `unconfigured has no countdown or fabricated date`() {
        val presentation = businessExpiryPresentation(null, now, zoneId)

        assertEquals(BusinessExpiryState.Unconfigured, presentation.state)
        assertEquals("业务过期：未配置", presentation.statusText)
        assertNull(presentation.expiresAtText)
    }

    @Test
    fun `valid expiry shows remaining time and exact local timestamp`() {
        val presentation = businessExpiryPresentation(now.plusSeconds(9_000), now, zoneId)

        assertEquals(BusinessExpiryState.Valid, presentation.state)
        assertEquals("业务有效 · 剩余 2小时30分", presentation.statusText)
        assertEquals("2026-09-12 11:30:00 +08:00", presentation.expiresAtText)
    }

    @Test
    fun `changes state exactly at the expiry boundary`() {
        val before = businessExpiryPresentation(now, now.minusNanos(1), zoneId)
        val at = businessExpiryPresentation(now, now, zoneId)
        val after = businessExpiryPresentation(now, now.plusNanos(1), zoneId)

        assertEquals(BusinessExpiryState.Valid, before.state)
        assertEquals("业务有效 · 剩余 不足1秒", before.statusText)
        assertEquals(BusinessExpiryState.Expired, at.state)
        assertEquals("业务已过期 · 刚刚过期", at.statusText)
        assertEquals(BusinessExpiryState.Expired, after.state)
        assertEquals("业务已过期 · 超时 不足1秒", after.statusText)
    }

    @Test
    fun `expired time reports elapsed duration and preserves expiry timestamp`() {
        val presentation = businessExpiryPresentation(now.minusSeconds(910), now, zoneId)

        assertEquals(BusinessExpiryState.Expired, presentation.state)
        assertEquals("业务已过期 · 超时 15分10秒", presentation.statusText)
        assertEquals("2026-09-12 08:44:50 +08:00", presentation.expiresAtText)
    }

    @Test
    fun `countdowns cover seconds minutes hours and days without zero suffixes`() {
        val cases = mapOf(
            1L to "1秒",
            59L to "59秒",
            60L to "1分",
            61L to "1分1秒",
            3_600L to "1小时",
            3_660L to "1小时1分",
            86_400L to "1天",
            183_600L to "2天3小时",
        )
        cases.forEach { (seconds, expected) ->
            assertEquals(
                "业务有效 · 剩余 $expected",
                businessExpiryPresentation(now.plusSeconds(seconds), now, zoneId).statusText,
            )
            assertEquals(
                "业务已过期 · 超时 $expected",
                businessExpiryPresentation(now.minusSeconds(seconds), now, zoneId).statusText,
            )
        }
    }

    @Test
    fun `timezone only changes the date label and never the status or countdown`() {
        val local = businessExpiryPresentation(now.plusSeconds(30), now, zoneId)
        val utc = businessExpiryPresentation(now.plusSeconds(30), now, ZoneId.of("UTC"))

        assertEquals(local.state, utc.state)
        assertEquals(local.statusText, utc.statusText)
        assertEquals("2026-09-12 01:00:30 +00:00", utc.expiresAtText)
    }
}
