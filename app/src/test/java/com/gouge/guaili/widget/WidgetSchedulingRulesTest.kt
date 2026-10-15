package com.gouge.guaili.widget

import com.gouge.guaili.data.ServerSignalsResponse
import com.gouge.guaili.data.ServerSignalsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSchedulingRulesTest {
    @Test fun periodicReminderUpdatesRequireAnActualReminderWidget() {
        assertFalse(needsDecisionReminderUpdates(WidgetMode.entries.filter { it != WidgetMode.DecisionReminders }.map(::config)))
        assertTrue(needsDecisionReminderUpdates(listOf(config(WidgetMode.Matrix), config(WidgetMode.DecisionReminders))))
        assertFalse(needsDecisionReminderUpdates(emptyList()))
    }

    @Test fun repeatedBackgroundStatusDoesNotChangeFeedbackButRecoveryAndDifferentErrorsDo() {
        val failure = WidgetRefreshStatus(WidgetRefreshPhase.Failure, 123, "无法连接")
        assertFalse(widgetRefreshStatusChanged(failure, WidgetRefreshPhase.Failure, "无法连接"))
        assertTrue(widgetRefreshStatusChanged(failure, WidgetRefreshPhase.Failure, "保存失败"))
        assertTrue(widgetRefreshStatusChanged(failure, WidgetRefreshPhase.Success, null))
        assertTrue(widgetRefreshStatusChanged(WidgetRefreshStatus(WidgetRefreshPhase.Refreshing), WidgetRefreshPhase.Success, null))
    }

    @Test fun repeatedEvaluatedResultKeepsOneExpiryButNewEvaluationAndServerGetNewExpiry() {
        val first = snapshot()
        assertEquals(serverSignalsExpiryIdentity(first), serverSignalsExpiryIdentity(first.copy(
            updatedAt = 8_000,
            response = first.response.copy(serverTime = 8_000),
        )))
        assertNotEquals(serverSignalsExpiryIdentity(first), serverSignalsExpiryIdentity(first.copy(
            response = first.response.copy(evaluatedAt = 7_000),
        )))
        assertNotEquals(serverSignalsExpiryIdentity(first), serverSignalsExpiryIdentity(first.copy(baseUrl = "http://second")))
        assertNull(serverSignalsExpiryIdentity(first.copy(response = first.response.copy(enabled = false))))
    }

    private fun config(mode: WidgetMode) = WidgetConfig(emptyList(), emptyList(), mode)
    private fun snapshot() = ServerSignalsSnapshot(
        ServerSignalsResponse(enabled = true, status = "ready", serverTime = 5_000, evaluatedAt = 4_000), 5_000, "http://first",
    )
}
