package com.gouge.xbot.widget

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class AlertWidgetModelsTest {
    private val now = Instant.parse("2026-09-12T09:00:00Z")
    private val a = config("a", "_MA_", 9.0)
    private val b = config("b", "_ATR_", 1.0)
    private val snapshot = AlertSnapshot(
        configs = listOf(b, a),
        alertsByCookieId = mapOf("cookie" to listOf(alert(1, "_MA_", 3600), alert(2, "_ATR_", 7200),
            alert(3, "_MA_", -3600), alert(4, "_MA_", 0).copy(createTime = ""))),
    )

    @Test fun `uses only home selections and matches each configuration prefix`() {
        assertEquals(listOf(1L, 3L, 4L), alertWidgetEntries(snapshot, setOf("a")).map { it.alert.alertId })
        assertTrue(alertWidgetEntries(snapshot, emptySet()).isEmpty())
        assertTrue(alertWidgetEntries(snapshot, setOf("deleted-config")).isEmpty())
    }

    @Test fun `time order keeps unconfigured entries last in both directions`() {
        fun ids(order: AlertWidgetOrder) = alertWidgetRows(snapshot, setOf("a", "b"),
            AlertWidgetSettings(mode = AlertWidgetMode.Time, order = order))
            .map { (it as AlertWidgetRow.Alert).entry.alert.alertId }
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(AlertWidgetOrder.Earliest))
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(AlertWidgetOrder.Latest))
    }

    @Test fun `groups preserve home order and expand only the first group by default`() {
        val rows = alertWidgetRows(snapshot, setOf("a", "b"), AlertWidgetSettings())
        assertEquals(listOf("group:b", "alert:b:2", "group:a"), rows.map { it.key })
        assertTrue((rows.first() as AlertWidgetRow.Group).expanded)
        assertFalse((rows.last() as AlertWidgetRow.Group).expanded)
        assertEquals("3条 · 过期1", (rows.last() as AlertWidgetRow.Group).summary(now))
    }

    @Test fun `collapsed groups retain summaries and multiple groups can be expanded`() {
        val collapsed = alertWidgetRows(snapshot, setOf("a", "b"), AlertWidgetSettings(expandedGroupIds = emptySet()))
        assertEquals(2, collapsed.size)
        assertTrue(collapsed.all { it is AlertWidgetRow.Group })
        val expanded = alertWidgetRows(snapshot, setOf("a", "b"), AlertWidgetSettings(expandedGroupIds = setOf("a", "b")))
        assertEquals(6, expanded.size)
        assertEquals(listOf(2L, 3L, 1L, 4L), expanded.filterIsInstance<AlertWidgetRow.Alert>().map { it.entry.alert.alertId })
    }

    @Test fun `density and order settings survive persistence without changing selected groups`() {
        val settings = AlertWidgetSettings(AlertWidgetMode.Time, AlertWidgetOrder.Latest, AlertWidgetDensity.Normal, setOf("a"))
        assertEquals(settings, Json.decodeFromString<AlertWidgetSettings>(Json.encodeToString(settings)))
        val compact = settings.copy(density = AlertWidgetDensity.Compact)
        assertEquals(alertWidgetRows(snapshot, setOf("a"), settings), alertWidgetRows(snapshot, setOf("a"), compact))
    }

    @Test fun `remaining labels use only days hours or less than an hour`() {
        mapOf(1L to "不足1小时", 3599L to "不足1小时", 3600L to "1小时", 86399L to "23小时",
            86400L to "1天", 280000L to "3天").forEach { (seconds, text) ->
            assertEquals("剩余$text", alertWidgetRemaining(now.plusSeconds(seconds), now))
            assertEquals("超时$text", alertWidgetRemaining(now.minusSeconds(seconds), now))
        }
        assertEquals("", alertWidgetRemaining(null, now))
        assertEquals("业务过期：未配置", alertWidgetExpiryText(null, now))
        assertEquals("已过期 09-12 09:00:00", alertWidgetExpiryText(now, now, ZoneId.of("UTC")))
    }

    @Test fun `local repaint schedules label transitions without network polling`() {
        assertEquals(now.plusSeconds(1800), nextAlertWidgetChange(now.plusSeconds(1800), now))
        assertEquals(now.plusMillis(1800001), nextAlertWidgetChange(now.plusSeconds(5400), now))
        assertEquals(now.plusSeconds(1800), nextAlertWidgetChange(now.minusSeconds(5400), now))
        assertEquals(now.plusSeconds(60), nextAlertWidgetChange(now.plusMillis(1), now))
    }

    @Test fun `full account replacement handles re-created IDs and deletions`() {
        val updated = snapshot.copy(alertsByCookieId = mapOf("cookie" to listOf(alert(99, "_MA_", 7200))))
        assertEquals(listOf(99L), alertWidgetEntries(updated, setOf("a")).map { it.alert.alertId })
        assertTrue(alertWidgetEntries(updated.copy(alertsByCookieId = mapOf("cookie" to emptyList())), setOf("a")).isEmpty())
    }

    private fun config(id: String, prefix: String, sort: Double) = TvAlertConfigDto(id = id, namePre = prefix, cookieId = "cookie", sort = sort,
        startTimeParamIndex = 0, validBarsParamIndex = 1, params = listOf(TvAlertParamDto("0", "0"), TvAlertParamDto("1", "0")))
    private fun alert(id: Long, prefix: String, seconds: Long) = TvAlertDto(alertId = id, active = true, name = "$prefix$id",
        resolution = "60", createTime = now.plusSeconds(seconds).toString(), symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\"}")
}
