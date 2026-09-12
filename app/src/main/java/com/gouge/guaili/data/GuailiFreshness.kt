package com.gouge.guaili.data

import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiIntervalDurationMillis
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

const val GUAILI_STALE_AFTER_MILLIS = 30 * 60 * 1000L

fun isGuailiSnapshotStale(updatedAt: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    updatedAt <= 0L || nowMillis - updatedAt >= GUAILI_STALE_AFTER_MILLIS || updatedAt > nowMillis + 60_000L

/** Offset-bearing times take precedence; local times require the API's declared zone. */
fun parseGuailiTime(value: String?, timezone: String? = null): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(value.replace(' ', 'T'))
                .atZone(ZoneId.of(timezone ?: return null)).toInstant().toEpochMilli()
        }.getOrNull()
}

enum class CellAvailability { Ready, Missing, Unclosed, Filtered, UnknownTime, Stale }

fun signalCellAvailability(
    cell: GuailiCell?,
    nowMillis: Long = System.currentTimeMillis(),
    timezone: String? = null,
): CellAvailability {
    if (cell?.value == null) return CellAvailability.Missing
    if (cell.isClosed != true) return CellAvailability.Unclosed
    val closedAt = parseGuailiTime(cell.closeTime, timezone) ?: return CellAvailability.UnknownTime
    val period = guailiIntervalDurationMillis(cell.interval)
    if (period == Long.MAX_VALUE || period <= 0L) return CellAvailability.UnknownTime
    // A last closed candle is naturally up to one period old. Do not infer exchange
    // sessions from a symbol name: without a calendar, stopped data is conservatively stale.
    val grace = (period / 10).coerceIn(5_000L, 60_000L)
    if (closedAt > nowMillis + 2_000L || nowMillis - closedAt > period + grace) {
        return CellAvailability.Stale
    }
    if (cell.rankFilter != true) return CellAvailability.Filtered
    return CellAvailability.Ready
}
