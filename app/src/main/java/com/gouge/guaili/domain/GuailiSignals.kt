package com.gouge.guaili.domain

import kotlin.math.abs
import com.gouge.guaili.data.CellAvailability
import com.gouge.guaili.data.signalCellAvailability
import kotlinx.serialization.Serializable

@Serializable
enum class GuailiSignalKind {
    Extreme,
    Compression,
    Conflict,
}

@Serializable
enum class GuailiSignalDirection {
    Positive,
    Negative,
    Neutral,
}

@Serializable
data class GuailiSignalRun(
    val direction: GuailiSignalDirection,
    val intervals: List<String>,
    val minAbsValue: Int,
    val meanAbsGuaili: Double = minAbsValue / 10.0,
    val maxAbsGuaili: Double = minAbsValue / 10.0,
) {
    val startInterval: String get() = intervals.first()
    val endInterval: String get() = intervals.last()
    val levelCount: Int get() = intervals.size
}

@Serializable
data class GuailiSignal(
    val symbol: String,
    val kind: GuailiSignalKind,
    val runs: List<GuailiSignalRun>,
    val phase: GuailiSignalPhase = GuailiSignalPhase.FirstObserved,
    val trend: GuailiSignalTrend = GuailiSignalTrend.Unknown,
    val transitionOnly: Boolean = false,
    val observedAt: Long? = null,
) {
    val primaryRun: GuailiSignalRun get() = runs.first()
    val anchorInterval: String get() = runs.maxBy { guailiIntervalDurationMillis(it.endInterval) }.endInterval
    val totalLevelCount: Int get() = runs.sumOf(GuailiSignalRun::levelCount)
    // No versioned validation dataset is bundled. A timeframe alone is not evidence.
    val isEvidenceBacked: Boolean get() = false

    internal val priority: Int
        get() = when (kind) {
            GuailiSignalKind.Conflict -> 400
            GuailiSignalKind.Extreme -> 200
            GuailiSignalKind.Compression -> 100
        } + totalLevelCount
}

object GuailiSignalDetector {
    const val ExtremeThreshold = 10
    const val CompressionBand = 2
    const val MinimumRunLength = 5

    fun detect(
        table: GuailiTable,
        selectedSymbols: List<String> = table.symbols,
        enabledKinds: Set<GuailiSignalKind> = GuailiSignalKind.entries.toSet(),
        nowMillis: Long = System.currentTimeMillis(),
        timezone: String? = null,
    ): List<GuailiSignal> {
        return candidates(table, selectedSymbols, nowMillis, timezone)
            .filter { it.kind in enabledKinds }
            .groupBy { it.symbol }.values.map { signals -> signals.maxBy { it.priority } }.sortedWith(
            compareByDescending<GuailiSignal> { it.priority }
                .thenBy { selectedSymbols.indexOf(it.symbol).let { index -> if (index < 0) Int.MAX_VALUE else index } },
        )
    }

    fun candidates(
        table: GuailiTable,
        selectedSymbols: List<String> = table.symbols,
        nowMillis: Long = System.currentTimeMillis(),
        timezone: String? = null,
    ): List<GuailiSignal> {
        val intervals = table.intervals.distinct().sortedBy(::guailiIntervalDurationMillis)
        return selectedSymbols.distinct().flatMap { symbol ->
            val cells = signalCells(table, symbol).filterValues {
                signalCellAvailability(it, nowMillis, timezone) == CellAvailability.Ready && eligibleCell(it)
            }
            val extremes = extremeRuns(intervals, cells)
            buildList {
                conflictRuns(extremes)?.let { add(GuailiSignal(symbol, GuailiSignalKind.Conflict, it)) }
                extremes.groupBy { it.direction }.values.forEach { sameDirection ->
                    sameDirection.maxWithOrNull(runComparator)?.let {
                        add(GuailiSignal(symbol, GuailiSignalKind.Extreme, listOf(it)))
                    }
                }
                compressionRuns(intervals, cells).maxWithOrNull(compressionComparator)?.let {
                    add(GuailiSignal(symbol, GuailiSignalKind.Compression, listOf(it)))
                }
            }.map { it.copy(trend = signalTrend(cells[it.anchorInterval])) }
        }
    }

    private fun extremeRuns(
        intervals: List<String>,
        cells: Map<String, GuailiCell>,
    ): List<GuailiSignalRun> = buildRuns(intervals) { interval ->
        val cell = cells[interval]?.takeIf(::eligibleCell) ?: return@buildRuns null
        when {
            (cell.value ?: 0) >= ExtremeThreshold -> GuailiSignalDirection.Positive
            (cell.value ?: 0) <= -ExtremeThreshold -> GuailiSignalDirection.Negative
            else -> null
        }
    }.map { (direction, runIntervals) ->
        GuailiSignalRun(
            direction = direction,
            intervals = runIntervals,
            minAbsValue = runIntervals.minOf { abs(cells.getValue(it).value ?: 0) },
            meanAbsGuaili = runIntervals.map { abs(signalGuaili(cells.getValue(it))) }.average(),
            maxAbsGuaili = runIntervals.maxOf { abs(signalGuaili(cells.getValue(it))) },
        )
    }

    private fun compressionRuns(
        intervals: List<String>,
        cells: Map<String, GuailiCell>,
    ): List<GuailiSignalRun> = buildRuns(intervals) { interval ->
        val cell = cells[interval]?.takeIf(::eligibleCell) ?: return@buildRuns null
        if (abs(cell.value ?: Int.MAX_VALUE) <= CompressionBand) {
            GuailiSignalDirection.Neutral
        } else {
            null
        }
    }.map { (_, runIntervals) ->
        GuailiSignalRun(
            direction = GuailiSignalDirection.Neutral,
            intervals = runIntervals,
            minAbsValue = runIntervals.minOf { abs(cells.getValue(it).value ?: 0) },
            meanAbsGuaili = runIntervals.map { abs(signalGuaili(cells.getValue(it))) }.average(),
            maxAbsGuaili = runIntervals.maxOf { abs(signalGuaili(cells.getValue(it))) },
        )
    }

    private fun buildRuns(
        intervals: List<String>,
        directionAt: (String) -> GuailiSignalDirection?,
    ): List<Pair<GuailiSignalDirection, List<String>>> {
        val result = mutableListOf<Pair<GuailiSignalDirection, List<String>>>()
        var currentDirection: GuailiSignalDirection? = null
        var currentIntervals = mutableListOf<String>()

        fun finishRun() {
            val direction = currentDirection
            if (direction != null && currentIntervals.size >= MinimumRunLength) {
                result += direction to currentIntervals.toList()
            }
            currentDirection = null
            currentIntervals = mutableListOf()
        }

        intervals.forEach { interval ->
            val direction = directionAt(interval)
            if (direction == null || direction != currentDirection) {
                finishRun()
                if (direction != null) {
                    currentDirection = direction
                    currentIntervals += interval
                }
            } else {
                currentIntervals += interval
            }
        }
        finishRun()
        return result
    }

    private fun conflictRuns(runs: List<GuailiSignalRun>): List<GuailiSignalRun>? {
        val candidates = runs.flatMapIndexed { firstIndex, first ->
            runs.drop(firstIndex + 1).mapNotNull { second ->
                if (first.direction == second.direction) null else listOf(first, second)
            }
        }
        return candidates.maxWithOrNull(
            compareBy<List<GuailiSignalRun>> { pair -> pair.sumOf(GuailiSignalRun::levelCount) }
                .thenBy { pair -> guailiIntervalDurationMillis(pair.last().endInterval) },
        )
    }

    private fun eligibleCell(cell: GuailiCell): Boolean =
        cell.value != null && cell.isClosed == true && cell.rankFilter == true &&
            cell.signalAtrReady != false && (cell.guaili == null || cell.guaili.isFinite()) &&
            (cell.atr14 == null || (cell.atr14.isFinite() && cell.atr14 > 0.0))

    private val runComparator =
        compareBy<GuailiSignalRun> { it.levelCount }
            .thenBy { it.minAbsValue }
            .thenBy { guailiIntervalDurationMillis(it.endInterval) }

    private val compressionComparator =
        compareBy<GuailiSignalRun> { it.levelCount }
            .thenByDescending { it.maxAbsGuaili }
            .thenByDescending { it.meanAbsGuaili }
            .thenBy { guailiIntervalDurationMillis(it.endInterval) }
}

internal fun signalCells(table: GuailiTable, symbol: String): Map<String, GuailiCell> =
    table.closedCells[symbol] ?: table.cells[symbol].orEmpty().filterValues { it.isClosed == true }

internal fun signalGuaili(cell: GuailiCell): Double = cell.guaili ?: (cell.value ?: 0) / 10.0

@Serializable
enum class GuailiSignalTrend { Up, Down, Flat, Unknown }

internal fun signalTrend(cell: GuailiCell?): GuailiSignalTrend = when {
    cell?.signalLongTrend == null || cell.signalShortTrend == null -> GuailiSignalTrend.Unknown
    cell.signalLongTrend && !cell.signalShortTrend -> GuailiSignalTrend.Up
    cell.signalShortTrend && !cell.signalLongTrend -> GuailiSignalTrend.Down
    !cell.signalLongTrend && !cell.signalShortTrend -> GuailiSignalTrend.Flat
    else -> GuailiSignalTrend.Unknown
}

internal fun guailiIntervalDurationMillis(interval: String): Long {
    val normalized = interval.trim().uppercase()
    val suffix = normalized.lastOrNull()
    val multiplier = when (suffix) {
        'S' -> 1_000L
        'D' -> 86_400_000L
        'W' -> 7L * 86_400_000L
        else -> 60_000L
    }
    val amount = if (suffix in setOf('S', 'D', 'W')) {
        normalized.dropLast(1).ifEmpty { "1" }.toLongOrNull()
    } else {
        normalized.toLongOrNull()
    }
    return amount?.times(multiplier) ?: Long.MAX_VALUE
}
