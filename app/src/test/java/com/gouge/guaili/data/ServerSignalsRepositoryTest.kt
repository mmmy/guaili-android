package com.gouge.guaili.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.assertFailsWith

class ServerSignalsRepositoryTest {
    @Test fun sendsSelectedSymbolsAsACommaSeparatedQuery() = runTest {
        val queries = mutableListOf<String?>()
        val response = ServerSignalsResponse(true, "ready", 10_000L,
            results = listOf(ServerSymbolSignals("BTCUSDT")))
        val repository = ServerSignalsRepository(ServerSignalsApiService { query ->
            queries += query
            response
        })
        val result = repository.fetch(listOf("BTCUSDT", " XAUUSDT ", "BTCUSDT", "")) as GuailiResult.Success
        assertSame(response, result.value)
        assertEquals(listOf("BTCUSDT,XAUUSDT"), queries)
    }

    @Test fun unconfiguredSymbolFallsBackOnceToKeepOtherWidgetsUsable() = runTest {
        val queries = mutableListOf<String?>()
        val response = ServerSignalsResponse(true, "ready", 10_000L,
            results = listOf(ServerSymbolSignals("BTCUSDT")))
        val repository = ServerSignalsRepository(ServerSignalsApiService { query ->
            queries += query
            if (query != null) throw httpError(400)
            response
        })
        val result = repository.fetch(listOf("BTCUSDT", "NOTCONFIGUREDUSDT")) as GuailiResult.Success
        assertEquals(listOf("BTCUSDT,NOTCONFIGUREDUSDT", null), queries)
        assertEquals(listOf("BTCUSDT"), result.value.results.map { it.symbol })
    }

    @Test fun serverFailureDoesNotRetryAsAnUnfilteredSuccess() = runTest {
        val queries = mutableListOf<String?>()
        val repository = ServerSignalsRepository(ServerSignalsApiService { query ->
            queries += query
            throw httpError(503)
        })
        assertTrue(repository.fetch(listOf("BTCUSDT")) is GuailiResult.Failure)
        assertEquals(listOf("BTCUSDT"), queries)
    }

    @Test fun fullQueryFailureDoesNotLoop() = runTest {
        var calls = 0
        val repository = ServerSignalsRepository(ServerSignalsApiService {
            calls++
            throw httpError(400)
        })
        assertTrue(repository.fetch() is GuailiResult.Failure)
        assertEquals(1, calls)
    }

    @Test fun missingEndpointProvidesAnActionableCompatibilityMessage() = runTest {
        val repository = ServerSignalsRepository(ServerSignalsApiService {
            throw HttpException(Response.error<ServerSignalsResponse>(
                404, "not found".toResponseBody("text/plain".toMediaType()),
            ))
        })
        val result = repository.fetch() as GuailiResult.Failure
        assertTrue(result.message.contains("不支持信号V2"))
        assertTrue(result.message.contains("原信号模式"))
    }

    @Test fun cancellationIsNotConvertedIntoAVisibleError() = runTest {
        val repository = ServerSignalsRepository(ServerSignalsApiService {
            throw CancellationException("cancelled")
        })
        assertFailsWith<CancellationException> { repository.fetch() }
    }

    private fun httpError(code: Int) = HttpException(Response.error<ServerSignalsResponse>(
        code, "request failed".toResponseBody("text/plain".toMediaType()),
    ))
}
