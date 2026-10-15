package com.gouge.guaili.pullback

import com.gouge.guaili.data.PriceAlertDto

internal enum class AlertValidityUnit(val millis: Long, val suffix: String, val spoken: String) {
    Days(86_400_000L, "D", "天"),
    Hours(3_600_000L, "H", "小时"),
    Minutes(60_000L, "m", "分钟"),
}

internal data class PullbackAlertDisplay(
    val label: String,
    val unit: AlertValidityUnit? = null,
    val nextChangeAt: Long? = null,
    val extendable: Boolean = false,
    val description: String = label,
)

/** Remaining validity, never a prediction of crossing, polling or candle close. */
internal fun PriceAlertDto.pullbackDisplay(now: Long, stale: Boolean = false): PullbackAlertDisplay {
    val canExtend = canExtend(now)
    // Mutations override this display in the UI. Retained triggers and data errors
    // always remain explicit, including when the line's start is in the future.
    val state = when {
        status == "triggered" || stale || !isEnabledAt(now) -> pullbackStatus(now, stale)
        dataStatus == "waiting_for_metadata" || dataStatus == "unavailable" -> "等精度"
        dataStatus == "disconnected" -> "行情断开"
        dataStatus == "invalid_line" || !geometry.valid() -> "线价无效"
        dataStatus == "range_ended" -> if (canExtend) "范围结束" else "待更新"
        dataStatus != "live" && dataStatus != "waiting_for_range" -> "等行情"
        else -> pullbackStatus(now)
    }
    if ((state != "监测" && state != "等起点") || (dataStatus != "live" && dataStatus != "waiting_for_range")) {
        return PullbackAlertDisplay(state, extendable = canExtend)
    }
    val end = if (geometry.extend == "none") expiresAt?.let { minOf(it, geometry.second.timeMs) }
        ?: geometry.second.timeMs else expiresAt
    if (end == null || end < now || now < 0L) {
        return PullbackAlertDisplay(if (state == "等起点") "未开始" else state, extendable = canExtend)
    }
    val remaining = end - now
    val unit = when {
        remaining >= AlertValidityUnit.Days.millis -> AlertValidityUnit.Days
        remaining >= AlertValidityUnit.Hours.millis -> AlertValidityUnit.Hours
        else -> AlertValidityUnit.Minutes
    }
    val amount = remaining / unit.millis
    val label = if (amount == 0L) "<1m" else "$amount${unit.suffix}"
    // Truncation changes immediately AFTER an exact unit multiple. Below one
    // minute, wait until the actual end; there is no one-second timer.
    val boundary = end - amount * unit.millis
    return PullbackAlertDisplay(label, unit, boundary.takeIf { it < Long.MAX_VALUE }?.plus(1),
        canExtend, if (amount == 0L) "剩余有效时间不足一分钟" else "剩余有效时间约 $amount ${unit.spoken}")
}
