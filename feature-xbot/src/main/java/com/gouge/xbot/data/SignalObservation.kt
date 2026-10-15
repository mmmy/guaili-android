package com.gouge.xbot.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A list observation is ordered when requested; a successful mutation is a write barrier. */
data class SignalObservation(
    val signals: List<SignalViewDto>,
    val revision: Long,
    val epoch: String,
    val complete: Boolean,
)

internal object SignalObservations {
    // Persisted widget snapshots may outlive the process. Its old sequence must not block a new process.
    private val epoch = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    fun begin(): Long = sequence.incrementAndGet()
    fun full(signals: List<SignalViewDto>, startedRevision: Long) = SignalObservation(signals, startedRevision, epoch, true)
    fun changed(signal: SignalViewDto) = SignalObservation(listOf(signal), begin(), epoch, false)
}
