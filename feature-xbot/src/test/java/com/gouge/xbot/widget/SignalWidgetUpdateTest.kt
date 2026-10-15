package com.gouge.xbot.widget

import com.gouge.xbot.data.SignalViewDto
import com.gouge.xbot.data.SignalObservation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class SignalWidgetUpdateTest {
    @Test fun `foreground list updates selected snapshots and keeps widget display choices`() {
        val old = SignalViewDto(id = "selected", symbol = "BTCUSDT", periods = listOf("15"))
        val state = WidgetState(listOf(WidgetSnapshot.from(old, updatedAtMillis = 1, showSymbol = false)))
        val latest = old.copy(periods = listOf("60"), longOn = true)

        val updated = signalWidgetUpdate(state, observation(listOf(latest), 1))

        assertEquals(listOf("60"), updated.state.signals.single().periods)
        assertFalse(updated.state.signals.single().showSymbol)
        assertEquals(null, updated.status)
    }

    @Test fun `missing selected signal retains its last snapshot and reports missing data`() {
        val first = WidgetSnapshot.from(SignalViewDto(id = "first"), updatedAtMillis = 1)
        val missing = WidgetSnapshot.from(SignalViewDto(id = "missing"), updatedAtMillis = 1)
        val updated = signalWidgetUpdate(WidgetState(listOf(first, missing)), observation(listOf(first.toSignalViewDto()), 1))

        assertEquals(missing.copy(observationEpoch = "test", observationRevision = 1, observationMissing = true), updated.state.signals[1])
        assertEquals("部分信号已不存在", updated.status)
        val allMissing = signalWidgetUpdate(WidgetState(listOf(missing)), observation(emptyList(), 1))
        assertEquals(missing.updatedAtMillis, allMissing.state.signals.single().updatedAtMillis)
        assertEquals("信号已不存在", allMissing.status)
    }

    @Test fun `partial mutation changes only its returned signal and preserves a newer unrelated snapshot`() {
        val a = SignalViewDto(id = "a", periods = listOf("15"))
        val b = SignalViewDto(id = "b", periods = listOf("15"))
        val initial = WidgetState(listOf(WidgetSnapshot.from(a, 1), WidgetSnapshot.from(b, 1)))
        val refreshedB = b.copy(periods = listOf("60"))
        val fresh = signalWidgetUpdate(initial, observation(listOf(a, refreshedB), 2), 20).state

        val result = signalWidgetUpdate(fresh, observation(listOf(a.copy(periods = listOf("D"))), 3, complete = false), 30)

        assertEquals(listOf("D"), result.state.signals[0].periods)
        assertEquals(fresh.signals[1], result.state.signals[1], "A PATCH must not rewrite or retimestamp B")
        assertEquals(null, result.status, "A partial result does not declare other selected signals missing")
    }

    @Test fun `late old full list cannot overwrite a newer mutation or mark it missing`() {
        val a = SignalViewDto(id = "a", periods = listOf("15"))
        val b = SignalViewDto(id = "b")
        val initial = WidgetState(listOf(WidgetSnapshot.from(a, 1), WidgetSnapshot.from(b, 1)))
        val mutation = signalWidgetUpdate(initial, observation(listOf(a.copy(periods = listOf("60"))), 3, false), 30).state

        val old = signalWidgetUpdate(mutation, observation(listOf(a, b), 2), 40)
        assertEquals(mutation.signals[0], old.state.signals[0])
        assertEquals(40L, old.state.signals[1].updatedAtMillis)
        val oldMissing = signalWidgetUpdate(mutation, observation(listOf(b), 2), 40)
        assertEquals(mutation.signals[0], oldMissing.state.signals[0])
        assertEquals(null, oldMissing.status)
    }

    @Test fun `late old full list cannot roll back a newer full result or revive a missing signal`() {
        val a = SignalViewDto(id = "a", periods = listOf("15"))
        val initial = WidgetState(listOf(WidgetSnapshot.from(a, 1)))
        val fresh = signalWidgetUpdate(initial, observation(listOf(a.copy(periods = listOf("60"))), 5), 50)
        val old = signalWidgetUpdate(fresh.state, observation(listOf(a), 4), 60)
        assertEquals(fresh.state, old.state)
        assertFalse(old.applied)

        val deleted = signalWidgetUpdate(fresh.state, observation(emptyList(), 6), 70)
        val oldPresent = signalWidgetUpdate(deleted.state, observation(listOf(a), 5), 80)
        assertEquals(deleted.state, oldPresent.state)
        assertEquals("信号已不存在", oldPresent.status)
        assertEquals(50L, oldPresent.state.signals.single().updatedAtMillis)
    }

    @Test fun `a restarted process can publish even when a persisted sequence was larger`() {
        val a = SignalViewDto(id = "a")
        val previous = WidgetSnapshot.from(a, 1).copy(observationEpoch = "previous-process", observationRevision = 99)
        val result = signalWidgetUpdate(WidgetState(listOf(previous)), observation(listOf(a.copy(periods = listOf("60"))), 1), 10)
        assertEquals(listOf("60"), result.state.signals.single().periods)
    }

    private fun observation(signals: List<SignalViewDto>, revision: Long, complete: Boolean = true) =
        SignalObservation(signals, revision, "test", complete)
}
