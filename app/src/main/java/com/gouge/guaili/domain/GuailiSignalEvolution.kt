package com.gouge.guaili.domain

import com.gouge.guaili.data.CellAvailability
import com.gouge.guaili.data.parseGuailiTime
import com.gouge.guaili.data.dynamicSignalCellAvailability
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class GuailiSignalPhase {
    FirstObserved, Formed, Ongoing, RangeChanged, Narrowing, Widening,
    UpwardDeparture, DownwardDeparture, DivergenceWidening, DivergenceEasing,
    AlignedPositive, AlignedNegative, Ended,
}

/** Compare consecutive valid dynamic samples, including changes within one candle. */
object GuailiSignalEvolution {
    const val RuleVersion = "dynamic-structure-v1"
    // One displayed unit, used only for descriptive changes, not probability.
    private const val ChangeBand = 0.1

    fun evaluate(
        table: GuailiTable,
        nowMillis: Long,
        timezone: String?,
        previousTable: GuailiTable? = null,
        previousSignals: List<GuailiSignal> = emptyList(),
        previousAt: Long = 0,
    ): List<GuailiSignal> {
        val current = GuailiSignalDetector.candidates(table, nowMillis = nowMillis, timezone = timezone)
        if (previousTable == null) return current.map { it.copy(observedAt = nowMillis) }
        val old = previousSignals.filterNot { it.transitionOnly }.associateBy(::identity)
        val results = current.map { signal ->
            val prior = old[identity(signal)]?.takeUnless { it.transitionOnly }
            val intervals = (signal.intervals() + prior?.intervals().orEmpty()).distinct()
            when (compare(table, previousTable, signal.symbol, intervals, nowMillis, previousAt, timezone)) {
                Comparison.Unknown -> signal.copy(observedAt = nowMillis)
                Comparison.Unchanged -> if (prior != null && signal.runs == prior.runs) {
                    signal.copy(phase = prior.phase, observedAt = prior.observedAt)
                } else signal.copy(observedAt = nowMillis)
                Comparison.Advanced -> signal.copy(
                    phase = when {
                        prior == null -> GuailiSignalPhase.Formed
                        signal.runs.map { it.direction to it.intervals } != prior.runs.map { it.direction to it.intervals } ->
                            GuailiSignalPhase.RangeChanged
                        else -> changePhase(signal, table, previousTable)
                    },
                    observedAt = nowMillis,
                )
            }
        }.toMutableList()

        // An exit is a one-observation event. Re-fetching the same candles keeps
        // it visible; the next changed dynamic sample retires the event.
        previousSignals.forEach { prior ->
            when (compare(table, previousTable, prior.symbol, prior.intervals(), nowMillis, previousAt, timezone)) {
                Comparison.Unknown -> Unit // missing/old/corrected data cannot prove an exit
                Comparison.Unchanged -> if (prior.transitionOnly) results += prior
                Comparison.Advanced -> if (!prior.transitionOnly) {
                    val replacement = current.firstOrNull { identity(it) == identity(prior) }
                    val phase = exitPhase(prior, table)
                    val directionalExit = phase in setOf(GuailiSignalPhase.UpwardDeparture,
                        GuailiSignalPhase.DownwardDeparture, GuailiSignalPhase.AlignedPositive, GuailiSignalPhase.AlignedNegative)
                    if ((replacement == null && !stillMatches(prior, table)) || directionalExit) {
                        results += prior.copy(
                            phase = phase,
                            trend = signalTrend(signalCells(table, prior.symbol)[prior.anchorInterval]),
                            transitionOnly = true,
                            observedAt = nowMillis,
                        )
                    }
                }
            }
        }
        return results
    }

    /** Preserve coexisting near-mean structures; conflict subsumes its extremes. */
    fun visible(
        signals: List<GuailiSignal>,
        table: GuailiTable,
        symbols: List<String>,
        enabledKinds: Set<GuailiSignalKind>,
        nowMillis: Long,
        timezone: String?,
    ): List<GuailiSignal> {
        val eligible = signals.filter { signal ->
            signal.symbol in symbols && signal.kind in enabledKinds && signal.intervals().all {
                usable(signalCells(table, signal.symbol)[it], nowMillis, timezone) &&
                    (signal.transitionOnly || signalCells(table, signal.symbol)[it]?.rankFilter == true)
            }
        }
        return eligible.filter { signal ->
            signal.kind != GuailiSignalKind.Extreme || eligible.none {
                it.symbol == signal.symbol && it.kind == GuailiSignalKind.Conflict && !it.transitionOnly
            }
        }.sortedWith(compareByDescending<GuailiSignal> { it.priority }
            .thenBy { symbols.indexOf(it.symbol) })
    }

    private fun changePhase(signal: GuailiSignal, current: GuailiTable, previous: GuailiTable): GuailiSignalPhase {
        if (signal.kind == GuailiSignalKind.Compression) return GuailiSignalPhase.Ongoing
        val before = magnitude(signal, previous)
        val after = magnitude(signal, current)
        return when {
            after < before - ChangeBand -> if (signal.kind == GuailiSignalKind.Conflict) {
                GuailiSignalPhase.DivergenceEasing
            } else GuailiSignalPhase.Narrowing
            after > before + ChangeBand -> if (signal.kind == GuailiSignalKind.Conflict) {
                GuailiSignalPhase.DivergenceWidening
            } else GuailiSignalPhase.Widening
            else -> GuailiSignalPhase.Ongoing
        }
    }

    private fun exitPhase(signal: GuailiSignal, current: GuailiTable): GuailiSignalPhase {
        val cells = signalCells(current, signal.symbol)
        // A rank-filter failure is a known rule exit, not directional expansion.
        if (signal.intervals().any { cells[it]?.rankFilter != true }) return GuailiSignalPhase.Ended
        return when (signal.kind) {
            GuailiSignalKind.Compression -> {
                val shortest = signal.primaryRun.intervals.take(2).map { cells.getValue(it).value!! }
                when {
                    shortest.all { it > GuailiSignalDetector.CompressionBand } -> GuailiSignalPhase.UpwardDeparture
                    shortest.all { it < -GuailiSignalDetector.CompressionBand } -> GuailiSignalPhase.DownwardDeparture
                    else -> GuailiSignalPhase.Ended
                }
            }
            GuailiSignalKind.Conflict -> {
                val values = signal.intervals().map { cells.getValue(it).value!! }
                when {
                    values.all { it > GuailiSignalDetector.CompressionBand } -> GuailiSignalPhase.AlignedPositive
                    values.all { it < -GuailiSignalDetector.CompressionBand } -> GuailiSignalPhase.AlignedNegative
                    magnitude(signal, current) < magnitudeFromRuns(signal) - ChangeBand -> GuailiSignalPhase.DivergenceEasing
                    else -> GuailiSignalPhase.Ended
                }
            }
            GuailiSignalKind.Extreme -> {
                val sign = if (signal.primaryRun.direction == GuailiSignalDirection.Positive) 1 else -1
                val values = signal.intervals().map { signalGuaili(cells.getValue(it)) }
                if (values.all { it * sign >= 0 } && values.map(::abs).average() < magnitudeFromRuns(signal) - ChangeBand) {
                    GuailiSignalPhase.Narrowing
                } else GuailiSignalPhase.Ended
            }
        }
    }

    // Equal weight per run in conflicts avoids the longer run hiding the other side.
    private fun magnitude(signal: GuailiSignal, table: GuailiTable): Double {
        val cells = signalCells(table, signal.symbol)
        return signal.runs.map { run -> run.intervals.map { abs(signalGuaili(cells.getValue(it))) }.average() }.average()
    }

    private fun magnitudeFromRuns(signal: GuailiSignal): Double = signal.runs.map { it.meanAbsGuaili }.average()

    // Choosing a different winning run/pair is not proof the old one ended.
    private fun stillMatches(signal: GuailiSignal, table: GuailiTable): Boolean {
        val cells = signalCells(table, signal.symbol)
        return signal.runs.all { run -> run.intervals.all { interval ->
            val cell = cells.getValue(interval)
            cell.rankFilter == true && when (run.direction) {
                GuailiSignalDirection.Positive -> cell.value!! >= GuailiSignalDetector.ExtremeThreshold
                GuailiSignalDirection.Negative -> cell.value!! <= -GuailiSignalDetector.ExtremeThreshold
                GuailiSignalDirection.Neutral -> abs(cell.value!!) <= GuailiSignalDetector.CompressionBand
            }
        } }
    }

    private enum class Comparison { Unknown, Unchanged, Advanced }

    private fun compare(
        current: GuailiTable, previous: GuailiTable, symbol: String, intervals: List<String>,
        now: Long, previousAt: Long, timezone: String?,
    ): Comparison {
        if (previousAt <= 0 || now < previousAt) return Comparison.Unknown
        val currentCells = signalCells(current, symbol)
        val previousCells = signalCells(previous, symbol)
        var advanced = false
        for (interval in intervals) {
            val a = previousCells[interval]
            val b = currentCells[interval]
            if (!usable(a, previousAt, timezone) || !usable(b, now, timezone)) return Comparison.Unknown
            val earlier = parseGuailiTime(a?.closeTime, timezone) ?: return Comparison.Unknown
            val later = parseGuailiTime(b?.closeTime, timezone) ?: return Comparison.Unknown
            val delta = later - earlier
            if (delta < 0 || (delta > 0 && abs(delta - guailiIntervalDurationMillis(interval)) > 2_000L)) {
                return Comparison.Unknown
            }
            if (delta == 0L) {
                // Prices and filters can change while the same dynamic candle is forming.
                if (a != b) {
                    if (now == previousAt) return Comparison.Unknown
                    advanced = true
                }
            } else advanced = true
        }
        return if (advanced) Comparison.Advanced else Comparison.Unchanged
    }

    private fun usable(cell: GuailiCell?, now: Long, timezone: String?): Boolean =
        cell != null && dynamicSignalCellAvailability(cell, now, timezone) in setOf(CellAvailability.Ready, CellAvailability.Filtered) &&
            cell.signalAtrReady != false && signalGuaili(cell).isFinite() &&
            (cell.atr14 == null || (cell.atr14.isFinite() && cell.atr14 > 0.0))

    private fun identity(signal: GuailiSignal): String =
        "${signal.symbol}:${signal.kind}:${signal.runs.joinToString { it.direction.name }}"

    private fun GuailiSignal.intervals(): List<String> = runs.flatMap { it.intervals }
}
