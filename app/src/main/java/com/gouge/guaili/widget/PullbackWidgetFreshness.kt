package com.gouge.guaili.widget

import com.gouge.guaili.data.GuailiDeviceTime

// This is the deadline for another widget transfer, never the live market evidence TTL.
internal const val PullbackWidgetRefreshWindowMillis = 17 * 60_000L
internal const val PullbackWidgetReceiptWriteIntervalMillis = 60_000L

internal fun pullbackWidgetReceiptAge(receipt: GuailiDeviceTime?, device: GuailiDeviceTime): Long? {
    if (receipt == null || receipt.bootCount == null || receipt.bootCount != device.bootCount ||
        receipt.elapsedMillis < 0 || device.elapsedMillis < receipt.elapsedMillis) return null
    return device.elapsedMillis - receipt.elapsedMillis
}

internal fun pullbackWidgetReceiptOverdue(receipt: GuailiDeviceTime?, device: GuailiDeviceTime): Boolean =
    pullbackWidgetReceiptAge(receipt, device)?.let { it >= PullbackWidgetRefreshWindowMillis } ?: true

internal fun shouldPersistPullbackReceipt(previous: GuailiDeviceTime?, current: GuailiDeviceTime): Boolean =
    pullbackWidgetReceiptAge(previous, current)?.let { it >= PullbackWidgetReceiptWriteIntervalMillis } ?: true
