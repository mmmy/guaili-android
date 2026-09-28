package com.gouge.guaili.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.serialization.Serializable
import kotlin.math.abs

data class GuailiDeviceTime(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int?)

fun readGuailiDeviceTime(context: Context): GuailiDeviceTime = GuailiDeviceTime(
    wallMillis = System.currentTimeMillis(),
    elapsedMillis = SystemClock.elapsedRealtime(),
    bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
)

@Serializable
data class GuailiServerClock(
    val serverTimeMillis: Long,
    val receivedElapsedMillis: Long,
    val bootCount: Int?,
    val requestDurationMillis: Long,
)

data class GuailiTimeAssessment(
    val nowMillis: Long? = null,
    val cacheAgeMillis: Long? = null,
    val fetchedAtMillis: Long? = null,
    val deviceOffsetMillis: Long? = null,
    val unavailableReason: String? = null,
) {
    val available: Boolean get() = nowMillis != null && unavailableReason == null

    val correctionMessage: String? get() {
        val offset = deviceOffsetMillis ?: return null
        if (!available || abs(offset) <= 2_000L) return null
        val amount = abs(offset)
        val duration = if (amount >= 86_400_000L) "约${(amount + 43_200_000L) / 86_400_000L}天"
            else "${(amount + 500L) / 1_000L}秒"
        return "设备${if (offset < 0) "慢" else "快"}$duration · 已按服务器时间判断"
    }
}

// serverTime is emitted near the end of the backend request. The whole request
// duration is a conservative upper bound on response transit uncertainty.
const val MAX_GUAILI_CLOCK_REQUEST_MILLIS = 5_000L

fun assessGuailiTime(snapshot: GuailiSnapshot, device: GuailiDeviceTime): GuailiTimeAssessment {
    val clock = snapshot.serverClock
        ?: return GuailiTimeAssessment(unavailableReason = "时间尚未校准，请刷新")
    val fetched = clock.serverTimeMillis.takeIf { it > 0 }
    fun unavailable(reason: String) = GuailiTimeAssessment(fetchedAtMillis = fetched, unavailableReason = reason)
    if (fetched == null || clock.receivedElapsedMillis < 0) return unavailable("服务器时间无效，请刷新")
    if (clock.bootCount == null || device.bootCount == null) return unavailable("时间基准不可确认，请刷新")
    if (clock.bootCount != device.bootCount || device.elapsedMillis < clock.receivedElapsedMillis) {
        return unavailable("设备已重启，请刷新校准时间")
    }
    if (clock.requestDurationMillis !in 0..MAX_GUAILI_CLOCK_REQUEST_MILLIS) {
        return unavailable("请求延迟过大，时间暂不可确认，请重试")
    }
    val age = device.elapsedMillis - clock.receivedElapsedMillis
    val now = runCatching { Math.addExact(fetched, age) }.getOrNull()
        ?: return unavailable("服务器时间无效，请刷新")
    return GuailiTimeAssessment(now, age, fetched, device.wallMillis - now)
}
