package com.gouge.guaili.widget

internal fun DecisionReminder.sameDefinition(other: DecisionReminder?): Boolean =
    other != null && copy(notifiedTargetAtEpochMillis = null) == other.copy(notifiedTargetAtEpochMillis = null)

/** Apply only edits made in this editor; preserve notification actions performed meanwhile. */
internal fun mergeReminderDraft(
    baseline: List<DecisionReminder>,
    draft: List<DecisionReminder>,
    live: List<DecisionReminder>,
): List<DecisionReminder> {
    val beforeById = baseline.associateBy(DecisionReminder::id)
    val liveById = live.associateBy(DecisionReminder::id)
    val draftIds = draft.map(DecisionReminder::id).toSet()
    val merged = draft.mapNotNull { incoming ->
        val before = beforeById[incoming.id]
        val current = liveById[incoming.id]
        when {
            incoming.sameDefinition(before) -> current // Includes a completed/deleted reminder.
            before == null && current == null -> incoming
            current != null && (current.sameDefinition(before) || current.sameDefinition(incoming)) -> incoming
            else -> throw IllegalStateException("这条提醒已在其他位置修改或完成，请重新打开编辑页后再修改")
        }
    } + live.filter { it.id !in beforeById && it.id !in draftIds }
    check(merged.size <= WidgetConfigStore.MaxReminders) { "其他位置新增了提醒，合并后超过数量上限，请重新打开编辑页" }
    return merged
}
