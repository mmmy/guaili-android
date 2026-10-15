package com.gouge.guaili.data

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

/** Keep distinct observations ordered while sharing an identical in-flight observation. */
internal class RefreshSingleFlight<K, V> {
    private class Flight<V>(val scope: CoroutineScope, val result: Deferred<V>, var waiters: Int = 1)

    private val monitor = Any()
    private val flights = mutableMapOf<K, Flight<V>>()
    private val observations = Mutex()

    suspend fun run(key: K, block: suspend () -> V): V {
        val callerContext = currentCoroutineContext()
        val flight = synchronized(monitor) {
            flights[key]?.also { it.waiters++ } ?: run {
                // A screen leaving must not cancel a widget that is awaiting the same refresh.
                // Retain the caller's dispatcher (including a test dispatcher), not its Job.
                val scope = CoroutineScope(callerContext.minusKey(Job) + SupervisorJob())
                val result = scope.async(start = CoroutineStart.LAZY) { observations.withLock { block() } }
                Flight(scope, result).also { created ->
                    flights[key] = created
                    result.invokeOnCompletion {
                        synchronized(monitor) {
                            if (flights[key] === created) flights.remove(key)
                        }
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

internal class RefreshSinkIdentity(private val sink: Any) {
    override fun equals(other: Any?): Boolean = other is RefreshSinkIdentity && sink === other.sink
    override fun hashCode(): Int = System.identityHashCode(sink)
}
