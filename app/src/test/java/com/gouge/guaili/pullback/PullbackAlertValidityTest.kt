package com.gouge.guaili.pullback

import com.gouge.guaili.data.PriceAlertDto
import com.gouge.guaili.data.PriceAlertGeometry
import com.gouge.guaili.data.PriceAlertPoint
import org.junit.Assert.*
import org.junit.Test

class PullbackAlertValidityTest {
    private val now = 1_800_000_000_000L
    private fun alert() = PriceAlertDto(1, 1, 1, "BTCUSDT", "120", "BTCUSDT.P", "test",
        PriceAlertGeometry("horizontal_segment", PriceAlertPoint(now - 60_000, 100.0), PriceAlertPoint(now + 1, 100.0), "none"),
        "cross_any", "once", "active", webhookUrl = "", messageTemplate = "{}", label = "", dataStatus = "live",
        createdAt = now, updatedAt = now, intent = "open_long")

    @Test fun greatestWholeUnitAndNextDisplayBoundaryAreExact() {
        data class Case(val remaining: Long, val label: String, val unit: AlertValidityUnit, val changeIn: Long)
        val cases = listOf(
            Case(2 * 86_400_000L + 5 * 3_600_000, "2D", AlertValidityUnit.Days, 5 * 3_600_000L + 1),
            Case(86_400_000L, "1D", AlertValidityUnit.Days, 1),
            Case(86_400_000L - 1, "23H", AlertValidityUnit.Hours, 3_600_000),
            Case(5 * 3_600_000L + 20 * 60_000, "5H", AlertValidityUnit.Hours, 20 * 60_000L + 1),
            Case(3_600_000L, "1H", AlertValidityUnit.Hours, 1),
            Case(3_600_000L - 1, "59m", AlertValidityUnit.Minutes, 60_000),
            Case(23 * 60_000L + 54_000, "23m", AlertValidityUnit.Minutes, 54_001),
            Case(60_000L, "1m", AlertValidityUnit.Minutes, 1),
            Case(59_999L, "<1m", AlertValidityUnit.Minutes, 60_000),
            Case(0L, "<1m", AlertValidityUnit.Minutes, 1),
        )
        for (case in cases) {
            val record = alert().let { it.copy(geometry = it.geometry.copy(second = it.geometry.second.copy(timeMs = now + case.remaining))) }
            val display = record.pullbackDisplay(now)
            assertEquals(case.label, display.label)
            assertEquals(case.unit, display.unit)
            assertEquals(now + case.changeIn, display.nextChangeAt)
            assertEquals(display, record.pullbackDisplay(display.nextChangeAt!! - 1))
            assertNotEquals(case.label, record.pullbackDisplay(display.nextChangeAt).label)
            assertTrue(display.description.startsWith("剩余有效时间"))
        }
    }

    @Test fun earlierExpiryWinsAndExtendedLinesIgnoreTheirSecondPoint() {
        val line = alert().let { it.copy(geometry = it.geometry.copy(second = it.geometry.second.copy(timeMs = now + 2 * 86_400_000))) }
        assertEquals("5H", line.copy(expiresAt = now + 5 * 3_600_000L).pullbackDisplay(now).label)
        assertEquals("2D", line.copy(expiresAt = now + 3 * 86_400_000L).pullbackDisplay(now).label)
        for (extend in listOf("right", "both")) {
            val extended = line.copy(geometry = line.geometry.copy(extend = extend, second = line.geometry.second.copy(timeMs = now - 1)))
            assertEquals("监测", extended.pullbackDisplay(now).label)
            assertNull(extended.pullbackDisplay(now).nextChangeAt)
            assertEquals("5H", extended.copy(expiresAt = now + 5 * 3_600_000L).pullbackDisplay(now).label)
        }
        assertEquals("已过期", line.copy(expiresAt = now).pullbackDisplay(now).label)
        val ended = line.copy(geometry = line.geometry.copy(second = line.geometry.second.copy(timeMs = now - 1)))
        assertEquals("范围结束", ended.pullbackDisplay(now).label)
        assertTrue(ended.pullbackDisplay(now).extendable)
        assertNull(ended.pullbackDisplay(now).nextChangeAt)
    }

    @Test fun futureStartDoesNotBecomeStartCountdownOrHideDataErrors() {
        val future = alert().let { it.copy(dataStatus = "waiting_for_range", geometry = it.geometry.copy(
            first = it.geometry.first.copy(timeMs = now + 1000), second = it.geometry.second.copy(timeMs = now + 86_400_000))) }
        assertEquals("1D", future.pullbackDisplay(now).label)
        assertEquals("未开始", future.copy(geometry = future.geometry.copy(extend = "right")).pullbackDisplay(now).label)
        for ((data, expected) in listOf("disconnected" to "行情断开", "waiting_for_metadata" to "等精度",
            "invalid_line" to "线价无效", "waiting_for_price" to "等行情", "range_ended" to "待更新")) {
            val display = future.copy(dataStatus = data).pullbackDisplay(now)
            assertEquals(expected, display.label)
            assertNull(display.unit)
            assertNull(display.nextChangeAt)
        }
        assertEquals("待更新", future.pullbackDisplay(now, stale = true).label)
    }

    @Test fun triggerWinsEvenAfterExpiryOrFailedRefreshAndExtremeEndCannotSpinTimer() {
        val triggered = alert().copy(status = "triggered", expiresAt = now - 1)
        for (stale in listOf(false, true)) {
            val display = triggered.pullbackDisplay(now, stale)
            assertEquals("已触发", display.label)
            assertNull(display.unit)
            assertNull(display.nextChangeAt)
            assertFalse(display.extendable)
        }
        for (status in listOf("disabled", "expired")) assertNull(alert().copy(status = status).pullbackDisplay(now).unit)
        val last = alert().let { it.copy(geometry = it.geometry.copy(second = it.geometry.second.copy(timeMs = Long.MAX_VALUE))) }
        assertNull(last.pullbackDisplay(Long.MAX_VALUE).nextChangeAt)
        val invalid = alert().let { it.copy(geometry = it.geometry.copy(second = it.geometry.first)) }
        assertEquals("线价无效", invalid.pullbackDisplay(now).label)
    }
}
