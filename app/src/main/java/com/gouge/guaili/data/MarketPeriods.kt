package com.gouge.guaili.data

/** Fixed market-service periods, shortest first. Shared independently of symbol availability. */
internal object MarketPeriods {
    internal data class Period(val interval: String, val durationMillis: Long)
    val all: List<Period> = listOf(
        Period("10S", 10_000), Period("15S", 15_000), Period("30S", 30_000), Period("45S", 45_000),
        Period("1", 60_000), Period("2", 120_000), Period("3", 180_000), Period("5", 300_000),
        Period("8", 480_000), Period("10", 600_000), Period("15", 900_000), Period("20", 1_200_000),
        Period("30", 1_800_000), Period("45", 2_700_000), Period("60", 3_600_000), Period("90", 5_400_000),
        Period("120", 7_200_000), Period("180", 10_800_000), Period("240", 14_400_000), Period("360", 21_600_000),
        Period("480", 28_800_000), Period("720", 43_200_000), Period("D", 86_400_000), Period("2D", 172_800_000),
        Period("3D", 259_200_000), Period("4D", 345_600_000), Period("W", 604_800_000), Period("10D", 864_000_000),
    )

    /** Integer comparison preserves fractional targets; equal distances keep the shorter period. */
    fun nearestSmaller(targetMillis: Long, divisor: Int): String? {
        if (targetMillis <= 0 || divisor !in 2..1000) return null
        var best: String? = null
        var distance = Long.MAX_VALUE
        for (index in all.indices) {
            val period = all[index]
            if (period.durationMillis >= targetMillis) break
            val delta = kotlin.math.abs(period.durationMillis * divisor - targetMillis)
            if (delta < distance) { best = period.interval; distance = delta }
        }
        return best
    }
}
