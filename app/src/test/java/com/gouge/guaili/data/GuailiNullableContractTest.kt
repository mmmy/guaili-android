package com.gouge.guaili.data

import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.toTable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GuailiNullableContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun backendNullAndValidZeroStayDistinctThroughMappingAndCachedSerialization() {
        val response = json.decodeFromString(GuailiResponse.serializer(), """
            {"symbols":["CLUSDT"],"intervals":["4D","10D"],"limit":1,"calcLimit":500,"closedOnly":false,
             "results":[{"symbol":"CLUSDT","series":[
              {"interval":"4D","latest":{"value":0,"guaili":0.0,"availability":"ready","historyCount":46}},
              {"interval":"10D","latest":{"value":null,"guaili":null,"availability":"warming_up",
                "reasonCode":"insufficient_history","reason":"指标连续历史不足","historyCount":18}}
             ]}]}
        """.trimIndent())
        val table = response.toTable(response.symbols, response.intervals)
        val ready = table.cells.getValue("CLUSDT").getValue("4D")
        assertEquals(0, ready.value)
        assertEquals("ready", ready.availability)
        assertEquals(true, ready.signalAtrReady)
        val missing = table.cells.getValue("CLUSDT").getValue("10D")
        assertNull(missing.value)
        assertNull(missing.guaili)
        assertEquals("insufficient_history", missing.reasonCode)
        assertEquals("指标连续历史不足", missing.reason)
        val restored = json.decodeFromString(GuailiCell.serializer(), json.encodeToString(GuailiCell.serializer(), missing))
        assertEquals(missing, restored)
    }
}
