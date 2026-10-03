package com.gouge.guaili.domain

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.widget.widgetDataStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuailiDynamicSignalsTest {
    private val now = Instant.parse("2026-10-03T05:01:10Z").toEpochMilli()
    private val symbol = "BTCUSDT"
    private val periods = listOf("1", "2", "3", "5", "8")

    private fun cell(interval: String, value: Int): GuailiCell {
        val duration = guailiIntervalDurationMillis(interval)
        val start = now / duration * duration
        return GuailiCell(symbol, interval, value, value / 10.0, 100.0, 1.0, 50.0,
            true, false, false, false, Instant.ofEpochMilli(start).toString(),
            Instant.ofEpochMilli(start + duration - 1).toString(),
            signalLongTrend = true, signalShortTrend = false, signalAtrReady = true)
    }

    private fun table(value: Int): GuailiTable = GuailiTable(listOf(symbol), periods,
        mapOf(symbol to periods.associateWith { cell(it, value) }),
        closedCells = mapOf(symbol to periods.associateWith { interval ->
            val live = cell(interval, 0)
            val duration = guailiIntervalDurationMillis(interval)
            live.copy(isClosed = true,
                openTime = Instant.ofEpochMilli(parseGuailiTime(live.openTime)!! - duration).toString(),
                closeTime = Instant.ofEpochMilli(parseGuailiTime(live.closeTime)!! - duration).toString())
        }))

    @Test fun detectorUsesLiveValuesAndSameCandleTrendInsteadOfClosedValues() {
        val signal = GuailiSignalDetector.candidates(table(12), nowMillis = now).single()
        assertEquals(GuailiSignalKind.Extreme, signal.kind)
        assertEquals(GuailiSignalTrend.Up, signal.trend)
        assertEquals(periods, signal.primaryRun.intervals)
    }

    @Test fun missingOrExpiredDynamicCandleNeverFallsBackToClosedSignals() {
        val current = table(12)
        assertTrue(GuailiSignalDetector.candidates(current.copy(cells = emptyMap()), nowMillis = now).isEmpty())
        val live = current.cells.getValue(symbol)
        for (invalid in listOf(live.getValue("3").copy(isClosed = true),
            cell("3", 12).copy(openTime = null),
            cell("3", 12).let { it.copy(
                openTime = Instant.ofEpochMilli(parseGuailiTime(it.openTime)!! - 180_000L).toString(),
                closeTime = Instant.ofEpochMilli(parseGuailiTime(it.closeTime)!! - 180_000L).toString()) })) {
            assertTrue(GuailiSignalDetector.candidates(current.copy(cells =
                mapOf(symbol to (live + ("3" to invalid)))), nowMillis = now).isEmpty())
        }
    }

    @Test fun futureCloseIsNormalButFutureOpenAndExpiredCandleAreUnavailable() {
        val live = cell("1", 12)
        assertEquals(CellAvailability.Ready, dynamicSignalCellAvailability(live, now))
        assertEquals(CellAvailability.Stale, dynamicSignalCellAvailability(live,
            parseGuailiTime(live.closeTime)!! + 1))
        assertEquals(CellAvailability.Future, dynamicSignalCellAvailability(live.copy(
            openTime = Instant.ofEpochMilli(now + 60_000).toString(),
            closeTime = Instant.ofEpochMilli(now + 119_999).toString()), now))
        assertEquals(CellAvailability.Filtered, dynamicSignalCellAvailability(live.copy(rankFilter = false), now))
    }

    @Test fun sameDynamicCandleCanFormNarrowAndLeaveWithoutWaitingForClose() {
        val old = table(9)
        val formed = table(16)
        val signal = GuailiSignalEvolution.evaluate(formed, now + 5_000, null, old,
            emptyList(), now).single()
        assertEquals(GuailiSignalPhase.Formed, signal.phase)
        val narrowed = table(12)
        val next = GuailiSignalEvolution.evaluate(narrowed, now + 10_000, null,
            formed, listOf(signal), now + 5_000).single()
        assertEquals(GuailiSignalPhase.Narrowing, next.phase)
        assertEquals(listOf(next), GuailiSignalEvolution.evaluate(narrowed, now + 15_000,
            null, narrowed, listOf(next), now + 10_000))
        val exit = GuailiSignalEvolution.evaluate(table(6), now + 20_000, null,
            narrowed, listOf(next), now + 15_000).single()
        assertTrue(exit.transitionOnly)
    }

    @Test fun widgetQualityUsesDynamicCandlesWhenOriginalSignalModeRequestsIt() {
        val snapshot = GuailiSnapshot(table(12), now)
        val status = widgetDataStatus(snapshot, listOf(symbol), now, dynamicSignals = true)
        assertEquals(5, status.ready)
        assertEquals(0, status.future)
        assertFalse(status.incomplete)
        val missing = widgetDataStatus(snapshot.copy(table = snapshot.table.copy(cells = emptyMap())),
            listOf(symbol), now, dynamicSignals = true)
        assertEquals(5, missing.missing)
        assertEquals(0, missing.ready)
    }

    @Test fun closedMatrixPreferenceStillFetchesLiveAndComputesDynamicSignals() = runTest {
        val settings = GuailiSettings.defaults().copy(symbols = listOf(symbol),
            intervals = periods, closedOnly = true)
        var requested: GuailiSettings? = null
        val response = GuailiResponse(listOf(symbol), periods, 3, 500, false, results =
            listOf(GuailiSymbolResult(symbol, periods.map { interval ->
                val live = cell(interval, 12)
                val closed = table(12).closedCells.getValue(symbol).getValue(interval)
                fun point(c: GuailiCell) = GuailiPoint(c.openTime, c.closeTime, c.ma, c.atr14,
                    c.atrRank, c.rankFilter, c.guaili, c.value, c.signalLongTrend, c.signalShortTrend, c.isClosed)
                GuailiSeries(interval, latest = point(live), data = listOf(point(closed), point(live)))
            })))
        val useCase = GuailiRefreshUseCase(fetcherFactory = GuailiFetcherFactory {
            GuailiFetcher { actual -> requested = actual; GuailiResult.Success(response) }
        }, nowMillis = { now })
        val snapshot = (useCase.refresh(settings) as GuailiResult.Success).value
        assertFalse(requested!!.closedOnly)
        assertEquals(true, snapshot.table.cells.getValue(symbol).getValue("1").isClosed)
        assertEquals(0, snapshot.table.cells.getValue(symbol).getValue("1").value)
        assertEquals(false, snapshot.table.dynamicCells!!.getValue(symbol).getValue("1").isClosed)
        assertEquals(GuailiSignalKind.Extreme, snapshot.signals.single().kind)
        assertEquals("dynamic-structure-v1", snapshot.signalRuleVersion)
    }
}
