package com.gouge.guaili.ui

import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.PullbackPreferences
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PullbackPriceAlertsTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = 1_800_000_000_000L
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun ended(period: String = "120"): PriceAlertDto {
        val duration = pullbackPeriodMillis(period)!!
        val open = now / duration * duration
        return samplePriceAlert().copy(interval = period, intent = "open_long", expiresAt = null, status = "active", dataStatus = "range_ended",
            geometry = PriceAlertGeometry("horizontal_segment", PriceAlertPoint(open - 7 * duration, 100.0), PriceAlertPoint(open - 2 * duration, 100.0), "none"))
    }
    private fun latest(alert: PriceAlertDto): KlineEnvelope {
        val duration = pullbackPeriodMillis(alert.interval)!!
        val open = now / duration * duration
        return KlineEnvelope(alert.symbol, listOf(alert.interval), 1, false, serverTime = now,
            series = listOf(KlineSeriesDto(alert.interval, count = 1, data = listOf(KlineRowDto(alert.symbol, alert.interval,
                KlineCandleDto(Instant.ofEpochMilli(open).toString(), Instant.ofEpochMilli(open + duration - 1).toString(), 100.0, 101.0, 99.0, 100.0, 1.0, 100.0, 1, false))))))
    }
    @Test fun intentsGroupByPositionSideAndUnknownsStayIndependent() {
        val a = ended()
        val records = listOf(a, a.copy(id = 2, intent = "close_long"), a.copy(id = 3, intent = "open_short"),
            a.copy(id = 4, intent = "close_short"), a.copy(id = 5, intent = "new_server_intent"),
            a.copy(id = 6, status = "triggered"), a.copy(id = 7, expiresAt = now - 1))
        val groups = groupPullbackAlerts(records, now).getValue(a.symbol)
        assertEquals(listOf(1L, 2L), groups.getValue("Long").map { it.id })
        assertEquals(listOf(3L, 4L), groups.getValue("Short").map { it.id })
        assertEquals(5L, groups.getValue("Unclassified").single().id)
        assertEquals(2L, primaryPullbackAlert(groups.getValue("Long"), 2)?.id)
        assertEquals(1L, primaryPullbackAlert(groups.getValue("Long"), 999)?.id)
        assertNotEquals(priceAlertPrimaryKey("http://a", "BTCUSDT", "Long"), priceAlertPrimaryKey("http://b", "BTCUSDT", "Long"))
        assertEquals("范围结束", a.pullbackStatus(now))
        assertEquals("待更新", a.pullbackStatus(now, true))
        assertEquals("等起点", a.copy(geometry = a.geometry.copy(first = a.geometry.first.copy(timeMs = now + 1), second = a.geometry.second.copy(timeMs = now + 100))).pullbackStatus(now))
    }
    @Test fun extensionUsesBoundSecondsHoursDaysAndWeeksAndPreservesGeometry() {
        for (interval in listOf("10S", "120", "2D", "W")) {
            val alert = ended(interval)
            val plan = extensionPlan(alert, 20, latest(alert))
            assertEquals(plan.latestOpen + 20 * pullbackPeriodMillis(interval)!!, plan.geometry.second.timeMs)
            assertEquals(alert.geometry.second.timeMs - alert.geometry.first.timeMs, plan.geometry.second.timeMs - plan.geometry.first.timeMs)
            assertEquals(alert.geometry.first.price, plan.geometry.first.price, 0.0)
            assertEquals(alert.geometry.second.price, plan.geometry.second.price, 0.0)
            assertEquals(alert.geometry.extend, plan.geometry.extend)
            assertTrue(plan.waitsForStart)
        }
        val short = extensionPlan(ended(), 1, latest(ended()))
        assertFalse(short.waitsForStart)
        assertEquals("待更新", short.alert.copy(geometry = short.geometry).pullbackStatus(now))
    }
    @Test fun latestBarMustMatchIdentityBeCurrentAndHaveServerClock() {
        val a = ended(); val response = latest(a)
        fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
        for (n in listOf(0, -1, 1001)) rejects { extensionPlan(a, n, response) }
        rejects { extensionPlan(a, 20, response.copy(symbol = "ETHUSDT")) }
        rejects { extensionPlan(a, 20, response.copy(closedOnly = true)) }
        rejects { extensionPlan(a, 20, response.copy(serverTime = null)) }
        rejects { extensionPlan(a, 20, response.copy(serverTime = now + 7_200_000)) }
        rejects { extensionPlan(a, 20, response.copy(series = emptyList())) }
        rejects { extensionPlan(a.copy(expiresAt = now), 20, response) }
        rejects { extensionPlan(a.copy(status = "triggered"), 20, response) }
        rejects { extensionPlan(a.copy(geometry = a.geometry.copy(extend = "right")), 20, response) }
        val prefs = PriceAlertRepository.json.decodeFromString<PullbackPreferences>("{}")
        assertEquals(20, prefs.extensionBars)
        val changed = prefs.copy(extensionBars = 33, primaryAlerts = mapOf("a|BTCUSDT|Long" to 42))
        assertEquals(changed, PriceAlertRepository.json.decodeFromString<PullbackPreferences>(PriceAlertRepository.json.encodeToString(changed)))
    }
    @Test fun uniquenessSeparatesCrossingFromIntentAndCountsEndedActiveLines() {
        val a = ended("D")
        fun op(intent: String, interval: String = "1D") = PriceAlertOperation("create", "test", body = buildJsonObject {
            put("symbol", a.symbol); put("interval", interval); put("status", "active"); put("intent", intent); put("direction", "cross_down")
        })
        assertEquals(a, conflictingPriceAlert(op("open_long"), listOf(a), now))
        assertNull(conflictingPriceAlert(op("close_long"), listOf(a), now))
        assertNull(conflictingPriceAlert(op("unclassified"), listOf(a), now))
        assertNull(conflictingPriceAlert(op("open_long", "120"), listOf(a), now))
        assertNull(conflictingPriceAlert(op("open_long"), listOf(a.copy(status = "disabled")), now))
    }
    private class Journal : PriceAlertJournal {
        var records = emptyList<PriceAlertOperation>()
        override fun read() = records
        override suspend fun write(operations: List<PriceAlertOperation>) { records = operations }
    }
    private inner class Api : PriceAlertGateway {
        var alert = ended()
        var lost = false
        var getGate: CompletableDeferred<Unit>? = null
        val sends = mutableListOf<PriceAlertOperation>()
        val receipts = mutableMapOf<String, JsonElement>()
        override suspend fun list(symbol: String) = PriceAlertResult.Success(listOf(alert))
        override suspend fun get(id: Long): PriceAlertResult<PriceAlertDto> { getGate?.await(); return PriceAlertResult.Success(alert) }
        override suspend fun latest(symbol: String, interval: String) = PriceAlertResult.Success(latest(alert))
        override suspend fun market(symbol: String) = PriceAlertResult.Success(PriceAlertMarket(symbol, "$symbol.P", "0.1", "ready"))
        override suspend fun events(id: Long, before: Long?) = PriceAlertResult.Success(emptyList<PriceAlertEvent>())
        override suspend fun recover(mutationId: String): PriceAlertResult<JsonElement> = receipts[mutationId]?.let { PriceAlertResult.Success(it) } ?: PriceAlertResult.Failure("absent", 404)
        override suspend fun send(operation: PriceAlertOperation): PriceAlertResult<JsonElement> {
            sends += operation
            receipts[operation.mutationId]?.let { return PriceAlertResult.Success(it) }
            if (operation.expectedRevision != alert.revision) return PriceAlertResult.Failure("version conflict", 409)
            val geometry = PriceAlertRepository.json.decodeFromJsonElement<PriceAlertGeometry>(operation.body.getValue("geometry"))
            alert = alert.copy(revision = alert.revision + 1, geometry = geometry)
            val result = PriceAlertRepository.json.encodeToJsonElement(alert)
            receipts[operation.mutationId] = result
            return if (lost) PriceAlertResult.Failure("lost receipt") else PriceAlertResult.Success(result)
        }
    }
    @Test fun nativeQueueRecoversLostReceiptOnceAndUndoIsSparseAndVersionGuarded() = runTest(dispatcher) {
        val api = Api().apply { lost = true }; val original = api.alert; val journal = Journal()
        val vm = PriceAlertsViewModel(api, journal, { now }, { testScheduler.currentTime })
        vm.synchronize(listOf(original)); vm.prepareExtension(original, 20); runCurrent()
        vm.confirmExtension(); vm.confirmExtension(); runCurrent()
        assertEquals(1, api.sends.size)
        assertEquals(setOf("geometry", "mutationId", "expectedRevision"), api.sends.single().body.keys)
        assertEquals(original.intent, api.alert.intent)
        assertTrue(journal.records.isEmpty()); assertNull(vm.state.value.extensionPreview)
        vm.undoExtension(); vm.undoExtension(); runCurrent()
        assertEquals(2, api.sends.size); assertEquals(original.geometry, api.alert.geometry)
        assertNull(vm.state.value.extensionUndo)
    }
    @Test fun conflictNeverRebasesExtensionAndCancelledPreviewCannotSubmit() = runTest(dispatcher) {
        val api = Api(); val vm = PriceAlertsViewModel(api, Journal(), { now }, { testScheduler.currentTime })
        vm.synchronize(listOf(api.alert)); vm.prepareExtension(api.alert, 20); runCurrent()
        api.alert = api.alert.copy(revision = api.alert.revision + 1)
        vm.confirmExtension(); runCurrent()
        assertEquals("failed", vm.state.value.pending[1L]?.phase)
        assertEquals(1, api.sends.size)
        vm.retry(1); runCurrent()
        assertEquals(1, api.sends.size)
        assertEquals(api.alert.revision, vm.state.value.extensionPreview?.alert?.revision)
        vm.closeExtensionPreview()
        val gate = CompletableDeferred<Unit>(); api.getGate = gate
        vm.prepareExtension(api.alert, 12); runCurrent(); vm.closeExtensionPreview(); gate.complete(Unit); runCurrent()
        assertNull(vm.state.value.extensionPreview); vm.confirmExtension(); runCurrent(); assertEquals(1, api.sends.size)
    }
    @Test fun undoExpiresAndDoesNotOverwriteTriggeredOrRevisedRecords() = runTest(dispatcher) {
        val api = Api(); val vm = PriceAlertsViewModel(api, Journal(), { now }, { testScheduler.currentTime })
        vm.synchronize(listOf(api.alert)); vm.prepareExtension(api.alert, 20); runCurrent(); vm.confirmExtension(); runCurrent()
        api.alert = api.alert.copy(status = "triggered", revision = api.alert.revision + 1)
        vm.synchronize(listOf(api.alert)); vm.undoExtension(); runCurrent()
        assertEquals(1, api.sends.size)
        assertNull(vm.state.value.extensionUndo)
        val undo = PriceAlertExtensionUndo(1, api.alert.revision, api.alert.geometry, 100)
        assertFalse(undo.validFor(api.alert.copy(status = "active"), now, 100))
    }
    @Test fun futureChartViewportCanLocateAnExtendedLineBeyondOrdinaryPanMargin() {
        val times = LongArray(100) { it * 60_000L }
        val geometry = PriceAlertGeometry("horizontal_segment", PriceAlertPoint(times.last() + 998 * 60_000, 250_000.0),
            PriceAlertPoint(times.last() + 1000 * 60_000, 250_000.0), "none")
        val offset = -1049f
        val viewport = calculateKlineViewport(100, 100f, offset, -offset)
        val mapping = AlertChartTransform(times, viewport, 0f, 600f, 0f, 400f, 240_000.0, 260_000.0, 60_000)
        assertTrue(mapping.xAt(geometry.first.timeMs) in 0f..600f)
        assertTrue(mapping.xAt(geometry.second.timeMs) in 0f..600f)
        assertEquals(1, mapping.segments(geometry).size)
        assertEquals(offset, reconcileKlineRightOffset(times.last(), times, 100f, offset, -offset), 0f)
    }
    @Test fun savedReceiptOverridesAnOlderInFlightOverviewButLaterDeletionWins() = runTest(dispatcher) {
        val old = ended(); val saved = old.copy(revision = old.revision + 1, geometry = extensionPlan(old, 20, latest(old)).geometry)
        val gate = CompletableDeferred<Unit>()
        var first = true
        var response = listOf(old)
        val seen = object : PriceAlertSeenStore {
            override fun readSeenTriggers() = emptySet<String>()
            override suspend fun saveSeenTriggers(triggers: Set<String>) {}
        }
        val vm = MarketPriceAlertsViewModel({ val snapshot = response; if (first) { first = false; gate.await() }; PriceAlertResult.Success(snapshot) }, seen) { now }
        vm.refresh(); runCurrent()
        vm.accept(PriceAlertConfirmation(PriceAlertOperation("patch", "receipt", old.id, old.revision, buildJsonObject {}), saved))
        gate.complete(Unit); runCurrent()
        assertEquals(saved, vm.state.value.alerts.single())
        response = listOf(saved); vm.refresh(); runCurrent()
        response = emptyList(); vm.refresh(); runCurrent()
        assertTrue(vm.state.value.alerts.isEmpty())
    }
    @Test fun journalRecoveryKeepsTheSameMutationAndDoesNotTranslateTwice() = runTest(dispatcher) {
        val api = Api(); val original = api.alert
        val plan = extensionPlan(original, 7, latest(original))
        val op = PriceAlertOperation("patch", "persisted-extension", original.id, original.revision,
            buildJsonObject { put("geometry", PriceAlertRepository.json.encodeToJsonElement(plan.geometry)); put("mutationId", "persisted-extension"); put("expectedRevision", original.revision) },
            original.geometry, purpose = "extension", extensionBars = 7)
        api.send(op)
        val journal = Journal().apply { records = listOf(op) }
        val restarted = PriceAlertsViewModel(api, journal, { now }, { testScheduler.currentTime })
        restarted.recoverPending(); runCurrent()
        assertEquals(1, api.sends.size)
        assertEquals(plan.geometry, restarted.state.value.alerts.single().geometry)
        assertTrue(journal.records.isEmpty())
        assertEquals(original.geometry, restarted.state.value.extensionUndo?.geometry)
    }
    @Test fun cancellingAnOldReadCannotClearTheNewForegroundReadOrStartDuplicateRequests() = runTest(dispatcher) {
        val cleanup = CompletableDeferred<Unit>()
        val newer = CompletableDeferred<Unit>()
        var reads = 0
        val seen = object : PriceAlertSeenStore {
            override fun readSeenTriggers() = emptySet<String>()
            override suspend fun saveSeenTriggers(triggers: Set<String>) {}
        }
        val vm = MarketPriceAlertsViewModel({
            if (++reads == 1) try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            else newer.await()
            PriceAlertResult.Success(emptyList())
        }, seen) { now }
        vm.setForeground(true); runCurrent()
        vm.setForeground(false); vm.setForeground(true); runCurrent()
        assertEquals(2, reads)
        cleanup.complete(Unit); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals(2, reads)
        vm.setForeground(false); runCurrent()
    }
}
