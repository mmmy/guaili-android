package com.gouge.xbot.domain

import java.time.Instant
import java.time.ZoneId

fun alignedPresetExpiry(
    minutes: Long,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): Instant {
    val alignmentMinutes = when {
        minutes == 15L -> 15L
        minutes == 30L -> 30L
        minutes > 0 && minutes % 60L == 0L -> 60L
        else -> throw IllegalArgumentException("Unsupported expiry preset: $minutes minutes")
    }
    val target = now.atZone(zoneId).plusMinutes(minutes)
    val minutesPastBoundary = target.minute % alignmentMinutes
    val alreadyAligned = minutesPastBoundary == 0L && target.second == 0 && target.nano == 0
    if (alreadyAligned) return target.toInstant()

    return target
        .plusMinutes(alignmentMinutes - minutesPastBoundary)
        .withSecond(0)
        .withNano(0)
        .toInstant()
}
