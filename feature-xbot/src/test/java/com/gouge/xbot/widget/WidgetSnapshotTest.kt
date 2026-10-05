package com.gouge.xbot.widget

import com.gouge.xbot.data.SignalViewDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    @Test
    fun `existing snapshot defaults to showing symbol`() {
        val snapshot = Json.decodeFromString<WidgetSnapshot>(
            """{
                "signalId":"1",
                "symbol":"BTCUSDT",
                "name":"Bitcoin",
                "periods":[],
                "longOn":true,
                "shortOn":false,
                "levelMin":"1",
                "levelMax":"5",
                "updatedAtMillis":1
            }""".trimIndent(),
        )

        assertTrue(snapshot.showSymbol)
        assertNull(snapshot.comment)
    }

    @Test
    fun `new snapshot stores hidden symbol preference`() {
        val signal = SignalViewDto(
            id = "1",
            symbol = "BTCUSDT",
            name = "Bitcoin",
            comment = "做多：突破均线 平多：反向信号",
        )

        val snapshot = WidgetSnapshot.from(signal, showSymbol = false)

        assertFalse(snapshot.showSymbol)
        assertEquals("做多：突破均线 平多：反向信号", snapshot.comment)
        assertEquals(signal.comment, snapshot.toSignalViewDto().comment)
    }

    @Test
    fun `legacy snapshot migrates to a single signal widget state`() {
        val json = Json { ignoreUnknownKeys = true }
        val state = decodeWidgetState(
            """{
                "signalId":"legacy",
                "symbol":"BTCUSDT",
                "name":"MA-TREND",
                "periods":[],
                "longOn":true,
                "shortOn":false,
                "levelMin":"1",
                "levelMax":"5",
                "updatedAtMillis":1
            }""".trimIndent(),
            json,
        )

        assertEquals(listOf("legacy"), state?.signals?.map { it.signalId })
        assertTrue(state?.signals?.single()?.showSymbol == true)
    }

    @Test
    fun `dual signal widget state round trips and replaces one signal`() {
        val json = Json { ignoreUnknownKeys = true }
        val first = WidgetSnapshot.from(SignalViewDto(id = "1", name = "MA-TREND"))
        val second = WidgetSnapshot.from(SignalViewDto(id = "2", name = "ATR-INDEX"))
        val encoded = json.encodeToString(WidgetState(listOf(first, second)))

        val decoded = decodeWidgetState(encoded, json)
        val updatedSecond = second.copy(periods = listOf("30"))
        val updated = decoded?.replace(updatedSecond)

        assertEquals(listOf("1", "2"), updated?.signals?.map { it.signalId })
        assertEquals(emptyList<String>(), updated?.signals?.first()?.periods)
        assertEquals(listOf("30"), updated?.signals?.last()?.periods)
    }
}
