package com.gouge.guaili.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KlineRefreshScheduleTest {
    @Test fun displayedDeadlineMatchesRequestBoundaryAndNextCycle() = runTest {
        var deadline = 0L
        val requests = mutableListOf<Long>()
        backgroundScope.launch {
            runKlineRefreshSchedule(5, { testScheduler.currentTime }, { deadline = it }) {
                requests += testScheduler.currentTime
            }
        }
        runCurrent()
        assertEquals(5_000L, deadline)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(emptyList<Long>(), requests)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(5_000L), requests)
        assertEquals(10_000L, deadline)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(5_000L, 10_000L), requests)
    }

    @Test fun cancellingForBackgroundStopsRequestsAndResumeStartsFreshDeadline() = runTest {
        var deadline = 0L
        var requests = 0
        val first = backgroundScope.launch {
            runKlineRefreshSchedule(5, { testScheduler.currentTime }, { deadline = it }) { requests++ }
        }
        runCurrent(); advanceTimeBy(2_000); first.cancel(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, requests)
        backgroundScope.launch {
            runKlineRefreshSchedule(3, { testScheduler.currentTime }, { deadline = it }) { requests++ }
        }
        runCurrent()
        assertEquals(15_000L, deadline)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, requests)
    }
}
