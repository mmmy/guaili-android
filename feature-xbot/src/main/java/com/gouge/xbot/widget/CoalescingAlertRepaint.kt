package com.gouge.xbot.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Keep one running repaint and at most one follow-up using the latest cache state. */
internal class CoalescingAlertRepaint<T>(private val scope: CoroutineScope, private val repaint: suspend (T) -> Unit) {
    private val monitor = Any()
    private var requested = 0L
    private var latest: T? = null
    private var work: Deferred<Unit>? = null

    fun request(value: T): Deferred<Unit> {
        val result = synchronized(monitor) {
            latest = value
            requested++
            work ?: scope.async(start = CoroutineStart.LAZY) {
                while (true) {
                    val (current, version) = synchronized(monitor) { latest to requested }
                    @Suppress("UNCHECKED_CAST")
                    repaint(current as T)
                    val finished = synchronized(monitor) {
                        if (version == requested) { work = null; true } else false
                    }
                    if (finished) return@async
                }
            }.also { created ->
                work = created
                created.invokeOnCompletion { synchronized(monitor) { if (work === created) work = null } }
            }
        }
        result.start()
        return result
    }
}
