package com.gouge.guaili.signals

import com.gouge.guaili.domain.*
import org.junit.Assert.*
import org.junit.Test

class SignalCompactLayoutTest {
    @Test fun narrowOrdinarySummaryUsesTwoRowsWithoutChangingRangeWidth() {
        assertTrue(signalCompactLayout(300f, 40f, listOf(110f), listOf(90f), 8f).inline)
        assertFalse(signalCompactLayout(200f, 40f, listOf(110f), listOf(90f), 8f).inline)
    }

    @Test fun narrowConflictPreservesRoomForBothRangesBeforeSecondarySymbolAndMeta() {
        val plan = signalCompactLayout(160f, 60f, listOf(80f, 130f), listOf(80f, 70f), 8f)
        assertEquals(22f, plan.symbolWidth, 0f)
        assertFalse(plan.showMeta)
        assertTrue(signalCompactLayout(320f, 60f, listOf(80f, 130f), listOf(80f, 70f), 8f).showMeta)
    }

    @Test fun compactWordsKeepStructureDirectionAndDistinctPhases() {
        val signal = GuailiSignal("BTCUSDT", GuailiSignalKind.Extreme,
            listOf(GuailiSignalRun(GuailiSignalDirection.Negative, listOf("1", "2", "3", "5", "8"), 12)),
            phase = GuailiSignalPhase.Formed)
        assertEquals("共振↓ · 新出现 · 5级", signalCompactLabel(signal))
        assertEquals("近 · 持续 · 5级", signalCompactLabel(signal.copy(kind = GuailiSignalKind.Compression, phase = GuailiSignalPhase.Ongoing)))
        assertTrue(signalCompactLabel(signal.copy(phase = GuailiSignalPhase.Ended)).contains("解除"))
        val original = signal.copy(transitionOnly = true, phase = GuailiSignalPhase.Narrowing)
        assertTrue(signalCompactLabel(original).startsWith("原 "))
        assertEquals(listOf("1m–8m"), signalCompactRanges(original))
    }
}
