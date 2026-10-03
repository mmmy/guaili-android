package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** The identical JSON fixture is also checked by the Rust detector tests. */
class ServerSignalParityTest {
    private val now = 1_000_000L
    private val url = "http://localhost:3005/"
    private val symbol = "BTCUSDT"
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun shared_inputs_preserve_legacy_candidate_selection_visibility_and_trend() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/signal-structure-parity.json"))
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        for (case in fixture.getValue("cases").jsonArray) {
            val name = case.jsonObject.getValue("name").jsonPrimitive.content
            val evidence = case.jsonObject.getValue("evidence").jsonArray.map {
                json.decodeFromJsonElement<ServerIntervalEvidence>(it).copy(isClosed = false)
            }
            // Both production modes now consume equivalent current dynamic candles.
            val cells = evidence.associate { item -> item.interval to GuailiCell(
                symbol, item.interval, item.value.takeIf { item.availability in setOf("ready", "filtered") },
                item.guaili, null, 1.0, 50.0, item.availability != "filtered", null, null, false,
                java.time.Instant.ofEpochMilli(now / guailiIntervalDurationMillis(item.interval) * guailiIntervalDurationMillis(item.interval)).toString(),
                java.time.Instant.ofEpochMilli((now / guailiIntervalDurationMillis(item.interval) + 1) * guailiIntervalDurationMillis(item.interval) - 1).toString(),
                signalLongTrend = item.longTrend, signalShortTrend = item.shortTrend,
                signalAtrReady = true,
            ) }
            val table = GuailiTable(listOf(symbol), evidence.map { it.interval }, mapOf(symbol to cells))
            val candidates = GuailiSignalDetector.candidates(table, nowMillis = now)
            assertEquals("$name legacy candidates", expected(case, "expectedCandidates"), candidates.map(::signature).sorted())
            val raw = case.jsonObject.getValue("expectedAll").jsonArray.mapIndexed { index, shape ->
                structure(shape.jsonPrimitive.content, index, evidence)
            }
            val row = ServerSymbolSignals(symbol, "ready", now - 1_000, signals = raw, perIntervalQuality = evidence)
            val response = ServerSignalsResponse(true, "ready", now, now - 1_000, results = listOf(row))
            val enabledSets = listOf(GuailiSignalKind.entries.toSet(), emptySet()) +
                GuailiSignalKind.entries.map { setOf(it) } +
                GuailiSignalKind.entries.map { GuailiSignalKind.entries.toSet() - it }
            for (enabled in enabledSets) {
                val original = GuailiSignalEvolution.visible(candidates, table, listOf(symbol), enabled, now, null)
                if (enabled == GuailiSignalKind.entries.toSet()) {
                    assertEquals("$name original visibility", expected(case, "expectedWidget"), original.map(::signature).sorted())
                }
                val config = WidgetConfig(listOf(symbol), emptyList(), WidgetMode.SignalsV2, enabledSignalKinds = enabled)
                val snapshot = ServerSignalsSnapshot(response, now, url, GuailiServerClock(now, 5_000, 1, 100))
                val state = serverSignalsWidgetState(snapshot, null, config, url, GuailiDeviceTime(now, 5_000, 1))
                val actual = state.signals.map { requireNotNull(serverWidgetPresentation(it, response, now)) }
                assertEquals("$name enabled=$enabled", original.map(::signature), actual.map(::signature))
                assertEquals("$name trends enabled=$enabled", original.map { it.trend }, actual.map { it.trend })
                assertEquals("$name anchors enabled=$enabled", original.map { it.anchorInterval }, actual.map { it.anchorInterval })
                assertEquals("$name counts enabled=$enabled", original.map { it.totalLevelCount }, actual.map { it.totalLevelCount })
            }
        }
    }

    @Test fun cross_symbol_priority_and_selection_order_match_the_original_widget() {
        val intervals = listOf("1", "2", "3", "5", "8")
        val evidence = intervals.map { ServerIntervalEvidence(it, "ready") }
        fun signal(kind: String, index: Int) = ServerSignalStructure("id-$index", kind,
            if (kind == "compression") "neutral" else "positive",
            listOf(ServerSignalRun(if (kind == "compression") "neutral" else "positive", intervals)), 5, 5, "8")
        val rows = listOf(
            ServerSymbolSignals("A", "ready", now - 1_000, signals = listOf(signal("compression", 1)), perIntervalQuality = evidence),
            ServerSymbolSignals("B", "ready", now - 1_000, signals = listOf(signal("extreme", 2)), perIntervalQuality = evidence),
            ServerSymbolSignals("C", "ready", now - 1_000, signals = listOf(signal("compression", 3)), perIntervalQuality = evidence),
        )
        val response = ServerSignalsResponse(true, "ready", now, now - 1_000, results = rows)
        val snapshot = ServerSignalsSnapshot(response, now, url, GuailiServerClock(now, 5_000, 1, 100))
        val state = serverSignalsWidgetState(snapshot, null,
            WidgetConfig(listOf("C", "A", "B"), emptyList(), WidgetMode.SignalsV2), url,
            GuailiDeviceTime(now, 5_000, 1))
        assertEquals(listOf("B", "C", "A"), state.signals.map { it.symbol })
    }

    private fun expected(case: JsonElement, field: String) =
        case.jsonObject.getValue(field).jsonArray.map { it.jsonPrimitive.content }.sorted()

    private fun signature(signal: GuailiSignal): String = signal.kind.name.lowercase() + ":" +
        signal.runs.joinToString(">") { it.direction.name.lowercase() + "[" + it.intervals.joinToString(",") + "]" }

    private fun structure(shape: String, index: Int, evidence: List<ServerIntervalEvidence>): ServerSignalStructure {
        val (kind, coverage) = shape.split(":", limit = 2)
        val runs = coverage.split(">").map { run ->
            val direction = run.substringBefore("[")
            val intervals = run.substringAfter("[").removeSuffix("]").split(",")
            val cells = intervals.map { interval -> evidence.first { it.interval == interval } }
            val raw = cells.map { abs(requireNotNull(it.guaili)) }
            val values = cells.map { abs(requireNotNull(it.value)) }
            ServerSignalRun(direction, intervals, values.min(), values.max(), values.average(), raw.max(), raw.average())
        }
        return ServerSignalStructure("fixture-$index", kind, runs.first().direction, runs,
            runs.maxOf { it.intervals.size }, runs.sumOf { it.intervals.size },
            runs.maxBy { guailiIntervalDurationMillis(it.intervals.last()) }.intervals.last(), now - 1_000,
            lastChangedAt = now - 1_000)
    }
}
