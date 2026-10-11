package com.gouge.guaili.settings

import com.gouge.guaili.pullback.pullbackPeriodMillis
import kotlinx.serialization.Serializable

@Serializable
internal data class PullbackRules(
    val levels: Int = 5,
    val threshold: Int = 10,
    val strongLevels: Int = 2,
    val minInterval: String = "1",
    val reverseLimit: Int = 3,
) {
    fun error(): String? = when {
        levels !in 2..64 -> "连续级数须为 2–64"
        threshold !in 1..1000 -> "强弱阈值须为 1–1000"
        strongLevels !in 1 until levels -> "强势级数须为 1–${levels - 1}，只计背景级别"
        pullbackPeriodMillis(minInterval) == null -> "最小级别请输入分钟数字或 S / D / W，例如 1、15、30S、D"
        reverseLimit !in 0..1000 -> "反向容差须为 0–1000"
        else -> null
    }
}

@Serializable
internal data class PullbackPreferences(
    val rules: PullbackRules = PullbackRules(),
    val onlyCandidates: Boolean = false,
    val soundEnabled: Boolean = false,
)
