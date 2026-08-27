package com.gouge.guaili.data

const val GUAILI_STALE_AFTER_MILLIS = 30 * 60 * 1000L

fun isGuailiSnapshotStale(updatedAt: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - updatedAt >= GUAILI_STALE_AFTER_MILLIS
