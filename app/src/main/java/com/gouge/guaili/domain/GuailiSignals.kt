package com.gouge.guaili.domain

import kotlin.math.abs

enum class GuailiSignalKind {
    Extreme,
    Compression,
    Conflict,
}

enum class GuailiSignalDirection {
    Positive,
    Negative,
    Neutral,
}

data class GuailiSignalRun(
    val direction: GuailiSignalDirection,
    val intervals: List<String>,
    val minAbsValue: Int,
) {
    val startInterval: String get() = intervals.first()
    val endInterval: String get() = intervals.last()
    val levelCount: Int get() = intervals.size
}

data class GuailiSignal(
    val symbol: String,
    val kind: GuailiSignalKind,
    val runs: List<GuailiSignalRun>,
) {
    val primaryRun: GuailiSignalRun get() = runs.first()
    val anchorInterval: String get() = primaryRun.endInterval
    val totalLevelCount: Int get() = runs.sumOf(GuailiSignalRun::levelCount)
    val isStrong: Boolean get() = kind == GuailiSignalKind.Extreme && primaryRun.levelCount >= 6
    val isEvidenceBacked: Boolean
        get() {
            if (kind == GuailiSignalKind.Conflict) return false
            val anchorMillis = guailiIntervalDurationMillis(anchorInterval)
            return anchorMillis in (8L * 60_000L)..(60L * 60_000L)
        }

    internal val priority: Int
        get() = when (kind) {
            GuailiSignalKind.Conflict -> 400
            GuailiSignalKind.Extreme -> if (isStrong) 300 else 200
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
    ): List<GuailiSignal> {
        val orderedIntervals = table.intervals
            .distinct()
            .sortedBy(::guailiIntervalDurationMillis)

        return selectedSymbols.distinct().mapNotNull { symbol ->
            detectForSymbol(table, symbol, orderedIntervals, enabledKinds)
        }.sortedWith(
            compareByDescending<GuailiSignal> { it.priority }
                .thenBy { selectedSymbols.indexOf(it.symbol).let { index -> if (index < 0) Int.MAX_VALUE else index } },
        )
    }

    private fun detectForSymbol(
        table: GuailiTable,
        symbol: String,
        orderedIntervals: List<String>,
        enabledKinds: Set<GuailiSignalKind>,
    ): GuailiSignal? {
        val cells = table.closedCells[symbol]
            ?: table.cells[symbol].orEmpty().filterValues { it.isClosed == true }
        if (cells.isEmpty()) return null

        val extremeRuns = extremeRuns(orderedIntervals, cells)
        if (GuailiSignalKind.Conflict in enabledKinds) {
            conflictRuns(extremeRuns)?.let { runs ->
                return GuailiSignal(symbol, GuailiSignalKind.Conflict, runs)
            }
        }

        if (GuailiSignalKind.Extreme in enabledKinds) {
            extremeRuns.maxWithOrNull(runComparator)?.let { run ->
                return GuailiSignal(symbol, GuailiSignalKind.Extreme, listOf(run))
            }
        }

        if (GuailiSignalKind.Compression in enabledKinds) {
            compressionRuns(orderedIntervals, cells).maxWithOrNull(runComparator)?.let { run ->
                return GuailiSignal(symbol, GuailiSignalKind.Compression, listOf(run))
            }
        }

        return null
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
        cell.value != null && cell.isClosed == true && cell.rankFilter == true

    private val runComparator =
        compareBy<GuailiSignalRun> { it.levelCount }
            .thenBy { it.minAbsValue }
            .thenBy { guailiIntervalDurationMillis(it.endInterval) }
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
