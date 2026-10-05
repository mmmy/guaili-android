package com.gouge.xbot.data

import com.gouge.xbot.domain.businessExpireAt
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class XbotApiServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login matches backend contract`() = runBlocking {
        server.enqueue(jsonResponse("""{"access_token":"jwt-token"}"""))
        val api = ApiClientFactory.create(server.url("/").toString()) { null }

        val response = api.login(LoginRequest("test", "password"))

        assertEquals("jwt-token", response.accessToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/customer/login", request.path)
        assertEquals(
            """{"username":"test","password":"password"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `signal list supports optional fields returned by backend`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                [{
                  "_id":"667ab5ec48f6a8913c2bf6e6",
                  "name":"test1",
                  "comment":"做多：突破均线 平多：反向信号",
                  "symbol":"_指数",
                  "sort":20.3,
                  "periods":[],
                  "longOn":false,
                  "shortOn":false,
                  "levelMin":"",
                  "levelMax":""
                }]
                """.trimIndent(),
            ),
        )
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val signal = api.getSignalViews().single()

        assertEquals("667ab5ec48f6a8913c2bf6e6", signal.id)
        assertEquals("_指数", signal.symbol)
        assertEquals("做多：突破均线 平多：反向信号", signal.comment)
        assertEquals(20.3, signal.sort)
        assertFalse(signal.longOn)
        assertNull(signal.expireAt)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/customer/signal-view/list", request.path)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
    }

    @Test
    fun `single signal query uses authenticated id endpoint`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "_id":"667ab5ec48f6a8913c2bf6e6",
                  "name":"MA-TREND",
                  "symbol":"_指数",
                  "periods":["15","60"]
                }
                """.trimIndent(),
            ),
        )
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val signal = api.getSignalView("667ab5ec48f6a8913c2bf6e6")

        assertEquals("MA-TREND", signal.name)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/customer/signal-view/667ab5ec48f6a8913c2bf6e6",
            request.path,
        )
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
    }

    @Test
    fun `signal settings update sends only periods and expiry`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "_id":"667ab5ec48f6a8913c2bf6e6",
                  "name":"test1",
                  "symbol":"_指数",
                  "periods":["15","60","D"],
                  "expireAt":"2026-08-12T08:00:00.000Z"
                }
                """.trimIndent(),
            ),
        )
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val updated = api.updateSignalSettings(
            "667ab5ec48f6a8913c2bf6e6",
            UpdateSignalSettingsRequest(
                periods = listOf("15", "60", "D"),
                expireAt = "2026-08-12T08:00:00.000Z",
            ),
        )

        assertEquals(listOf("15", "60", "D"), updated.periods)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/customer/signal-view/667ab5ec48f6a8913c2bf6e6/settings", request.path)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
        assertEquals(
            """{"periods":["15","60","D"],"expireAt":"2026-08-12T08:00:00.000Z"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `omitting expiry requests permanent validity`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "_id":"667ab5ec48f6a8913c2bf6e6",
                  "name":"test1",
                  "symbol":"_指数",
                  "periods":[]
                }
                """.trimIndent(),
            ),
        )
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        api.updateSignalSettings(
            "667ab5ec48f6a8913c2bf6e6",
            UpdateSignalSettingsRequest(periods = emptyList(), expireAt = null),
        )

        val request = server.takeRequest()
        assertEquals("""{"periods":[]}""", request.body.readUtf8())
    }

    @Test
    fun `tv alert endpoints match backend contract`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                [{
                  "_id":"alert-config-1",
                  "title":"MA 趋势",
                  "cookieId":"cookie-1",
                  "namePre":"_MA_",
                  "periods":"15 60",
                  "sort":2.35,
                  "startTimeParamIndex":17,
                  "validBarsParamIndex":0,
                  "params":[
                    {"index":"16","value":true,"comment":"假突破开仓"},
                    {"index":"17","value":12.5,"comment":"偏移"},
                    {"index":"18","value":"OFFSET","comment":"模式"},
                    {"index":"19","value":null,"comment":"空值"},
                    {"index":"0","value":10,"comment":"有效 K 线数"}
                  ]
                }]
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                [{
                  "active":true,
                  "symbol":"={\"symbol\":\"BINANCE:BTCUSDT.P\"}",
                  "name":"_MA_BTC",
                  "alert_id":123,
                  "resolution":"15",
                  "create_time":"2026-08-19T08:00:00Z"
                }]
                """.trimIndent(),
            ),
        )
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val config = api.getTvAlertConfigs().single()
        val alert = api.getTvAlerts(
            TvAlertListRequest(cookieId = config.cookieId, namePre = "_"),
        ).single()

        assertEquals("alert-config-1", config.id)
        assertEquals(2.35, config.sort)
        assertEquals(17, config.startTimeParamIndex)
        assertEquals(0, config.validBarsParamIndex)
        assertEquals(listOf("true", "12.5", "OFFSET", "", "10"), config.params.map { it.value })
        assertEquals(123, alert.alertId)
        assertEquals(Instant.parse("2026-08-19T10:42:30Z"), businessExpireAt(config, alert))
        assertEquals("GET", server.takeRequest().method)
        val alertsRequest = server.takeRequest()
        assertEquals("POST", alertsRequest.method)
        assertEquals("/api/customer/tv-alert/all-alert-list", alertsRequest.path)
        assertEquals("""{"cookieId":"cookie-1","namePre":"_"}""", alertsRequest.body.readUtf8())
    }

    @Test
    fun `old and unconfigured alert configs keep business expiry unset`() = runBlocking {
        server.enqueue(jsonResponse("""
            [
              {"_id":"old-config"},
              {"_id":"unconfigured","startTimeParamIndex":null,"validBarsParamIndex":null}
            ]
        """.trimIndent()))
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val configs = api.getTvAlertConfigs()

        assertEquals(2, configs.size)
        configs.forEach {
            assertNull(it.startTimeParamIndex)
            assertNull(it.validBarsParamIndex)
            assertNull(businessExpireAt(it, TvAlertDto(alertId = 1)))
        }
    }

    @Test
    fun `TV alert creation sends selected symbol and periods`() = runBlocking {
        server.enqueue(jsonResponse("""{"result":true,"msg":"成功"}"""))
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val result = api.addTvAlerts(
            AddTvAlertRequest(
                alertId = "alert-config-1",
                symbols = "BINANCE:ETHUSDT.P",
                periods = "5S 15 60 D",
            ),
        )

        assertEquals(true, result.result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/customer/tv-alert/add-alerts", request.path)
        assertEquals(
            """{"alertId":"alert-config-1","symbols":"BINANCE:ETHUSDT.P","periods":"5S 15 60 D"}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `delete TV alert sends account and numeric alert id`() = runBlocking {
        server.enqueue(jsonResponse("""{"result":true,"msg":"已删除"}"""))
        val api = ApiClientFactory.create(server.url("/").toString()) { "jwt-token" }

        val result = api.deleteTvAlertById(
            DeleteTvAlertByIdRequest(cookieId = "cookie-1", alertId = 123),
        )

        assertEquals(true, result.result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/customer/tv-alert/delete-by-tv-alert-id", request.path)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
        assertEquals(
            """{"cookieId":"cookie-1","alertId":123}""",
            request.body.readUtf8(),
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
