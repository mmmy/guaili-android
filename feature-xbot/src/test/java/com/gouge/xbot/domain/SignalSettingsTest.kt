package com.gouge.xbot.domain

import kotlin.test.assertEquals
import org.junit.Test

class SignalSettingsTest {
    @Test
    fun `periods are sorted by duration order and deduplicated`() {
        assertEquals(
            listOf("5S", "15", "60", "D", "W"),
            sortSignalPeriods(listOf("W", "60", "15", "5S", "60", "D")),
        )
    }

    @Test
    fun `fill range includes every supported period between bounds`() {
        assertEquals(
            listOf("15", "20", "30", "45", "60"),
            fillSignalPeriodRange(listOf("60", "15")),
        )
    }

    @Test
    fun `fill range leaves a single selection unchanged`() {
        assertEquals(listOf("D"), fillSignalPeriodRange(listOf("D")))
    }
}
