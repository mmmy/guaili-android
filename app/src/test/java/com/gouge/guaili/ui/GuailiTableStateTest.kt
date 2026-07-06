package com.gouge.guaili.ui

import com.gouge.guaili.settings.GuailiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuailiTableStateTest {
    @Test
    fun defaultsUseSettingsAndStartLoading() {
        val defaults = GuailiSettings.defaults()
        val state = GuailiTableState()

        assertEquals(defaults, state.settings)
        assertEquals(defaults.symbols, state.symbols)
        assertEquals(defaults.intervals, state.intervals)
        assertTrue(state.cells.isEmpty())
        assertTrue(state.isLoading)
        assertFalse(state.isRefreshing)
        assertNull(state.lastUpdatedAt)
        assertNull(state.errorMessage)
        assertFalse(state.isStale)
    }
}
