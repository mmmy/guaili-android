package com.gouge.guaili.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GuailiSignalEvolutionTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli() + 1_000
    private val before = now - 2_000
    private val symbol = "BTCUSDT"
    private val periods = listOf("1", "2", "3", "5", "8")

    @Test fun firstObservationDoesNotInventAFormationTimeAndDuplicateRefreshDoesNotAdvance() {
        val table = table(periods.associateWith { 12 }, now)
        val first = evaluate(table, now)
        assertEquals(GuailiSignalPhase.FirstObserved, first.single().phase)
        val again = evaluate(table, now + 5_000, table, first, now)
        assertEquals(first, again)
    }

    @Test fun freshConsecutiveCandlesCanFormAndNarrowADeviationStructure() {
        val old = table(periods.associateWith { 9 }, before)
        val fresh = table(periods.associateWith { 14 }, now)
        assertEquals(GuailiSignalPhase.Formed, evaluate(fresh, now, old, emptyList(), before).single().phase)
        val extreme = table(periods.associateWith { 17 }, before)
        val narrowed = evaluate(fresh, now, extreme, evaluate(extreme, before), before).single()
        assertEquals(GuailiSignalPhase.Narrowing, narrowed.phase)
        assertFalse(narrowed.transitionOnly)
    }

    @Test fun structureExitStillShowsNarrowingWithOriginalRange() {
        val old = table(periods.associateWith { 12 }, before)
        val fresh = table(periods.associateWith { 7 }, now)
        val change = evaluate(fresh, now, old, evaluate(old, before), before).single()
        assertTrue(change.transitionOnly)
        assertEquals(GuailiSignalPhase.Narrowing, change.phase)
        assertEquals(periods, change.primaryRun.intervals)
    }

    @Test fun nearMeanRequiresTwoShortestPeriodsToLeaveInSameDirection() {
        val old = table(periods.associateWith { 0 }, before)
        val prior = evaluate(old, before)
        for ((values, expected) in listOf(
            listOf(3, 4, 0, 0, 0) to GuailiSignalPhase.UpwardDeparture,
            listOf(-3, -4, 0, 0, 0) to GuailiSignalPhase.DownwardDeparture,
            listOf(3, -4, 0, 0, 0) to GuailiSignalPhase.Ended,
            listOf(3, 0, 0, 0, 0) to GuailiSignalPhase.Ended,
        )) {
            val fresh = table(periods.zip(values).toMap(), now)
            val event = evaluate(fresh, now, old, prior, before).single()
            assertEquals(expected, event.phase)
            assertTrue(event.transitionOnly)
            assertEquals(listOf(event), evaluate(fresh, now + 5_000, fresh, listOf(event), now))
            val next = table(periods.zip(values).toMap(), now + 60_000)
            assertTrue(evaluate(next, now + 60_000, fresh, listOf(event), now).isEmpty())
        }
    }

    @Test fun nearMeanDepartureIsNotHiddenByRemainingLongerNearMeanRun() {
        val six = periods + "10" + "15"
        val old = table(six.associateWith { 0 }, before)
        val fresh = table(six.associateWith { if (it in listOf("1", "2")) 4 else 0 }, now)
        // A shrinking but still valid run needs both a current state and an exit
        // event describing the original short end.
        val result = evaluate(fresh, now, old, evaluate(old, before), before)
        assertTrue(result.any { it.phase == GuailiSignalPhase.UpwardDeparture })
        assertTrue(result.any { !it.transitionOnly && it.primaryRun.startInterval == "3" })
    }

    @Test fun conflictDirectionAndMagnitudeChangesAreExplicit() {
        val long = listOf("15", "20", "30", "45", "60")
        val values = periods.associateWith { -15 } + ("10" to 5) + long.associateWith { 15 }
        val old = table(values, before)
        val prior = evaluate(old, before)
        for ((shortValue, longValue, expected) in listOf(
            Triple(-18, 18, GuailiSignalPhase.DivergenceWidening),
            Triple(-11, 11, GuailiSignalPhase.DivergenceEasing),
            Triple(5, 15, GuailiSignalPhase.AlignedPositive),
            Triple(-15, -5, GuailiSignalPhase.AlignedNegative),
        )) {
            val fresh = table(periods.associateWith { shortValue } + ("10" to 5) + long.associateWith { longValue }, now)
            val result = evaluate(fresh, now, old, prior, before).single { it.kind == GuailiSignalKind.Conflict }
            assertEquals(expected, result.phase)
        }
    }

    @Test fun changingWinningConflictPairDoesNotInventAnExitOfStillValidPair() {
        val middle = listOf("15", "20", "30", "45", "60")
        val end = listOf("120", "180", "240", "360", "480")
        val values = periods.associateWith { 12 } + ("10" to 5) + middle.associateWith { -12 } +
            ("90" to 5) + end.associateWith { 9 }
        val old = table(values, before)
        val fresh = table(values + end.associateWith { 12 }, now)
        val result = evaluate(fresh, now, old, evaluate(old, before), before)
        assertTrue(result.none { it.kind == GuailiSignalKind.Conflict && it.transitionOnly })
        assertEquals(2, result.count { it.kind == GuailiSignalKind.Extreme && !it.transitionOnly })
    }

    @Test fun ongoingNearMeanStateAndDepartureRemainDistinctOnDuplicateRefresh() {
        val seven = periods + "10" + "15"
        val old = table(seven.associateWith { 0 }, before)
        val fresh = table(seven.associateWith { if (it in listOf("1", "2")) 4 else 0 }, now)
        val result = evaluate(fresh, now, old, evaluate(old, before), before)
        assertEquals(2, result.size)
        assertEquals(result, evaluate(fresh, now + 5_000, fresh, result, now))
    }

    @Test fun missingFilteredAndStaleHaveDifferentMeanings() {
        val old = table(periods.associateWith { 12 }, before)
        val prior = evaluate(old, before)
        val fresh = table(periods.associateWith { 12 }, now)
        val cells = fresh.cells.getValue(symbol)
        val missing = fresh.copy(cells = mapOf(symbol to cells.minus("3")))
        assertTrue(evaluate(missing, now, old, prior, before).isEmpty())
        val filtered = fresh.copy(cells = mapOf(symbol to (cells + ("3" to cells.getValue("3").copy(rankFilter = false)))))
        assertEquals(GuailiSignalPhase.Ended, evaluate(filtered, now, old, prior, before).single().phase)
        val stale = fresh.copy(cells = mapOf(symbol to (cells + ("3" to cells.getValue("3").copy(
            closeTime = Instant.ofEpochMilli(now - 3_600_000).toString())))))
        assertTrue(evaluate(stale, now, old, prior, before).isEmpty())
        assertEquals(GuailiSignalPhase.FirstObserved, evaluate(fresh, now, missing, emptyList(), now - 1).single().phase)
    }

    @Test fun skippedCandlesResetAndSameCandleDynamicChangesCanWiden() {
        val old = table(periods.associateWith { 12 }, before)
        val fresh = table(periods.associateWith { 14 }, now + 120_000)
        assertEquals(GuailiSignalPhase.FirstObserved,
            evaluate(fresh, now + 120_000, old, evaluate(old, before), before).single().phase)
        val corrected = table(periods.associateWith { 16 }, before)
        assertEquals(GuailiSignalPhase.Widening,
            evaluate(corrected, before + 100, old, evaluate(old, before), before).single().phase)
    }

    @Test fun rangeMigrationIsNotAnotherFormationAndSameCandleRefreshIsStable() {
        val six = periods + "10"
        val old = table(six.associateWith { if (it == "10") 8 else 12 }, before)
        val fresh = table(six.associateWith { 12 }, now)
        val result = evaluate(fresh, now, old, evaluate(old, before), before)
        assertEquals(GuailiSignalPhase.RangeChanged, result.single().phase)
        assertEquals(result, evaluate(fresh, now + 1_000, fresh, result, now))
    }

    @Test fun independentNearMeanStructureRemainsVisibleBesideConflict() {
        val values = periods.associateWith { -12 } + ("10" to 5) +
            listOf("15", "20", "30", "45", "60").associateWith { 12 } + ("90" to 5) +
            listOf("120", "180", "240", "360", "480").associateWith { 0 }
        val table = table(values, now)
        val candidates = evaluate(table, now)
        fun visible(kinds: Set<GuailiSignalKind>) = GuailiSignalEvolution.visible(candidates, table,
            listOf(symbol), kinds, now, "UTC")
        assertEquals(setOf(GuailiSignalKind.Conflict, GuailiSignalKind.Compression),
            visible(GuailiSignalKind.entries.toSet()).map { it.kind }.toSet())
        assertEquals(setOf(GuailiSignalKind.Extreme, GuailiSignalKind.Compression),
            visible(setOf(GuailiSignalKind.Extreme, GuailiSignalKind.Compression)).map { it.kind }.toSet())
        assertTrue(visible(emptySet()).isEmpty())
        assertTrue(GuailiSignalEvolution.visible(candidates, table, listOf(symbol),
            GuailiSignalKind.entries.toSet(), now + 2 * 86_400_000, "UTC").isEmpty())
    }

    private fun evaluate(table: GuailiTable, time: Long, old: GuailiTable? = null,
        signals: List<GuailiSignal> = emptyList(), oldTime: Long = 0): List<GuailiSignal> =
        GuailiSignalEvolution.evaluate(table, time, "UTC", old, signals, oldTime)

    private fun table(values: Map<String, Int>, time: Long): GuailiTable = GuailiTable(
        symbols = listOf(symbol), intervals = values.keys.toList(), cells = mapOf(symbol to values.mapValues { (interval, value) ->
            val duration = guailiIntervalDurationMillis(interval)
            GuailiCell(symbol, interval, value, value / 10.0, 100.0, 1.0, 50.0, true,
                false, false, false, Instant.ofEpochMilli(time / duration * duration).toString(),
                Instant.ofEpochMilli((time / duration + 1) * duration - 1).toString(),
                signalLongTrend = true, signalShortTrend = false, signalAtrReady = true)
        }),
    )
}
