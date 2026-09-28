package com.gouge.guaili.widget

import com.gouge.guaili.domain.*
import org.junit.Assert.*
import org.junit.Test

class GuailiSignalPresentationTest {
    private val positive = GuailiSignal("BTCUSDT", GuailiSignalKind.Extreme,
        listOf(GuailiSignalRun(GuailiSignalDirection.Positive, listOf("1", "2", "3", "5", "8", "10"), 12)))

    @Test fun sixLevelsDescribeCoverageWithoutReversalArrowOrStrength() {
        assertEquals("上方乖离共振", signalTitle(positive))
        assertEquals("1m–10m · 6级共振", signalSummary(positive, "EMA20"))
        assertEquals("观察 · 首次观测", signalChangeText(positive))
        assertFalse(signalTitle(positive).contains("强"))
    }

    @Test fun conflictShowsBothDirectionsAndSeparateCounts() {
        val conflict = positive.copy(kind = GuailiSignalKind.Conflict, runs = listOf(
            positive.primaryRun.copy(direction = GuailiSignalDirection.Negative),
            GuailiSignalRun(GuailiSignalDirection.Positive, listOf("15", "20", "30", "45", "60"), 12)))
        assertEquals("长短周期分歧", signalTitle(conflict))
        assertEquals("短负 1m–10m（6级） · 长正 15m–60m（5级）", signalSummary(conflict, "SMA50"))
        assertEquals("60m SMA50 上行 · 回调结构观察", signalTrendText(conflict.copy(trend = GuailiSignalTrend.Up), "SMA50"))
    }

    @Test fun exitDoesNotDescribeFormerRunAsCurrentlyActive() {
        val event = positive.copy(transitionOnly = true, phase = GuailiSignalPhase.Narrowing)
        assertEquals("上方乖离共振 · 变化", signalTitle(event))
        assertTrue(signalSummary(event, "EMA20").startsWith("原区间："))
        assertEquals("观察 · 乖离收窄", signalChangeText(event))
        assertEquals("10m 趋势数据不足", signalTrendText(event, "EMA20"))
        assertFalse(signalTrendText(event.copy(trend = GuailiSignalTrend.Down), "EMA20").contains("反弹"))
    }
}
