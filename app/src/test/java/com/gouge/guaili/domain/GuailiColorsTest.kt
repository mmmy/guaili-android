package com.gouge.guaili.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiColorsTest {
    @Test
    fun clampsStrengthAtConfiguredMax() {
        assertEquals(-1.0f, guailiColorStrength(-100, maxAbs = 20))
        assertEquals(-0.5f, guailiColorStrength(-10, maxAbs = 20))
        assertEquals(0.0f, guailiColorStrength(0, maxAbs = 20))
        assertEquals(0.5f, guailiColorStrength(10, maxAbs = 20))
        assertEquals(1.0f, guailiColorStrength(100, maxAbs = 20))
    }
}
