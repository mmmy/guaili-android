package com.gouge.guaili.widget

import androidx.glance.GlanceId
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMarketWidgetRefreshTest {
    private data class Id(val number: Int) : GlanceId

    @Test fun snapshotDeliveryOnlyUpdatesTheIdsThatConsumeLegacyData() {
        val targets = WidgetMode.entries.mapIndexed { index, mode ->
            WidgetRefreshTarget(Id(index), WidgetConfig(listOf("BTCUSDT"), listOf("60"), mode))
        }
        val affected = legacyMarketWidgetTargets(targets)
        assertEquals(listOf(WidgetMode.Signals, WidgetMode.Matrix, WidgetMode.SingleSymbol), affected.map { it.config.mode })
        assertEquals(listOf(Id(0), Id(2), Id(3)), affected.map { it.id })
    }

    @Test fun v2AndReminderOnlyHomesReceiveNoLegacyUpdate() {
        val targets = listOf(WidgetMode.SignalsV2, WidgetMode.DecisionReminders).mapIndexed { index, mode ->
            WidgetRefreshTarget(Id(index), WidgetConfig(emptyList(), emptyList(), mode))
        }
        assertEquals(emptyList<WidgetRefreshTarget>(), legacyMarketWidgetTargets(targets))
    }
}
