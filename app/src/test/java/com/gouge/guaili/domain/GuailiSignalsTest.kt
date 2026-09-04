package com.gouge.guaili.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuailiSignalsTest {
    @Test
    fun detectsFiveLevelPositiveExtremeAsPullbackRisk() {
        val table = table(
            values = mapOf("1" to 12, "2" to 11, "3" to 14, "5" to 10, "8" to 16),
            intervalOrder = listOf("8", "5", "3", "2", "1"),
        )

        val signal = GuailiSignalDetector.detect(table).single()

        assertEquals(GuailiSignalKind.Extreme, signal.kind)
        assertEquals(GuailiSignalDirection.Positive, signal.primaryRun.direction)
        assertEquals(listOf("1", "2", "3", "5", "8"), signal.primaryRun.intervals)
        assertEquals("8", signal.anchorInterval)
        assertFalse(signal.isStrong)
        assertTrue(signal.isEvidenceBacked)
    }

    @Test
    fun sixLevelNegativeExtremeIsStrong() {
        val table = table(
            values = mapOf("1" to -11, "2" to -12, "3" to -14, "5" to -10, "8" to -18, "10" to -13),
        )

        val signal = GuailiSignalDetector.detect(table).single()

        assertEquals(GuailiSignalDirection.Negative, signal.primaryRun.direction)
        assertEquals(6, signal.primaryRun.levelCount)
        assertTrue(signal.isStrong)
        assertTrue(signal.isEvidenceBacked)
    }

    @Test
    fun detectsNearZeroCompression() {
        val table = table(
            values = mapOf("15" to -2, "20" to 0, "30" to 1, "45" to 0, "60" to 2),
        )

        val signal = GuailiSignalDetector.detect(table).single()

        assertEquals(GuailiSignalKind.Compression, signal.kind)
        assertEquals("15", signal.primaryRun.startInterval)
        assertEquals("60", signal.anchorInterval)
    }

    @Test
    fun oppositeExtremeRunsBecomeOneConflictSignal() {
        val values = linkedMapOf(
            "1" to -12, "2" to -14, "3" to -11, "5" to -13, "8" to -10,
            "10" to 0,
            "15" to 11, "20" to 12, "30" to 15, "45" to 13, "60" to 10,
        )

        val signal = GuailiSignalDetector.detect(table(values)).single()

        assertEquals(GuailiSignalKind.Conflict, signal.kind)
        assertEquals(2, signal.runs.size)
        assertEquals(GuailiSignalDirection.Negative, signal.runs[0].direction)
        assertEquals(GuailiSignalDirection.Positive, signal.runs[1].direction)
        assertEquals("8", signal.anchorInterval)
        assertFalse(signal.isEvidenceBacked)
    }

    @Test
    fun disabledConflictFallsBackToEnabledExtremeSignal() {
        val values = linkedMapOf(
            "1" to -12, "2" to -14, "3" to -11, "5" to -13, "8" to -10,
            "10" to 0,
            "15" to 11, "20" to 12, "30" to 15, "45" to 13, "60" to 10,
        )

        val signal = GuailiSignalDetector.detect(
            table = table(values),
            enabledKinds = setOf(GuailiSignalKind.Extreme),
        ).single()

        assertEquals(GuailiSignalKind.Extreme, signal.kind)
    }

    @Test
    fun noSignalKindsEnabledProducesNoSignals() {
        val table = table(
            values = mapOf("15" to -2, "20" to 0, "30" to 1, "45" to 0, "60" to 2),
        )

        assertTrue(GuailiSignalDetector.detect(table, enabledKinds = emptySet()).isEmpty())
    }

    @Test
    fun missingOrFilteredLevelBreaksAdjacency() {
        val values = mapOf("1" to 12, "2" to 13, "3" to 14, "5" to 15, "8" to 16)
        val cells = values.mapValues { (interval, value) -> cell(interval, value) }.toMutableMap()
        cells["3"] = cell("3", 14).copy(rankFilter = false)
        val table = GuailiTable(
            symbols = listOf(Symbol),
            intervals = values.keys.toList(),
            cells = emptyMap(),
            closedCells = mapOf(Symbol to cells),
        )

        assertTrue(GuailiSignalDetector.detect(table).isEmpty())
    }

    @Test
    fun intervalDurationSortsWeekBeforeTenDays() {
        assertTrue(
            guailiIntervalDurationMillis("4D") < guailiIntervalDurationMillis("W"),
        )
        assertTrue(
            guailiIntervalDurationMillis("W") < guailiIntervalDurationMillis("10D"),
        )
    }

    private fun table(
        values: Map<String, Int>,
        intervalOrder: List<String> = values.keys.toList(),
    ): GuailiTable = GuailiTable(
        symbols = listOf(Symbol),
        intervals = intervalOrder,
        cells = emptyMap(),
        closedCells = mapOf(
            Symbol to values.mapValues { (interval, value) -> cell(interval, value) },
        ),
    )

    private fun cell(interval: String, value: Int) = GuailiCell(
        symbol = Symbol,
        interval = interval,
        value = value,
        guaili = value / 10.0,
        ma = 100.0,
        atr14 = 1.0,
        atrRank = 50.0,
        rankFilter = true,
        longTrend = false,
        shortTrend = false,
        isClosed = true,
        openTime = null,
        closeTime = null,
    )

    companion object {
        private const val Symbol = "BTCUSDT"
    }
}
