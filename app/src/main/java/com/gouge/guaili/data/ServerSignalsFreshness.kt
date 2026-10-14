package com.gouge.guaili.data

internal const val SERVER_SIGNALS_REQUEST_TIMEOUT_MILLIS = 12_000L

/** Server sampling freshness and the phone's refresh schedule are separate clocks. */
internal fun serverSignalsResultLifetime(response: ServerSignalsResponse): Long =
    response.qualityConfig?.maxResultAgeMs?.takeIf { it in 1..86_400_000L }
        ?: maxOf(15_000L, response.evaluationIntervalMs.coerceIn(1_000L, 300_000L) * 3)

internal fun serverSignalsPhoneCacheLifetime(response: ServerSignalsResponse, refreshIntervalMillis: Long?): Long {
    val resultLifetime = serverSignalsResultLifetime(response)
    // Background widgets retain their existing expiry policy; only the foreground
    // phone observer owns a configurable polling interval and request allowance.
    return if (refreshIntervalMillis == null) resultLifetime else maxOf(resultLifetime,
        refreshIntervalMillis.coerceIn(1_000L, Int.MAX_VALUE.toLong() * 1_000L) + SERVER_SIGNALS_REQUEST_TIMEOUT_MILLIS)
}

internal fun serverSignalsEvidenceTime(response: ServerSignalsResponse, nowMillis: Long, refreshIntervalMillis: Long?): Long =
    if (refreshIntervalMillis == null) nowMillis else response.serverTime
