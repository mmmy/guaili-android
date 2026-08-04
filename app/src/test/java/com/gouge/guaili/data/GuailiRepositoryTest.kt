package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class GuailiRepositoryTest {
    @Test
    fun fetchBatchesSymbolsAndIntervalsIntoOneRequest() = runTest {
        val response = GuailiResponse(
            symbols = listOf("BTCUSDT", "ETHUSDT"),
            intervals = listOf("1", "5"),
            limit = 2,
            calcLimit = 300,
            closedOnly = true,
            results = emptyList(),
        )
        val api = RecordingGuailiApiService(response = response)
        val repository = GuailiRepository(api)

        val result = repository.fetch(customSettings())

        assertTrue(result is GuailiResult.Success)
        assertSame(response, (result as GuailiResult.Success).value)
        assertEquals(1, api.calls.size)
        assertEquals(
            GuailiCall(
                symbols = "BTCUSDT,ETHUSDT",
                intervals = "1,5",
                limit = 2,
                calcLimit = 300,
                closedOnly = true,
                maLength = 34,
                maType = "SMA",
                atrLen = 14,
                atrPercentLen = 30,
                maxAtrRank = 72.5,
                slopeMul = 0.25,
                useSlope = false,
            ),
            api.calls.single(),
        )
    }

    @Test
    fun fetchRequestsAtLeastTwoPointsForPreviousCandleTrend() = runTest {
        val api = RecordingGuailiApiService()
        val repository = GuailiRepository(api)

        repository.fetch(GuailiSettings.defaults().copy(limit = 1))

        assertEquals(2, api.calls.single().limit)
    }

    @Test
    fun fetchReturnsFailureWhenRequestThrows() = runTest {
        val error = IllegalStateException("boom")
        val api = RecordingGuailiApiService(error = error)
        val repository = GuailiRepository(api)

        val result = repository.fetch(customSettings())

        assertTrue(result is GuailiResult.Failure)
        val failure = result as GuailiResult.Failure
        assertEquals("boom", failure.message)
        assertSame(error, failure.cause)
    }

    @Test
    fun fetchRethrowsCancellation() = runTest {
        val api = RecordingGuailiApiService(error = CancellationException("cancelled"))
        val repository = GuailiRepository(api)

        assertFailsWith<CancellationException> {
            repository.fetch(customSettings())
        }
    }

    private fun customSettings(): GuailiSettings = GuailiSettings.defaults().copy(
        symbols = listOf("BTCUSDT", "ETHUSDT"),
        intervals = listOf("1", "5"),
        limit = 2,
        calcLimit = 300,
        closedOnly = true,
        maLength = 34,
        maType = "SMA",
        atrLen = 14,
        atrPercentLen = 30,
        maxAtrRank = 72.5,
        slopeMul = 0.25,
        useSlope = false,
    )
}

private data class GuailiCall(
    val symbols: String,
    val intervals: String,
    val limit: Int,
    val calcLimit: Int,
    val closedOnly: Boolean,
    val maLength: Int,
    val maType: String,
    val atrLen: Int,
    val atrPercentLen: Int,
    val maxAtrRank: Double,
    val slopeMul: Double,
    val useSlope: Boolean,
)

private class RecordingGuailiApiService(
    private val response: GuailiResponse = GuailiResponse(
        symbols = emptyList(),
        intervals = emptyList(),
        limit = 0,
        calcLimit = 0,
        closedOnly = false,
        results = emptyList(),
    ),
    private val error: Throwable? = null,
) : GuailiApiService {
    val calls = mutableListOf<GuailiCall>()

    override suspend fun getGuaili(
        symbols: String,
        intervals: String,
        limit: Int,
        calcLimit: Int,
        closedOnly: Boolean,
        maLength: Int,
        maType: String,
        atrLen: Int,
        atrPercentLen: Int,
        maxAtrRank: Double,
        slopeMul: Double,
        useSlope: Boolean,
    ): GuailiResponse {
        calls += GuailiCall(
            symbols = symbols,
            intervals = intervals,
            limit = limit,
            calcLimit = calcLimit,
            closedOnly = closedOnly,
            maLength = maLength,
            maType = maType,
            atrLen = atrLen,
            atrPercentLen = atrPercentLen,
            maxAtrRank = maxAtrRank,
            slopeMul = slopeMul,
            useSlope = useSlope,
        )
        error?.let { throw it }
        return response
    }
}
