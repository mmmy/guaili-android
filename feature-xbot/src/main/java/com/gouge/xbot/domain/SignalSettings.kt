package com.gouge.xbot.domain

val SignalPeriodGroups: List<Pair<String, List<String>>> = listOf(
    "秒" to listOf("5S", "10S", "15S", "30S", "45S"),
    "分钟" to listOf(
        "1", "2", "3", "4", "5", "8", "10", "15", "20", "30", "45",
        "60", "90", "120", "180", "240", "360", "480", "720", "1080",
    ),
    "长周期" to listOf("D", "2D", "3D", "4D", "W", "2W", "M"),
)

val SignalPeriodOptions: List<String> = SignalPeriodGroups.flatMap { it.second }

private val periodOrder = SignalPeriodOptions.withIndex().associate { it.value to it.index }

fun sortSignalPeriods(periods: Collection<String>): List<String> =
    periods.distinct().sortedBy { periodOrder[it] ?: Int.MAX_VALUE }

fun fillSignalPeriodRange(periods: Collection<String>): List<String> {
    val selectedIndexes = periods.mapNotNull(periodOrder::get)
    if (selectedIndexes.size < 2) return sortSignalPeriods(periods)
    val range = selectedIndexes.min()..selectedIndexes.max()
    return SignalPeriodOptions.filterIndexed { index, _ -> index in range }
}
