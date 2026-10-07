package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketPriceAlertsTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private class SeenStore : PriceAlertSeenStore {
        var saved = emptySet<String>()
        var fail = false
        override fun readSeenTriggers() = saved
        override suspend fun saveSeenTriggers(triggers: Set<String>) {
            if (fail) error("disk full")
            saved = triggers
        }
    }
    private fun triggered(id: Long = 2, generation: Long = 1) = samplePriceAlert().copy(
        id = id, interval = "30", status = "triggered", triggeredAt = 500, armGeneration = generation)

    @Test fun matrixGroupsBySymbolAndPeriodAndKeepsAlertHealthIndependentOfIndicatorData() {
        val active = samplePriceAlert()
        val unread = triggered()
        val seen = triggered(3)
        val state = MarketPriceAlertsState(listOf(active, active.copy(id = 4, dataStatus = "disconnected"),
            unread, seen, active.copy(id = 5, status = "disabled"), active.copy(id = 6, expiresAt = 999),
            active.copy(id = 7, status = "expired"), active.copy(id = 8, symbol = "SOXLUSDT")),
            seenTriggers = setOf(seen.triggerKey()!!), now = 1000)
        val btc = state.summaries().getValue("BTCUSDT")
        assertEquals(7, btc.total)
        assertEquals(PeriodPriceAlerts(2, 0, 1), btc.periods["60"])
        assertEquals(PeriodPriceAlerts(0, 1, 0), btc.periods["30"])
        assertEquals(1, btc.newTriggerCount)
        assertEquals(1, state.summaries().getValue("SOXLUSDT").total)
        assertEquals(unread.id, state.preferredAlert("BTCUSDT", "30")?.id)
        assertNull(state.preferredAlert("SOXLUSDT", "30"))
    }

    @Test fun viewingPersistsAcrossRestartAndRearmingCreatesANewUnreadTrigger() = runTest {
        var alert = triggered()
        val store = SeenStore()
        val vm = MarketPriceAlertsViewModel({ PriceAlertResult.Success(listOf(alert)) }, store) { 1000 }
        vm.refresh(); runCurrent()
        assertEquals(1, vm.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
        vm.acknowledge(listOf(alert)); runCurrent()
        assertEquals(0, vm.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
        val restarted = MarketPriceAlertsViewModel({ PriceAlertResult.Success(listOf(alert)) }, store) { 1000 }
        restarted.refresh(); runCurrent()
        assertEquals(0, restarted.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
        alert = alert.copy(revision = 8, deliveryStatus = "delivered", updatedAt = 900)
        restarted.refresh(); runCurrent()
        assertEquals(0, restarted.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
        alert = alert.copy(armGeneration = 2)
        restarted.refresh(); runCurrent()
        assertEquals(1, restarted.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
    }

    @Test fun acknowledgingAnOlderSnapshotDoesNotClearALaterTrigger() = runTest {
        val old = triggered()
        val newer = triggered(generation = 2)
        val vm = MarketPriceAlertsViewModel({ PriceAlertResult.Success(listOf(newer)) }, SeenStore()) { 1000 }
        vm.refresh(); runCurrent(); vm.acknowledge(listOf(old)); runCurrent()
        assertEquals(1, vm.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
    }

    @Test fun readFailureRetainsLastMarkersAndSuccessfulEmptyResponseRemovesDeletedAlerts() = runTest {
        var result: PriceAlertResult<List<PriceAlertDto>> = PriceAlertResult.Success(listOf(triggered()))
        val vm = MarketPriceAlertsViewModel({ result }, SeenStore()) { 1000 }
        vm.refresh(); runCurrent()
        result = PriceAlertResult.Failure("offline")
        vm.refresh(); runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals(1, vm.state.value.alerts.size)
        result = PriceAlertResult.Success(emptyList())
        vm.refresh(); runCurrent()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.summaries().isEmpty())
    }

    @Test fun failedSeenWriteKeepsBadgeAndCanBeRetried() = runTest {
        val store = SeenStore().apply { fail = true }
        val vm = MarketPriceAlertsViewModel({ PriceAlertResult.Success(listOf(triggered())) }, store) { 1000 }
        vm.refresh(); runCurrent(); vm.acknowledge(vm.state.value.alerts); runCurrent()
        assertNotNull(vm.state.value.seenError)
        assertEquals(1, vm.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
        store.fail = false
        vm.acknowledge(vm.state.value.alerts); runCurrent()
        assertNull(vm.state.value.seenError)
        assertEquals(0, vm.state.value.summaries().getValue("BTCUSDT").newTriggerCount)
    }

    @Test fun pollingRunsOnlyWhileForegroundAndExpiresMarkersWithoutServerChanges() = runTest {
        var calls = 0
        val vm = MarketPriceAlertsViewModel({ calls++; PriceAlertResult.Success(listOf(samplePriceAlert().copy(expiresAt = 5000))) },
            SeenStore()) { testScheduler.currentTime }
        vm.setForeground(true); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, calls)
        assertTrue(vm.state.value.summaries().getValue("BTCUSDT").periods.isEmpty())
        vm.setForeground(false)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, calls)
        vm.setForeground(true); runCurrent()
        assertEquals(3, calls)
        vm.setForeground(false)
    }
}
