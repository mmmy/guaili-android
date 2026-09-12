package com.gouge.guaili.widget

import org.junit.Assert.*
import org.junit.Test

class ReminderDraftMergeTest {
    private val original = DecisionReminder("one", "BTCUSDT", "15", DecisionDirection.Long, 1_000L)

    @Test fun unchangedEditorDoesNotResurrectCompletedReminder() {
        assertTrue(mergeReminderDraft(listOf(original), listOf(original), emptyList()).isEmpty())
    }

    @Test fun unchangedEditorKeepsNotificationSnooze() {
        val snoozed = original.copy(targetAtEpochMillis = 901_000L)
        assertEquals(listOf(snoozed), mergeReminderDraft(listOf(original), listOf(original), listOf(snoozed)))
    }

    @Test fun unrelatedLocalEditAndRemoteCompletionAreBothPreserved() {
        val other = original.copy(id = "two")
        val editedOther = other.copy(direction = DecisionDirection.Short)
        assertEquals(listOf(editedOther), mergeReminderDraft(listOf(original, other), listOf(original, editedOther), listOf(other)))
    }

    @Test fun explicitDeletionStaysDeletedWhileRemoteAdditionsArePreserved() {
        val added = original.copy(id = "two")
        assertEquals(listOf(added), mergeReminderDraft(listOf(original), emptyList(), listOf(original, added)))
    }

    @Test(expected = IllegalStateException::class)
    fun editingReminderThatWasCompletedRequiresReload() {
        mergeReminderDraft(listOf(original), listOf(original.copy(direction = DecisionDirection.Short)), emptyList())
    }

    @Test(expected = IllegalStateException::class)
    fun conflictingTimeEditsAreNotSilentlyOverwritten() {
        mergeReminderDraft(listOf(original), listOf(original.copy(targetAtEpochMillis = 2_000L)), listOf(original.copy(targetAtEpochMillis = 3_000L)))
    }

    @Test fun deliveryMarkerDoesNotCountAsConflictingUserEdit() {
        val notified = original.copy(notifiedTargetAtEpochMillis = original.targetAtEpochMillis)
        val edited = original.copy(targetAtEpochMillis = 2_000L)
        assertEquals(listOf(edited), mergeReminderDraft(listOf(original), listOf(edited), listOf(notified)))
    }

    @Test(expected = IllegalStateException::class)
    fun concurrentAdditionsDoNotSilentlyDropEntriesOverTheLimit() {
        val live = (1..12).map { original.copy(id = "live-$it") }
        mergeReminderDraft(emptyList(), listOf(original), live)
    }
}
