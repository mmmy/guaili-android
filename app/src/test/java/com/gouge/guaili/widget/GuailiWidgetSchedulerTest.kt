package com.gouge.guaili.widget

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiWidgetSchedulerTest {
    @Test
    fun manualRefreshReplacesUnfinishedWork() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            GuailiWidgetScheduler.ImmediateWorkPolicy,
        )
    }
}
