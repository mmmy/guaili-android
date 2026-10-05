package com.gouge.xbot.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAlertCreationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: XbotApiService
    private val request = AddTvAlertRequest("config", "BINANCE:BTCUSDT.P", "15 60")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = ApiClientFactory.create(server.url("/").toString()) { "test-token" }
    }
    @After fun close() { server.shutdown() }

    @Test fun `creation waits for all server work before publishing success`() = runBlocking {
        enqueue("[]"); enqueue("""{"result":true}""")
        enqueue(process("running", 1)); enqueue(process("finished", 2))
        var submitted = false
        val result = api.addTvAlertsAndWait(request, { submitted = true }, 2, 0)
        assertTrue(submitted); assertTrue(result.result)
        assertEquals(4, server.requestCount)
    }

    @Test fun `partial creation failure is not reported as success`() = runBlocking {
        enqueue("[]"); enqueue("""{"result":true}"""); enqueue(process("error", 1))
        assertFalse(api.addTvAlertsAndWait(request, pollAttempts = 1, pollIntervalMillis = 0).result)
    }

    @Test fun `old completed process cannot confirm a new request`() = runBlocking {
        enqueue(process("finished", 2)); enqueue("""{"result":true}"""); enqueue(process("finished", 2))
        val result = api.addTvAlertsAndWait(request, pollAttempts = 1, pollIntervalMillis = 0)
        assertFalse(result.result)
        assertTrue(result.msg.contains("暂未确认"))
    }

    @Test fun `busy server blocks duplicate creation without posting`() = runBlocking {
        enqueue(process("running", 0))
        assertFalse(api.addTvAlertsAndWait(request).result)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun `waits until the list contains every newly created alert`() = runBlocking {
        val config = TvAlertConfigDto(id = "config", cookieId = "cookie", namePre = "_MA_")
        val old = """{"alert_id":1,"active":true,"name":"_MA_old","symbol":"={\"symbol\":\"BINANCE:BTCUSDT.P\"}","resolution":"15"}"""
        val fresh15 = old.replace("\"alert_id\":1", "\"alert_id\":2")
        val fresh60 = old.replace("\"alert_id\":1", "\"alert_id\":3").replace("\"resolution\":\"15\"", "\"resolution\":\"60\"")
        enqueue("[$old,$fresh15]")
        enqueue("[$fresh15,$fresh60]")
        assertTrue(api.awaitCreatedAlerts(config, request, setOf(1), 2, 2, 0))
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("/api/customer/tv-alert/all-alert-list", server.takeRequest().path) }
    }

    private fun enqueue(body: String) { server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(body)) }
    private fun process(status: String, done: Int) = """[{"alertId":"config","startDate":100,"status":"$status","maxAlerts":2,"finishedAlerts":$done}]"""
}
