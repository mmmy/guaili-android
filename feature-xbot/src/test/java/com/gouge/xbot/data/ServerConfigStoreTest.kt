package com.gouge.xbot.data

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ServerConfigStoreTest {
    @Test
    fun `base url gets a trailing slash`() {
        assertEquals(
            "http://192.168.1.100:3002/",
            normalizeBaseUrl(" http://192.168.1.100:3002 "),
        )
    }

    @Test
    fun `base url requires an http scheme`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeBaseUrl("192.168.1.100:3002")
        }
    }

    @Test
    fun `base url rejects query and fragment`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeBaseUrl("http://localhost:3002/?token=secret")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeBaseUrl("http://localhost:3002/#api")
        }
    }

    @Test
    fun `base url rejects malformed host`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeBaseUrl("http://")
        }
    }
}
