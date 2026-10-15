package com.gouge.xbot.data

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SignalObservationTest {
    @Test fun `list results retain request order when they complete out of order`() {
        val firstStarted = SignalObservations.begin()
        val secondStarted = SignalObservations.begin()
        val secondResult = SignalObservations.full(emptyList(), secondStarted)
        val firstResult = SignalObservations.full(emptyList(), firstStarted)
        assertTrue(firstResult.revision < secondResult.revision)
        assertTrue(firstResult.complete)
    }

    @Test fun `successful mutation supersedes every list requested before the write completed`() {
        val beforePatch = SignalObservations.begin()
        val duringPatch = SignalObservations.begin()
        val mutation = SignalObservations.changed(SignalViewDto(id = "a"))
        val delayedRead = SignalObservations.full(emptyList(), duringPatch)
        assertTrue(beforePatch < mutation.revision)
        assertTrue(delayedRead.revision < mutation.revision)
        assertFalse(mutation.complete)
    }
}
