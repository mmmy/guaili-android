package com.gouge.guaili.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class ServerSignalsDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesDynamicServerContractAndKeepsMillisecondEvidence() {
        val response = json.decodeFromString<ServerSignalsResponse>("""
            {
              "enabled":true,"status":"degraded","serverTime":1791000005000,
              "evaluatedAt":1791000004000,"evaluationIntervalMs":5000,
              "configHash":"1234","ruleVersion":"live-v1","runId":"run-1",
              "snapshotVersion":21,"candleMode":"live","evaluationMode":"sampled_live",
              "delivery":{"queued":2,"recent":[]},
              "results":[{
                "symbol":"BTCUSDT","dataStatus":"degraded","sampledAt":1791000004000,
                "marketSequence":55,"generation":3,"lastMarketEventTime":1791000003999,
                "primarySignal":"run-1:BTCUSDT:1",
                "signals":[{
                  "id":"run-1:BTCUSDT:1","kind":"extreme","direction":"positive",
                  "runs":[{"direction":"positive","intervals":["3","5","8","10","15"],
                    "minAbsValue":10,"maxAbsValue":12,"meanAbsValue":11.0}],
                  "levelCount":5,"totalLevelCount":5,"anchorInterval":"15",
                  "firstObservedAt":1791000000000,"formedAt":null,"lastChangedAt":1791000004000
                }],
                "missingIntervals":["W"],
                "perIntervalQuality":[{
                  "interval":"15","availability":"ready","value":12,"guaili":1.29,
                  "ma":100,"atr14":10,"atrRank":90,"historyCount":499,
                  "openTime":1791000000000,"closeTime":1791000899999,
                  "marketEventTime":1791000003999,"isClosed":false
                },{"interval":"W","availability":"warming_up","reason":"insufficient history"}]
              }]
            }
        """.trimIndent())
        assertEquals(1791000005000L, response.serverTime)
        assertEquals("live", response.candleMode)
        val symbol = response.results.single()
        val signal = symbol.signals.single()
        assertEquals("15", signal.anchorInterval)
        assertEquals(listOf("3", "5", "8", "10", "15"), signal.runs.single().intervals)
        assertNull(signal.formedAt)
        assertEquals(1791000004000L, signal.lastChangedAt)
        assertFalse(symbol.perIntervalQuality.first().isClosed!!)
        assertEquals(1791000000000L, symbol.perIntervalQuality.first().openTime)
        assertEquals("warming_up", symbol.perIntervalQuality.last().availability)
    }

    @Test fun disabledAndStartupResponsesDoNotRequireSignalLists() {
        val response = json.decodeFromString<ServerSignalsResponse>(
            """{"enabled":false,"status":"disabled","serverTime":1791000005000}""",
        )
        assertTrue(response.results.isEmpty())
        assertNull(response.evaluatedAt)
        assertFalse(response.enabled)
    }

    @Test fun legacyStringTimesCannotSilentlyEnterTheV2Cache() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ServerSignalsResponse>(
                """{"enabled":true,"status":"ready","serverTime":"2026-10-02T00:00:00+08:00"}""",
            )
        }
    }

    @Test fun optional_unrounded_statistics_preserve_fractions_and_older_response_compatibility() {
        val legacy = json.decodeFromString<ServerSignalRun>(
            """{"direction":"neutral","intervals":["1"],"maxAbsValue":0,"meanAbsValue":0.0}""",
        )
        assertNull(legacy.maxAbsGuaili)
        assertNull(legacy.meanAbsGuaili)
        val current = json.decodeFromString<ServerSignalRun>(
            """{"direction":"neutral","intervals":["1"],"maxAbsValue":0,"meanAbsValue":0.0,"maxAbsGuaili":0.09,"meanAbsGuaili":0.01}""",
        )
        assertEquals(0, current.maxAbsValue)
        assertEquals(0.09, requireNotNull(current.maxAbsGuaili), 0.0)
        assertEquals(0.01, requireNotNull(current.meanAbsGuaili), 0.0)
    }
}
