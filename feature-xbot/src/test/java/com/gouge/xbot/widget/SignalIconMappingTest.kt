package com.gouge.xbot.widget

import com.gouge.xbot.R
import com.gouge.xbot.domain.DirectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalIconMappingTest {
    @Test
    fun `default mappings contain both built-in signals`() {
        assertEquals(
            SignalIconType.MaTrend,
            resolveSignalIcon("MA-TREND", DefaultSignalIconMappings),
        )
        assertEquals(
            SignalIconType.AtrIndex,
            resolveSignalIcon("ATR-INDEX", DefaultSignalIconMappings),
        )
    }

    @Test
    fun `matching ignores surrounding spaces and case`() {
        assertEquals(
            SignalIconType.MaTrend,
            resolveSignalIcon("  ma-trend ", DefaultSignalIconMappings),
        )
    }

    @Test
    fun `disabled and partial mappings do not match`() {
        val disabledMappings = listOf(
            DefaultSignalIconMappings.first().copy(enabled = false),
        )

        assertNull(resolveSignalIcon("MA-TREND", disabledMappings))
        assertNull(resolveSignalIcon("MA-TREND-V2", DefaultSignalIconMappings))
    }

    @Test
    fun `ma trend selects a distinct drawable for every direction`() {
        assertEquals(
            R.drawable.xbot_ic_signal_ma_trend,
            SignalIconType.MaTrend.drawableResource(DirectionState.LongOnly),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_ma_trend_short,
            SignalIconType.MaTrend.drawableResource(DirectionState.ShortOnly),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_ma_trend_both,
            SignalIconType.MaTrend.drawableResource(DirectionState.Both),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_ma_trend_disabled,
            SignalIconType.MaTrend.drawableResource(DirectionState.Disabled),
        )
        assertNotEquals(
            SignalIconType.MaTrend.drawableResource(DirectionState.LongOnly),
            SignalIconType.MaTrend.drawableResource(DirectionState.ShortOnly),
        )
    }

    @Test
    fun `atr index selects a distinct drawable for every direction`() {
        assertEquals(
            R.drawable.xbot_ic_signal_atr_index,
            SignalIconType.AtrIndex.drawableResource(DirectionState.LongOnly),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_atr_index_short,
            SignalIconType.AtrIndex.drawableResource(DirectionState.ShortOnly),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_atr_index_both,
            SignalIconType.AtrIndex.drawableResource(DirectionState.Both),
        )
        assertEquals(
            R.drawable.xbot_ic_signal_atr_index_disabled,
            SignalIconType.AtrIndex.drawableResource(DirectionState.Disabled),
        )
    }
}
