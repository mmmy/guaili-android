package com.gouge.xbot.domain

import com.gouge.xbot.data.SignalViewDto
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SignalPresentationTest {
    @Test
    fun `direction is derived from long and short switches`() {
        assertEquals(DirectionState.LongOnly, directionState(longOn = true, shortOn = false))
        assertEquals(DirectionState.ShortOnly, directionState(longOn = false, shortOn = true))
        assertEquals(DirectionState.Both, directionState(longOn = true, shortOn = true))
        assertEquals(DirectionState.Disabled, directionState(longOn = false, shortOn = false))
    }

    @Test
    fun `level text includes periods and numeric range`() {
        val signal = SignalViewDto(
            id = "signal-1",
            periods = listOf("5", "15", "60"),
            levelMin = "1",
            levelMax = "3",
        )

        assertEquals("5 · 15 · 60  L1–3", signal.levelText())
    }

    @Test
    fun `signal comment splits four trade actions like web`() {
        val items = parseSignalComment(
            "做多：突破均线 做空:跌破均线 平多：反向信号 平空: 趋势反转",
        )

        assertEquals(
            listOf(
                SignalCommentItem(SignalCommentType.OpenLong, "突破均线"),
                SignalCommentItem(SignalCommentType.OpenShort, "跌破均线"),
                SignalCommentItem(SignalCommentType.CloseLong, "反向信号"),
                SignalCommentItem(SignalCommentType.CloseShort, "趋势反转"),
            ),
            items,
        )
    }

    @Test
    fun `plain signal comment remains visible`() {
        assertEquals(
            listOf(SignalCommentItem(type = null, text = "普通备注")),
            parseSignalComment("  普通备注  "),
        )
        assertTrue(parseSignalComment(null).isEmpty())
    }

    @Test
    fun `missing expiry is permanent`() {
        val result = formatExpiry(null)

        assertEquals("● 运行中 · 长期有效", result.text)
        assertFalse(result.isExpired)
    }

    @Test
    fun `past expiry is marked expired`() {
        val result = formatExpiry(
            expireAt = "2026-08-10T10:00:00+08:00",
            now = Instant.parse("2026-08-10T03:00:00Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertTrue(result.isExpired)
        assertEquals("已过期 · 08-10 10:00", result.text)
    }

    @Test
    fun `widget expiry uses hours below one day and days from one day`() {
        val now = Instant.parse("2026-08-10T00:00:00Z")
        val zoneId = ZoneId.of("UTC")

        assertEquals(
            "即将过期 · 08-10 00:59 · <1小时",
            formatWidgetExpiry("2026-08-10T00:59:59Z", now, zoneId).text,
        )
        assertEquals(
            "● 运行中 · 有效至 08-10 23:59 · 23小时",
            formatWidgetExpiry("2026-08-10T23:59:59Z", now, zoneId).text,
        )
        assertEquals(
            "● 运行中 · 有效至 08-11 00:00 · 1天",
            formatWidgetExpiry("2026-08-11T00:00:00Z", now, zoneId).text,
        )
        assertEquals(
            "● 运行中 · 有效至 08-12 12:00 · 2天",
            formatWidgetExpiry("2026-08-12T12:00:00Z", now, zoneId).text,
        )
    }

    @Test
    fun `widget expiry handles permanent and expired signals`() {
        val now = Instant.parse("2026-08-10T00:00:00Z")

        assertEquals("● 运行中 · 长期有效", formatWidgetExpiry(null, now).text)
        val expired = formatWidgetExpiry(
            "2026-08-10T00:00:00Z",
            now,
            ZoneId.of("UTC"),
        )
        assertEquals("已过期 · 08-10 00:00", expired.text)
        assertTrue(expired.isExpired)
    }
}
