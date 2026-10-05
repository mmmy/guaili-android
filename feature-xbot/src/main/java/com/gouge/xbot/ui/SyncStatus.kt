package com.gouge.xbot.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun syncStatusText(updatedAtMillis: Long, loading: Boolean, error: String?, compact: Boolean = false): String {
    val updated = updatedAtMillis.takeIf { it > 0 }?.let {
        DateTimeFormatter.ofPattern(if (compact) "HH:mm:ss" else "MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    }
    if (compact) return when {
        loading -> "同步中…"
        error != null -> "同步失败"
        updated == null -> "未同步"
        else -> "同步 $updated"
    }
    return when {
        loading -> if (updated == null) "正在同步…" else "正在同步 · 上次 $updated"
        error != null -> if (updated == null) "同步失败" else "同步失败 · 保留 $updated 数据"
        updated == null -> "尚未同步"
        else -> "上次同步 $updated"
    }
}
