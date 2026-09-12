package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.*
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WidgetBusinessRulesTest {
    @Test fun matrixFreshnessOnlyUsesConfiguredIntervals() {
        val data = snapshot(intervals.associateWith { cell(it, if (it == "1") 600_000L else 30_000L) })
        assertTrue(widgetDataStatus(data, listOf("BTCUSDT"), now).incomplete)
        val visible = widgetDataStatus(data, listOf("BTCUSDT"), now, listOf("5", "8"))
        assertFalse(visible.incomplete)
        assertEquals(2, visible.expectedCells)
    }

    @Test fun emptyPeriodSetIsUnknownInsteadOfComplete() {
        val status = widgetDataStatus(snapshot(), listOf("BTCUSDT"), now, emptyList())
        assertTrue(status.incomplete)
        assertEquals("没有可判断的品种或周期", status.description)
    }

    @Test fun narrowMatrixWrapsAllIntervalsWithoutDroppingAny() {
        val configured = listOf("5", "15", "60", "D")
        assertEquals(listOf(listOf("5", "15"), listOf("60", "D")), matrixIntervalGroups(configured, 180))
        assertEquals(listOf(configured), matrixIntervalGroups(configured, 320))
    }
    private val now = Instant.parse("2026-09-05T02:01:00Z").toEpochMilli()
    private val intervals = listOf("1", "2", "3", "5", "8")
    private fun cell(interval: String = "1", age: Long = 30_000L) = GuailiCell(
        "BTCUSDT", interval, 12, 1.2, 100.0, 1.0, 50.0, true,
        false, false, true, null, Instant.ofEpochMilli(now - age).toString(),
    )
    private fun snapshot(cells: Map<String, GuailiCell> = intervals.associateWith { cell(it) }) = GuailiSnapshot(
        GuailiTable(listOf("BTCUSDT"), intervals, mapOf("BTCUSDT" to cells), mapOf("BTCUSDT" to cells)), now,
    )

    @Test fun offsetTimesAndDeclaredLocalTimesRepresentSameInstant() {
        assertEquals(now, parseGuailiTime("2026-09-05T10:01:00+08:00"))
        assertEquals(now, parseGuailiTime("2026-09-05 10:01:00", "Asia/Shanghai"))
        assertNull(parseGuailiTime("2026-09-05T10:01:00"))
        assertNull(parseGuailiTime("bad timestamp", "invalid-zone"))
    }

    @Test fun staleLevelBreaksExtremeRunEvenWhenRequestJustSucceeded() {
        val stale = snapshot(intervals.associateWith { if (it == "3") cell(it, 600_000L) else cell(it) })
        assertTrue(GuailiSignalDetector.detect(stale.table, nowMillis = now).isEmpty())
        assertTrue(widgetDataStatus(stale, listOf("BTCUSDT"), now).incomplete)
        assertEquals(1, widgetDataStatus(stale, listOf("BTCUSDT"), now).stale)
    }

    @Test fun unknownTimeIsNotAssumedFresh() {
        val unknown = snapshot(intervals.associateWith { cell(it).copy(closeTime = null) })
        assertTrue(GuailiSignalDetector.detect(unknown.table, nowMillis = now).isEmpty())
        assertEquals(5, widgetDataStatus(unknown, listOf("BTCUSDT"), now).missing)
    }

    @Test fun dynamicallyMissingBackendPeriodsBreakSignalsWithoutBeingReportedAsStale() {
        val cases = listOf(
            listOf("1", "2", "3", "5", "8", "15", "30", "60") to setOf("2", "3", "5"),
            listOf("1", "8", "15", "60", "240", "720") to setOf("60"),
        )
        cases.forEach { (requested, missing) ->
            val point = GuailiPoint(value = 12, rankFilter = true, isClosed = true,
                closeTime = Instant.ofEpochMilli(now - 30_000).toString())
            val response = GuailiResponse(
                symbols = listOf("BTCUSDT", "XAUUSDT"), intervals = requested,
                limit = 2, calcLimit = 500, closedOnly = true,
                results = listOf(
                    GuailiSymbolResult("BTCUSDT", requested.filterNot(missing::contains).map {
                        GuailiSeries(interval = it, latest = point, data = listOf(point))
                    }),
                    GuailiSymbolResult("XAUUSDT", requested.map {
                        GuailiSeries(interval = it, latest = point, data = listOf(point))
                    }),
                ),
            )
            val table = response.toTable(response.symbols, requested)
            val data = GuailiSnapshot(table, now)
            val status = widgetDataStatus(data, listOf("BTCUSDT"), now)
            assertEquals(0, status.missing)
            assertEquals(missing.size, status.unavailable)
            assertEquals(requested.size - missing.size, status.expectedCells)
            assertEquals(0, status.stale)
            assertNull(status.warning)
            assertFalse(status.incomplete)
            assertTrue(GuailiSignalDetector.detect(table, listOf("BTCUSDT"), nowMillis = now).isEmpty())
            assertTrue(GuailiSignalDetector.detect(table, listOf("XAUUSDT"), nowMillis = now).isNotEmpty())
            assertNull(widgetDataStatus(data, listOf("XAUUSDT"), now).warning)
        }
    }

    @Test fun continuousUpperPeriodsStillProduceSignalsWithDynamicFiveOrEightMinuteStart() {
        val requested = listOf("1", "2", "3", "5", "8", "15", "30", "60", "120")
        listOf("5", "8").forEach { firstAvailable ->
            val upperPeriods = requested.dropWhile { it != firstAvailable }
            // The backend may keep 1m even though other periods below its threshold are absent.
            val returned = (listOf("1") + upperPeriods).associateWith { cell(it) }
            val table = GuailiTable(listOf("BTCUSDT"), requested,
                mapOf("BTCUSDT" to returned), mapOf("BTCUSDT" to returned))
            val signal = GuailiSignalDetector.detect(table, nowMillis = now).single()
            assertEquals(upperPeriods, signal.primaryRun.intervals)
            assertEquals(firstAvailable, signal.primaryRun.startInterval)
            assertFalse(signal.primaryRun.intervals.contains("1"))
        }
    }

    @Test fun lastClosedDailyCandleCanBeHoursOldWithoutBeingStale() {
        assertEquals(CellAvailability.Ready, signalCellAvailability(cell("D", 12 * 60 * 60_000L), now))
        assertEquals(CellAvailability.Stale, signalCellAvailability(cell("1", 12 * 60 * 60_000L), now))
    }

    @Test fun futureTimestampAndUnsupportedPeriodAreRejected() {
        assertEquals(CellAvailability.Stale, signalCellAvailability(cell(age = -60_000L), now))
        assertEquals(CellAvailability.UnknownTime, signalCellAvailability(cell("nonsense"), now))
    }

    @Test fun missingDataAndCompleteQuietMarketHaveDifferentStatus() {
        assertTrue(widgetDataStatus(snapshot(emptyMap()), listOf("BTCUSDT"), now).incomplete)
        val quiet = snapshot(intervals.associateWith { cell(it).copy(value = 5) })
        assertFalse(widgetDataStatus(quiet, listOf("BTCUSDT"), now).incomplete)
        assertTrue(GuailiSignalDetector.detect(quiet.table, nowMillis = now).isEmpty())
    }

    @Test fun wholeSnapshotExpiryIsIndependentOfCandleAge() {
        val data = snapshot().copy(updatedAt = now - GUAILI_STALE_AFTER_MILLIS)
        assertTrue(widgetDataStatus(data, listOf("BTCUSDT"), now).snapshotStale)
    }

    @Test fun refreshResultRemainsVisibleAndNewSnapshotSupersedesFailure() {
        val success = WidgetRefreshStatus(WidgetRefreshPhase.Success, now)
        assertEquals("刷新成功", refreshFeedbackText(success, now + 1))
        assertEquals("刷新成功", refreshFeedbackText(success, now + 60_000))
        assertNull(refreshFeedbackText(WidgetRefreshStatus(WidgetRefreshPhase.Failure, now), now + 1_000, now + 500))
        assertEquals("刷新超时，重试", refreshFeedbackText(WidgetRefreshStatus(WidgetRefreshPhase.Refreshing, now), now + 65_000))
    }

    @Test fun partialStaleMarketNamesAffectedSymbolWithoutHidingRefreshSuccess() {
        val fresh = snapshot()
        val staleCells = intervals.associateWith { cell(it, 20 * 60_000L).copy(symbol = "SKHYNIXUSDT") }
        val mixed = fresh.copy(table = fresh.table.copy(
            symbols = listOf("BTCUSDT", "SKHYNIXUSDT"),
            closedCells = fresh.table.closedCells + ("SKHYNIXUSDT" to staleCells),
        ))
        val data = widgetDataStatus(mixed, mixed.table.symbols, now)
        assertEquals("部分行情过期：SKHYNIX（1m/2m等5级）", data.warning)
        assertEquals(listOf("SKHYNIXUSDT"), data.staleSymbols)
        assertEquals(5, data.ready)
        val success = WidgetRefreshStatus(WidgetRefreshPhase.Success, now)
        assertTrue(widgetRefreshSummary(success, now, data.latestClosedAt, now + 60_000).startsWith("刷新成功 "))
        assertFalse(widgetRefreshSummary(success, now, data.latestClosedAt, now + 60_000).contains("过期"))
        val onlyFresh = widgetDataStatus(mixed, listOf("BTCUSDT"), now)
        assertNull(onlyFresh.warning)
    }

    @Test fun refreshFailureRemainsVisibleAndLoadingSurvivesSnapshotSaveUntilWorkerCompletes() {
        val failed = WidgetRefreshStatus(WidgetRefreshPhase.Failure, now)
        assertTrue(widgetRefreshSummary(failed, now - 1000, null, now + 60_000).startsWith("刷新失败，请重试"))
        assertEquals("刷新中…", refreshFeedbackText(WidgetRefreshStatus(WidgetRefreshPhase.Refreshing, now), now + 1000, now + 500))
    }

    @Test fun freshSourceMinuteDoesNotHideAnAbandonedFiveMinuteSeries() {
        val fetchedAt = Instant.parse("2026-09-12T16:35:04.812Z").toEpochMilli()
        val requested = listOf("1", "2", "3", "5", "8", "10", "15", "20", "30", "45", "60", "90", "120")
        val closeTimes = mapOf(
            "1" to "2026-09-12T16:34:59.999Z", "5" to "2026-09-06T12:49:59.999Z",
            "8" to "2026-09-12T16:31:59.999Z", "10" to "2026-09-12T16:29:59.999Z",
            "15" to "2026-09-12T16:29:59.999Z", "20" to "2026-09-12T16:19:59.999Z",
            "30" to "2026-09-12T16:29:59.999Z", "45" to "2026-09-12T16:29:59.999Z",
            "60" to "2026-09-12T15:59:59.999Z", "90" to "2026-09-12T16:29:59.999Z",
            "120" to "2026-09-12T15:59:59.999Z",
        )
        val closed = closeTimes.mapValues { (period, time) -> cell(period).copy(symbol = "UVXYUSDT", closeTime = time) }
        val table = GuailiTable(listOf("UVXYUSDT"), requested,
            mapOf("UVXYUSDT" to closed), mapOf("UVXYUSDT" to closed))
        val status = widgetDataStatus(GuailiSnapshot(table, fetchedAt), table.symbols, fetchedAt)
        assertEquals("部分行情过期：UVXY（5m）", status.warning)
        assertEquals(1, status.stale)
        assertEquals(0, status.missing)
        assertEquals(2, status.unavailable)
        assertEquals(listOf("5"), status.staleIntervalsBySymbol["UVXYUSDT"])
        assertEquals(listOf("8", "10", "15", "20", "30", "45", "60", "90", "120"),
            GuailiSignalDetector.detect(table, nowMillis = fetchedAt).single().primaryRun.intervals)
    }

    @Test fun removedTargetsNeverSilentlyBecomeAnotherSymbol() {
        val config = WidgetConfig(listOf("OLD"), listOf("99"), WidgetMode.Matrix)
        assertEquals(listOf("OLD"), reconcileWidgetSymbols(config.symbols, listOf("BTCUSDT"), config.mode))
        val issue = widgetConfigurationIssue(config, listOf("BTCUSDT"), listOf("5"))!!
        assertTrue(issue.contains("OLD"))
        assertTrue(issue.contains("99"))
    }

    @Test fun emptyBackendSeriesAreNormalButInvalidReturnedCandlesStillWarn() {
        val point = GuailiPoint(value = 5, isClosed = true, rankFilter = true,
            closeTime = Instant.ofEpochMilli(now - 30_000).toString())
        val response = GuailiResponse(listOf("BTCUSDT"), intervals, 2, 500, false,
            results = listOf(GuailiSymbolResult("BTCUSDT", listOf(
                GuailiSeries("1", latest = point, data = listOf(point)),
                GuailiSeries("2"), GuailiSeries("3"), GuailiSeries("5"), GuailiSeries("8"),
            ))))
        val data = GuailiSnapshot(response.toTable(response.symbols, intervals), now)
        val normal = widgetDataStatus(data, response.symbols, now)
        assertEquals(4, normal.unavailable)
        assertFalse(normal.incomplete)
        assertNull(normal.warning)
        assertTrue(GuailiSignalDetector.detect(data.table, nowMillis = now).isEmpty())

        val liveOnly = data.copy(table = data.table.copy(
            cells = mapOf("BTCUSDT" to mapOf("1" to cell().copy(isClosed = false))),
            closedCells = mapOf("BTCUSDT" to emptyMap()),
        ))
        val waitingForClose = widgetDataStatus(liveOnly, response.symbols, now)
        assertEquals(1, waitingForClose.missing)
        assertNull(waitingForClose.latestClosedAt)
        assertNotNull(waitingForClose.warning)

        listOf(cell().copy(closeTime = null), cell().copy(value = null), cell().copy(isClosed = false)).forEach { invalid ->
            val status = widgetDataStatus(snapshot(mapOf("1" to invalid)), listOf("BTCUSDT"), now)
            assertEquals(1, status.missing)
            assertTrue(status.incomplete)
            assertNotNull(status.warning)
        }
    }

    @Test fun anEntireMissingSymbolIsNotReportedAsNormalQuietData() {
        val data = snapshot()
        val status = widgetDataStatus(data, listOf("BTCUSDT", "UVXYUSDT"), now)
        assertEquals(listOf("UVXYUSDT"), status.noDataSymbols)
        assertEquals("暂无行情：UVXY", status.warning)
        assertTrue(status.incomplete)
    }

    @Test fun movingAverageLabelUsesSnapshotInsteadOfAssumingEma20() {
        assertEquals("SMA50", movingAverageLabel(snapshot().copy(maType = "SMA", maLength = 50)))
        assertEquals("均线（参数未知）", movingAverageLabel(snapshot()))
    }

    @Test fun unclosedAndUnknownClosureValuesAreMarkedInEveryMode() {
        assertEquals("12·", widgetCellValue(cell().copy(isClosed = false)))
        assertEquals("12·", widgetCellValue(cell().copy(isClosed = null)))
        assertEquals("12", widgetCellValue(cell()))
    }

    @Test fun snoozeAddsExactlyFifteenMinutesWithoutRounding() {
        assertEquals(Instant.parse("2026-09-05T02:16:00Z"), decisionReminderAfter(15, Instant.ofEpochMilli(now)))
    }

    @Test fun missedReminderIsNotifiedOncePerTargetAndCanBeSnoozedAgain() {
        val due = DecisionReminder("one", "BTCUSDT", "15", DecisionDirection.Long, now - 1)
        assertTrue(shouldNotifyReminder(due, now))
        val delivered = due.copy(notifiedTargetAtEpochMillis = due.targetAtEpochMillis)
        assertFalse(shouldNotifyReminder(delivered, now + 60_000))
        assertFalse(shouldNotifyReminder(due.copy(targetAtEpochMillis = now + 60_000), now))
        assertTrue(shouldNotifyReminder(delivered.copy(targetAtEpochMillis = now + 60_000), now + 120_000))
    }

    @Test fun notificationAndAlarmPermissionsHaveDistinctUserVisibleStates() {
        assertTrue(ReminderDeliveryStatus(false, true).label.contains("仅桌面"))
        assertTrue(ReminderDeliveryStatus(true, false).label.contains("延迟"))
        assertTrue(ReminderDeliveryStatus(true, true).label.contains("精确"))
    }

    @Test fun savedDraftRetainsEmptyReminderListAndOrdering() {
        val config = WidgetConfig(listOf("B", "A"), listOf("60", "5"), WidgetMode.DecisionReminders)
        assertEquals(config, Json.decodeFromString<WidgetConfig>(Json.encodeToString(config)))
        assertTrue(normalizeWidgetConfig(config).reminders.isEmpty())
        assertEquals(listOf("A", "B"), moveWidgetSelection(config.symbols, 1, -1))
    }
}
