package com.gouge.xbot.data

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class ApiClientReuseTest {
    @Test fun `session services reuse transport without reusing another sessions authorization`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/").toString()
            var oldSessionCurrent = true
            val old = ApiClientFactory.forSession(url, 100, "old-token") { oldSessionCurrent }
            assertSame(old, ApiClientFactory.forSession(url, 100, "old-token") { oldSessionCurrent })
            val current = ApiClientFactory.forSession(url, 101, "new-token") { true }
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]")) }

            old.getSignalViews()
            current.getSignalViews()

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("Bearer old-token", first.getHeader("Authorization"))
            assertEquals("Bearer new-token", second.getHeader("Authorization"))
            assertEquals(0, first.sequenceNumber)
            assertEquals(1, second.sequenceNumber, "Session changes should retain the reusable HTTP connection")

            oldSessionCurrent = false
            assertFailsWith<IOException> { old.getSignalViews() }
            assertEquals(2, server.requestCount, "An invalidated service must fail before sending an old token")
        } finally {
            server.shutdown()
        }
    }
}
