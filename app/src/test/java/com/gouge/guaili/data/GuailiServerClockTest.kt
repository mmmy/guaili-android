package com.gouge.guaili.data

import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.GuailiTable
import com.gouge.guaili.widget.WidgetConfig
import com.gouge.guaili.widget.WidgetMode
import com.gouge.guaili.widget.widgetDataStatus
import com.gouge.guaili.widget.widgetSignals
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GuailiServerClockTest {
    private val serverNow = Instant.parse("2026-09-28T16:33:57Z").toEpochMilli()
    private val elapsed = 90_000L
    private val boot = 7
    private val intervals = listOf("1", "2", "3", "5", "8")

    private fun snapshot() = GuailiSnapshot(
        table = GuailiTable(listOf("BTCUSDT"), intervals, emptyMap(), mapOf("BTCUSDT" to intervals.associateWith {
            GuailiCell("BTCUSDT", it, 12, 1.2, 100.0, 1.0, 50.0, true, false, false, true,
                null, Instant.ofEpochMilli(serverNow - 1_000L).toString())
        })),
        updatedAt = serverNow - 86_000L,
        serverClock = GuailiServerClock(serverNow, elapsed, boot, 300),
    )

    @Test fun allDeviceClockOffsetsProduceSameMarketAndSignals() {
        val snapshot = snapshot()
        for (offset in listOf(-86_000L, 180_000L, -16 * 86_400_000L, 16 * 86_400_000L)) {
            val time = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow + offset, elapsed, boot))
            assertTrue(time.available)
            assertEquals(serverNow, time.nowMillis)
            val status = widgetDataStatus(snapshot, listOf("BTCUSDT"), time = time)
            assertEquals(5, status.ready)
            assertEquals(0, status.stale)
            assertEquals(0, status.future)
            assertFalse(status.snapshotStale)
            assertNotNull(status.clockCorrection)
            assertFalse(widgetSignals(snapshot, WidgetConfig(listOf("BTCUSDT"), intervals, WidgetMode.Signals),
                time.nowMillis!!).isEmpty())
        }
    }

    @Test fun manualDateChangesDoNotMoveEstimatedServerTimeOrCacheAge() {
        val snapshot = snapshot()
        val before = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow, elapsed + 1_000L, boot))
        val after = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow + 16 * 86_400_000L,
            elapsed + 2_000L, boot))
        assertEquals(1_000L, after.nowMillis!! - before.nowMillis!!)
        assertEquals(2_000L, after.cacheAgeMillis)
        val expired = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow,
            elapsed + GUAILI_STALE_AFTER_MILLIS, boot))
        assertTrue(widgetDataStatus(snapshot, listOf("BTCUSDT"), time = expired).snapshotStale)
    }

    @Test fun processRestartKeepsAnchorButDeviceRebootRequiresRefresh() {
        val snapshot = Json.decodeFromString<GuailiSnapshot>(Json.encodeToString(GuailiSnapshot.serializer(), snapshot()))
        assertTrue(assessGuailiTime(snapshot, GuailiDeviceTime(serverNow, elapsed + 500, boot)).available)
        // A reboot may already have more uptime than the old anchor: compare boot IDs too.
        val rebooted = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow, elapsed + 500, boot + 1))
        assertFalse(rebooted.available)
        val status = widgetDataStatus(snapshot, listOf("BTCUSDT"), time = rebooted)
        assertTrue(status.timeUncertain)
        assertEquals(0, status.stale)
        assertTrue(status.warning!!.contains("设备已重启"))
        assertFalse(assessGuailiTime(snapshot, GuailiDeviceTime(serverNow, elapsed - 1, boot)).available)
    }

    @Test fun missingAndSlowServerTimeCannotBeLabeledAsStaleMarket() {
        val snapshot = snapshot()
        for (invalid in listOf(snapshot.copy(serverClock = null), snapshot.copy(serverClock =
            snapshot.serverClock!!.copy(requestDurationMillis = MAX_GUAILI_CLOCK_REQUEST_MILLIS + 1)),
            snapshot.copy(serverClock = snapshot.serverClock!!.copy(bootCount = null)))) {
            val time = assessGuailiTime(invalid, GuailiDeviceTime(serverNow, elapsed, boot))
            assertFalse(time.available)
            val status = widgetDataStatus(invalid, listOf("BTCUSDT"), time = time)
            assertEquals(0, status.stale)
            assertTrue(status.timeUncertain)
            assertFalse(status.warning!!.contains("行情过期"))
        }
    }

    @Test fun realOldMissingAndFutureDataStillHaveDistinctWarnings() {
        val snapshot = snapshot()
        val time = assessGuailiTime(snapshot, GuailiDeviceTime(serverNow - 86_000L, elapsed, boot))
        val cells = snapshot.table.closedCells.getValue("BTCUSDT")
        val oldCell = cells.getValue("1").copy(closeTime = Instant.ofEpochMilli(serverNow - 600_000L).toString())
        val old = snapshot.copy(table = snapshot.table.copy(closedCells = mapOf("BTCUSDT" to (cells + ("1" to oldCell)))))
        val stale = widgetDataStatus(old, listOf("BTCUSDT"), time = time)
        assertEquals(1, stale.stale)
        assertTrue(stale.warning!!.contains("行情过期"))
        val missing = widgetDataStatus(snapshot, listOf("BTCUSDT", "UVXYUSDT"), time = time)
        assertEquals("暂无行情：UVXY", missing.warning)
        val futureCell = cells.getValue("1").copy(closeTime = Instant.ofEpochMilli(serverNow + 60_000L).toString())
        val future = widgetDataStatus(snapshot.copy(table = snapshot.table.copy(closedCells =
            mapOf("BTCUSDT" to (cells + ("1" to futureCell))))), listOf("BTCUSDT"), time = time)
        assertEquals(0, future.stale)
        assertEquals(1, future.future)
        assertTrue(future.warning!!.contains("收线时间异常"))
    }
}
