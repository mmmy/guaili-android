package com.gouge.guaili.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiTableTest {
    @Test
    fun formatIntervalCompactsMinuteIntervals() {
        assertEquals("1m", formatInterval("1"))
        assertEquals("15m", formatInterval("15"))
        assertEquals("1h", formatInterval("60"))
        assertEquals("2h", formatInterval("120"))
        assertEquals("1d", formatInterval("1d"))
    }
}
