package com.gouge.xbot.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identical callers share a fetch. Local cache mutations use a separate, short-lived lock. */
internal class AlertRefreshFlights<K, V> {
    private class Flight<V>(val result: Deferred<V>, var waiters: Int = 1)
    private val monitor = Any()
    private val flights = mutableMapOf<K, Flight<V>>()
    private val observations = Mutex()

    suspend fun run(key: K, block: suspend () -> V): V {
        val caller = currentCoroutineContext()
        val flight = synchronized(monitor) {
            flights[key]?.also { it.waiters++ } ?: run {
                val scope = CoroutineScope(caller.minusKey(Job) + SupervisorJob())
                val result = scope.async(start = CoroutineStart.LAZY) { observations.withLock { block() } }
                Flight(result).also { created ->
                    flights[key] = created
                    result.invokeOnCompletion {
                        synchronized(monitor) { if (flights[key] === created) flights.remove(key) }
                        scope.cancel()
                    }
                }
            }
        }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            synchronized(monitor) {
                flight.waiters--
                if (flight.waiters == 0 && !flight.result.isCompleted) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.result.cancel()
                }
            }
        }
    }
}

/** Accessed under AlertDataCoordinator's data lock, never across a network wait. */
internal class AlertSnapshotEdits {
    private var sequence = 0L
    private val cookieRevisions = mutableMapOf<String, Long>()

    fun beginRefresh(): Long = sequence

    fun changed(cookieId: String) { cookieRevisions[cookieId] = ++sequence }

    fun <T> merge(current: Map<String, T>, fetched: Map<String, T>, refreshRevision: Long): Map<String, T> =
        current + fetched.filterKeys { (cookieRevisions[it] ?: 0L) <= refreshRevision }
}
