package com.gouge.guaili.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CellDetailSheetTest {
    @Test
    fun decimalValuesUseReadablePrecision() {
        assertEquals("2.9942", formatDecimal(2.994190196685949, 4))
        assertEquals("84.21%", formatDecimal(84.21052631578947, 2, "%"))
        assertEquals("-", formatDecimal(null, 2))
    }

    @Test
    fun isoTimesUseCompactDisplayFormat() {
        assertEquals(
            "07-11 16:20:00",
            formatDateTime("2026-07-11T16:20:00.000+08:00"),
        )
        assertEquals("-", formatDateTime(null))
    }
}
