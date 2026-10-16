package com.gouge.xbot.widget

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AlertRefreshFlightsTest {
    @Test fun overlappingIdenticalRefreshesFetchOnceAndShareTheirResult() = runBlocking {
        val flights = AlertRefreshFlights<String, Any>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val value = Any()
        suspend fun request() = flights.run("scope+selection") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            value
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { request() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { request() }
        started.await()
        release.complete(Unit)
        assertSame(value, first.await())
        assertSame(value, second.await())
        assertEquals(1, calls.get())
    }

    @Test fun leavingOneScreenDoesNotCancelAWidgetAwaitingTheSameRefresh() = runBlocking {
        val flights = AlertRefreshFlights<String, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flights.run("account") { started.complete(Unit); release.await(); 7 }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) { flights.run("account") { error("duplicate fetch") } }
        started.await()
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals(7, second.await())
    }

    @Test fun coalescedRepaintsBuildOffTheCallingThreadAndUseTheLatestPendingState() = runBlocking {
        val callerThread = Thread.currentThread().id
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val painted = mutableListOf<Int>()
            val queue = CoalescingAlertRepaint<Int>(kotlinx.coroutines.CoroutineScope(dispatcher)) { value ->
                assertNotEquals(callerThread, Thread.currentThread().id)
                painted.add(value)
                if (value == 1) { started.complete(Unit); release.await() }
            }
            val first = queue.request(1)
            started.await()
            val second = queue.request(2)
            val third = queue.request(3)
            assertSame(first, second)
            assertSame(second, third)
            release.complete(Unit)
            third.await()
            assertEquals(listOf(1, 3), painted)
        } finally { dispatcher.close() }
    }

    @Test fun oldRefreshCannotReviveDeletedOrResetCookieWhileOtherCookiesStillUpdate() {
        val edits = AlertSnapshotEdits()
        val started = edits.beginRefresh()
        edits.changed("deleted")
        edits.changed("reset")
        val result = edits.merge(
            mapOf("deleted" to emptyList(), "reset" to listOf(99), "unchanged" to listOf(1)),
            mapOf("deleted" to listOf(5), "reset" to listOf(8), "unchanged" to listOf(2)),
            started,
        )
        assertEquals(emptyList<Int>(), result["deleted"])
        assertEquals(listOf(99), result["reset"])
        assertEquals(listOf(2), result["unchanged"])
        val next = edits.merge(result, mapOf("reset" to listOf(100)), edits.beginRefresh())
        assertEquals(listOf(100), next["reset"])
    }
}
