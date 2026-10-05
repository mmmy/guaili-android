package com.gouge.xbot.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TvAlertResetterTest {
    private lateinit var server: MockWebServer
    private lateinit var resetter: TvAlertResetter
    private val config = TvAlertConfigDto(
        id = "config-1", cookieId = "cookie-1", namePre = "_MA_",
        tickerIds = "BINANCE:ETHUSDT.P", periods = "15 D", overwriteAlert = false,
    )
    private val oldAlert = TvAlertDto(
        alertId = 100, active = true, name = "_MA_old",
        symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\"}", resolution = "5S",
        createTime = "2026-09-12T01:00:00Z",
    )
    private val newAlert = oldAlert.copy(alertId = 200, name = "_MA_new", createTime = "2026-09-12T02:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        resetter = TvAlertResetter(
            ApiClientFactory.create(server.url("/").toString()) { "jwt-token" },
            maxPollAttempts = 2,
            pollIntervalMillis = 0,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `reset uses only current ticker and period with overwrite and never calls delete`() = runBlocking {
        enqueue(process("finished", 10))
        enqueue("""{"result":true,"msg":"开始设置警报:1"}""")
        enqueue(process("finished", 20))
        enqueue(Json.encodeToString(listOf(oldAlert))) // List cache has not caught up yet.
        enqueue(process("finished", 20))
        enqueue(Json.encodeToString(listOf(newAlert)))
        var submittedCount = 0

        val result = resetter.reset(config, oldAlert, setOf(100)) { submittedCount++ }

        assertEquals(1, submittedCount)
        assertEquals(listOf(newAlert), assertIs<TvAlertResetResult.Completed>(result).alerts)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals("/api/customer/tv-alert/alert-process-status", requests.first().path)
        val creation = requests.single { it.path == "/api/customer/tv-alert/add-alerts" }
        assertEquals("POST", creation.method)
        assertEquals("Bearer jwt-token", creation.getHeader("Authorization"))
        assertEquals(
            """{"alertId":"config-1","symbols":"BINANCE:BTCUSDT.P","periods":"5S","overwrite":true}""",
            creation.body.readUtf8(),
        )
        assertTrue(requests.none { "delete" in it.path.orEmpty() })
    }

    @Test
    fun `running configuration is not submitted again`() = runBlocking {
        enqueue(process("running", 10))

        assertIs<TvAlertResetResult.Failed>(resetter.reset(config, oldAlert, setOf(100)) { error("submitted") })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rejected overwrite stops without polling or deleting`() = runBlocking {
        enqueue("[]")
        enqueue("""{"result":false,"msg":"覆盖删除警报失败"}""")

        val result = resetter.reset(config, oldAlert, setOf(100)) { error("submitted") }

        assertEquals("覆盖删除警报失败", assertIs<TvAlertResetResult.Failed>(result).message)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `accepted request is not treated as completed when backend creation fails`() = runBlocking {
        enqueue("[]")
        enqueue("""{"result":true}""")
        enqueue(process("error", 20, finished = 0, message = "警报数量已达上限"))

        val result = resetter.reset(config, oldAlert, setOf(100)) {}

        assertEquals("警报数量已达上限", assertIs<TvAlertResetResult.Failed>(result).message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `stale process completion never confirms the new request`() = runBlocking {
        enqueue(process("finished", 10))
        enqueue("""{"result":true}""")
        repeat(2) { enqueue(process("finished", 10)) }

        assertIs<TvAlertResetResult.Unconfirmed>(resetter.reset(config, oldAlert, setOf(100)) {})
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(1, requests.count { it.path == "/api/customer/tv-alert/add-alerts" })
        assertEquals(0, requests.count { it.path == "/api/customer/tv-alert/all-alert-list" })
    }

    @Test
    fun `old duplicate inactive and unrelated alerts cannot confirm a reset`() = runBlocking<Unit> {
        enqueue("[]")
        enqueue("""{"result":true}""")
        repeat(2) {
            enqueue(process("finished", 20))
            enqueue(Json.encodeToString(listOf(
                oldAlert,
                oldAlert.copy(alertId = 101),
                newAlert.copy(active = false),
                newAlert.copy(alertId = 201, resolution = "60"),
                newAlert.copy(alertId = 202, name = "_ATR_new"),
                newAlert.copy(alertId = 203, symbol = "={\"symbol\":\"BINANCE:ETHUSDT.P\"}"),
            )))
        }

        assertIs<TvAlertResetResult.Unconfirmed>(resetter.reset(config, oldAlert, setOf(100, 101)) {})
    }

    @Test
    fun `waits for asynchronous creation before returning the new alert`() = runBlocking {
        enqueue("[]")
        enqueue("""{"result":true}""")
        enqueue(process("running", 20, finished = 0))
        enqueue(process("finished", 20))
        enqueue(Json.encodeToString(listOf(newAlert)))

        val result = resetter.reset(config, oldAlert, setOf(100)) {}

        assertEquals(listOf(newAlert), assertIs<TvAlertResetResult.Completed>(result).alerts)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `poll failure never resubmits the destructive overwrite`() = runBlocking {
        enqueue("[]")
        enqueue("""{"result":true}""")
        server.enqueue(MockResponse().setResponseCode(500))

        assertFailsWith<HttpException> { resetter.reset(config, oldAlert, setOf(100)) {} }
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(1, requests.count { it.path == "/api/customer/tv-alert/add-alerts" })
    }

    @Test
    fun `invalid source data is rejected before any network request`() = runBlocking {
        listOf(
            oldAlert.copy(symbol = ""), oldAlert.copy(resolution = ""),
            oldAlert.copy(resolution = "5S 60"), oldAlert.copy(name = "_OTHER_"),
        ).forEach { alert ->
            assertFailsWith<IllegalArgumentException> { resetter.reset(config, alert, setOf(100)) {} }
        }
        assertEquals(0, server.requestCount)
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(body))
    }

    private fun process(status: String, startDate: Long, finished: Int = 1, message: String = "") =
        Json.encodeToString(listOf(TvAlertProcessDto("config-1", startDate, status, 1, finished, message)))
}
