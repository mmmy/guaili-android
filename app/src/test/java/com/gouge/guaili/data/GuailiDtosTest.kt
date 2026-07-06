package com.gouge.guaili.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GuailiDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesGuailiResponse() {
        val body = """
            {
              "symbols": ["BTCUSDT"],
              "intervals": ["1"],
              "limit": 1,
              "calcLimit": 500,
              "closedOnly": false,
              "timezone": "Asia/Shanghai",
              "serverTime": 1780000000000,
              "results": [
                {
                  "symbol": "BTCUSDT",
                  "series": [
                    {
                      "interval": "1",
                      "count": 1,
                      "latest": {
                        "openTime": "2024-03-10T00:19:00.000+08:00",
                        "closeTime": "2024-03-10T00:19:59.999+08:00",
                        "ma": 100.0,
                        "atr14": 10.0,
                        "atrRank": 50.0,
                        "rankFilter": true,
                        "guaili": 1.2,
                        "value": 12,
                        "longTrend": true,
                        "shortTrend": false,
                        "isClosed": false
                      },
                      "data": []
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString(GuailiResponse.serializer(), body)

        assertEquals(listOf("BTCUSDT"), parsed.symbols)
        assertEquals(500, parsed.calcLimit)
        assertEquals("Asia/Shanghai", parsed.timezone)
        assertEquals(1780000000000, parsed.serverTime)
        assertEquals("BTCUSDT", parsed.results.first().symbol)
        assertEquals("1", parsed.results.first().series.first().interval)
        assertEquals(1.2, parsed.results.first().series.first().latest?.guaili)
        assertEquals(12, parsed.results.first().series.first().latest?.value)
        assertEquals(true, parsed.results.first().series.first().latest?.longTrend)
        assertFalse(parsed.results.first().series.first().latest?.isClosed ?: true)
    }

    @Test
    fun missingRequiredTopLevelFieldsThrow() {
        val requiredFields = listOf("symbols", "intervals", "limit", "calcLimit", "closedOnly", "results")

        requiredFields.forEach { missingField ->
            val body = buildJsonMissing(missingField)

            assertThrows(SerializationException::class.java) {
                json.decodeFromString(GuailiResponse.serializer(), body)
            }
        }
    }

    private fun buildJsonMissing(missingField: String): String {
        val fields = linkedMapOf(
            "symbols" to """"symbols": ["BTCUSDT"]""",
            "intervals" to """"intervals": ["1"]""",
            "limit" to """"limit": 1""",
            "calcLimit" to """"calcLimit": 500""",
            "closedOnly" to """"closedOnly": false""",
            "results" to """"results": []""",
        )

        return fields
            .filterKeys { it != missingField }
            .values
            .joinToString(separator = ",\n", prefix = "{\n", postfix = "\n}")
    }
}
