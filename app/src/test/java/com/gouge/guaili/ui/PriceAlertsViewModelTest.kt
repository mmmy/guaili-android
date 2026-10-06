package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun samplePriceAlert() = PriceAlertDto(1, 1, 1, "BTCUSDT", "60", "BTCUSDT.P", "穿过多",
    PriceAlertGeometry("horizontal_segment", PriceAlertPoint(1000, 100.0), PriceAlertPoint(61000, 100.0), "right"),
    "cross_any", "once", "active", webhookUrl = "http://localhost/mock", messageTemplate = PriceAlertTemplate,
    label = "做多", createdAt = 1, updatedAt = 1, dataStatus = "live")

@OptIn(ExperimentalCoroutinesApi::class)
class PriceAlertsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private class Journal(var operations: List<PriceAlertOperation> = emptyList()) : PriceAlertJournal {
        var fail = false
        var cleanupGate: CompletableDeferred<Unit>? = null
        override fun read() = operations
        override suspend fun write(operations: List<PriceAlertOperation>) {
            if (fail) error("disk")
            if (operations.isEmpty()) cleanupGate?.await()
            this.operations = operations
        }
    }
    private class Api : PriceAlertGateway {
        var alert = samplePriceAlert()
        val calls = mutableListOf<PriceAlertOperation>()
        val committed = mutableMapOf<String, JsonElement>()
        var hook: (suspend (PriceAlertOperation) -> PriceAlertResult<JsonElement>)? = null
        var getHook: (suspend () -> PriceAlertResult<PriceAlertDto>)? = null
        override suspend fun list(symbol: String) = PriceAlertResult.Success(listOf(alert).filter { it.symbol == symbol })
        override suspend fun get(id: Long) = getHook?.invoke() ?: PriceAlertResult.Success(alert)
        override suspend fun market(symbol: String) = PriceAlertResult.Success(PriceAlertMarket(symbol, "$symbol.P", "0.10", "ready"))
        override suspend fun events(id: Long, before: Long?) = PriceAlertResult.Success(emptyList<PriceAlertEvent>())
        override suspend fun recover(mutationId: String): PriceAlertResult<JsonElement> = committed[mutationId]?.let { PriceAlertResult.Success(it) } ?: PriceAlertResult.Failure("absent", 404)
        override suspend fun send(operation: PriceAlertOperation): PriceAlertResult<JsonElement> {
            calls += operation
            return hook?.invoke(operation) ?: commit(operation)
        }
        fun commit(operation: PriceAlertOperation): PriceAlertResult<JsonElement> {
            committed[operation.mutationId]?.let { return PriceAlertResult.Success(it) }
            if (operation.method == "delete") {
                val result = buildJsonObject { put("deleted", true); put("id", alert.id); put("revision", alert.revision + 1) }
                committed[operation.mutationId] = result; return PriceAlertResult.Success(result)
            }
            alert = alert.copy(revision = alert.revision + 1,
                geometry = operation.body["geometry"]?.let { PriceAlertRepository.json.decodeFromJsonElement<PriceAlertGeometry>(it) } ?: alert.geometry)
            val result = PriceAlertRepository.json.encodeToJsonElement(alert)
            committed[operation.mutationId] = result
            return PriceAlertResult.Success(result)
        }
    }
    private fun geometry(price: Double) = samplePriceAlert().geometry.let { it.copy(first = it.first.copy(price = price), second = it.second.copy(price = price)) }

    @Test fun dragOnlySubmitsGeometryAndRevisionAndDoesNotEnablePausedAlert() = runTest {
        val api = Api(); api.alert = api.alert.copy(status = "disabled")
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertEquals(1, api.calls.size)
        assertEquals(setOf("mutationId", "expectedRevision", "geometry"), api.calls.single().body.keys)
        assertEquals("disabled", vm.state.value.alerts.single().status)
        assertTrue(journal.operations.isEmpty())
        assertEquals(geometry(100.0), vm.state.value.undo[1])
    }
    @Test fun continuousDragsCoalesceAndUseAcknowledgedRevision() = runTest {
        val api = Api(); val gate = CompletableDeferred<Unit>(); var count = 0
        api.hook = { op -> if (count++ == 0) gate.await(); api.commit(op) }
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent()
        vm.move(1, geometry(110.0)); runCurrent()
        vm.move(1, geometry(120.0)); vm.move(1, geometry(130.0)); runCurrent()
        assertEquals(1, api.calls.size)
        assertEquals(geometry(130.0), vm.state.value.pending[1]?.geometry)
        assertEquals(geometry(130.0), journal.operations.single().queuedGeometry)
        gate.complete(Unit); runCurrent()
        assertEquals(2, api.calls.size)
        assertEquals(2, api.calls.last().expectedRevision)
        assertEquals(geometry(130.0), vm.state.value.alerts.single().geometry)
        assertTrue(vm.state.value.pending.isEmpty())
    }
    @Test fun lostResponseRecoversOriginalMutationWithoutSendingAgain() = runTest {
        val api = Api(); api.hook = { op -> api.commit(op); PriceAlertResult.Failure("lost") }
        val vm = PriceAlertsViewModel(api, Journal())
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertEquals(1, api.calls.size)
        assertEquals(geometry(110.0), vm.state.value.alerts.single().geometry)
        assertTrue(vm.state.value.pending.isEmpty())
    }
    @Test fun immediateUndoDuringJournalCleanupIsDrained() = runTest {
        val api = Api(); val gate = CompletableDeferred<Unit>()
        val journal = Journal().apply { cleanupGate = gate }
        val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertTrue(vm.state.value.pending.isEmpty())
        vm.undo(1); runCurrent()
        assertEquals(1, api.calls.size)
        gate.complete(Unit); runCurrent()
        assertEquals(2, api.calls.size)
        assertEquals(geometry(100.0), vm.state.value.alerts.single().geometry)
        assertFalse(vm.state.value.undo.containsKey(1L))
        assertTrue(vm.state.value.pending.isEmpty())
    }
    @Test fun uncertainWriteRetainsExactRequestForLaterRecovery() = runTest {
        val api = Api(); api.hook = { PriceAlertResult.Failure("offline") }
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertEquals("uncertain", vm.state.value.pending[1]?.phase)
        assertEquals(2, api.calls.size)
        assertEquals(api.calls[0], api.calls[1])
        assertEquals(api.calls[0], journal.operations.single())
        api.hook = null
        val restored = PriceAlertsViewModel(api, journal)
        restored.refresh("BTCUSDT"); runCurrent()
        assertEquals(api.calls[0].mutationId, api.calls.last().mutationId)
        assertTrue(restored.state.value.pending.isEmpty())
    }
    @Test fun conflictNeedsExplicitRetryAgainstNewServerVersion() = runTest {
        val api = Api(); api.hook = { api.alert = api.alert.copy(revision = 7); PriceAlertResult.Failure("conflict", 409) }
        val vm = PriceAlertsViewModel(api, Journal()); vm.refresh("BTCUSDT"); runCurrent()
        vm.move(1, geometry(110.0)); runCurrent()
        assertEquals("failed", vm.state.value.pending[1]?.phase)
        assertEquals(7, vm.state.value.alerts.single().revision)
        assertEquals(1, api.calls.size)
        api.hook = null; vm.retry(1); runCurrent()
        assertEquals(7, api.calls.last().expectedRevision)
        assertNotEquals(api.calls.first().mutationId, api.calls.last().mutationId)
        assertTrue(vm.state.value.pending.isEmpty())
    }
    @Test fun badSuccessBodyDoesNotErasePendingRequest() = runTest {
        val api = Api(); api.hook = { PriceAlertResult.Success(buildJsonObject {}) }
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertEquals("uncertain", vm.state.value.pending[1]?.phase)
        assertFalse(journal.operations.isEmpty())
        assertEquals(geometry(100.0), vm.state.value.alerts.single().geometry)
    }
    @Test fun discardedRetryCannotSubmitAfterAnUncancellableLateRead() = runTest {
        val api = Api(); api.hook = { PriceAlertResult.Failure("rejected", 400) }
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        api.getHook = {
            reads++
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            PriceAlertResult.Success(api.alert)
        }
        vm.retry(1); vm.retry(1); runCurrent()
        assertEquals("retrying", vm.state.value.pending[1]?.phase)
        assertEquals(1, reads)
        vm.discardFailed(1); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(1, api.calls.size)
        assertTrue(vm.state.value.pending.isEmpty())
        assertTrue(journal.operations.isEmpty())
    }
    @Test fun restartingDoesNotAutomaticallyRetryARejectedEdit() = runTest {
        val api = Api(); api.hook = { PriceAlertResult.Failure("rejected", 400) }
        val journal = Journal(); val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        api.hook = null
        val restored = PriceAlertsViewModel(api, journal)
        restored.refresh("BTCUSDT"); runCurrent()
        assertEquals(1, api.calls.size)
        assertEquals("failed", restored.state.value.pending[1]?.phase)
        assertEquals(geometry(100.0), restored.state.value.alerts.single().geometry)
        restored.retry(1); runCurrent()
        assertEquals(2, api.calls.size)
        assertTrue(restored.state.value.pending.isEmpty())
    }
    @Test fun journalMustPersistBeforeSending() = runTest {
        val api = Api(); val journal = Journal().apply { fail = true }; val vm = PriceAlertsViewModel(api, journal)
        vm.refresh("BTCUSDT"); runCurrent(); vm.move(1, geometry(110.0)); runCurrent()
        assertTrue(api.calls.isEmpty())
        assertNotNull(vm.state.value.pending[1])
    }
    @Test fun explicitExpiryNullAndMetadataEditArePreservedWithoutRearm() = runTest {
        val api = Api(); val vm = PriceAlertsViewModel(api, Journal())
        vm.refresh("BTCUSDT"); runCurrent()
        vm.edit(api.alert, buildJsonObject { put("expiresAt", JsonNull); put("label", "观察") }); runCurrent()
        assertEquals(JsonNull, api.calls.single().body["expiresAt"])
        assertFalse(api.calls.single().body.containsKey("status"))
        assertFalse(api.calls.single().body.containsKey("geometry"))
    }
    @Test fun eventPayloadKeepsBooleanAndStringTypes() {
        val event = PriceAlertRepository.json.decodeFromString<PriceAlertEvent>("""{"id":"ns:1:2","alertId":1,"armGeneration":2,"triggeredAt":1000,"triggerPrice":101,"linePrice":100,"direction":"cross_up","payload":{"price":"101","noConfirm":false},"deliveryStatus":"success"}""")
        assertTrue(event.payload.jsonObject["price"]!!.jsonPrimitive.isString)
        assertFalse(event.payload.jsonObject["noConfirm"]!!.jsonPrimitive.isString)
        assertEquals(false, event.payload.jsonObject["noConfirm"]!!.jsonPrimitive.boolean)
    }
}
