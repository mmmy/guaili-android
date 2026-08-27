package com.gouge.guaili.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigStoreTest {
    @Test
    fun defaultIntervalsPreferUsefulShortMediumAndDailyPeriods() {
        val available = listOf("10D", "W", "D", "240", "60", "15", "5", "1")

        assertEquals(
            listOf("5", "15", "60", "D"),
            WidgetConfigStore.defaultIntervals(available),
        )
    }

    @Test
    fun defaultIntervalsFallBackToAvailableOrder() {
        val available = listOf("10", "30", "240", "W", "10D")

        assertEquals(
            listOf("10", "30", "240", "W"),
            WidgetConfigStore.defaultIntervals(available),
        )
    }
}
