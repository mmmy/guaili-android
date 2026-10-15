package com.gouge.guaili.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshSingleFlightTest {
    @Test fun cancellingTheInitiatorKeepsAnotherSubscriberAndCompletedResultsAreNotCached() = runTest {
        val refreshes = RefreshSingleFlight<String, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        var calls = 0
        val initiator = async { refreshes.run("same") { calls++; started.complete(Unit); release.await() } }
        started.await()
        val subscriber = async { refreshes.run("same") { error("must join the existing refresh") } }
        runCurrent()
        initiator.cancelAndJoin()
        assertTrue(subscriber.isActive)
        release.complete(7)
        assertEquals(7, subscriber.await())
        assertEquals(8, refreshes.run("same") { calls++; 8 })
        assertEquals(2, calls)
    }

    @Test fun theLastSubscriberCancelsWorkAndReleasesTheObservationLock() = runTest {
        val refreshes = RefreshSingleFlight<String, Int>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            refreshes.run("same") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        started.await()
        request.cancelAndJoin()
        cancelled.await()
        assertEquals(9, refreshes.run("same") { 9 })
    }

    @Test fun distinctRequestsRemainOrderedAndSharedFailuresAllowRetry() = runTest {
        val refreshes = RefreshSingleFlight<String, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = async { runCatching { refreshes.run("first") { started.complete(Unit); release.await(); error("offline") } } }
        started.await()
        val joined = async { runCatching { refreshes.run("first") { error("must not run") } } }
        val second = async { refreshes.run("second") { secondStarted = true; 2 } }
        runCurrent()
        assertFalse(secondStarted)
        release.complete(Unit)
        assertEquals("offline", first.await().exceptionOrNull()?.message)
        assertEquals("offline", joined.await().exceptionOrNull()?.message)
        assertEquals(2, second.await())
        assertEquals(3, refreshes.run("first") { 3 })
    }
}
