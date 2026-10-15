package com.gouge.guaili.data

import com.gouge.guaili.domain.toTable
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface GuailiFetcher {
    suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse>
}

fun interface GuailiFetcherFactory {
    fun create(baseUrl: String): GuailiFetcher
}

class GuailiRefreshUseCase(
    private val fetcherFactory: GuailiFetcherFactory = GuailiFetcherFactory { baseUrl ->
        val repository = GuailiRepository.create(baseUrl)
        GuailiFetcher(repository::fetch)
    },
    private val snapshotSink: GuailiSnapshotSink = NoopGuailiSnapshotSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var fetcher: GuailiFetcher? = null
    private var fetcherBaseUrl: String? = null

    suspend fun refresh(settings: GuailiSettings, requirePersistence: Boolean = false): GuailiResult<GuailiSnapshot> {
        val outcome = refreshes.run(RefreshKey(settings.requestKey(), RefreshSinkIdentity(snapshotSink.refreshIdentity()))) {
            refreshLocked(settings)
        }
        return if (requirePersistence && outcome.persistenceFailure != null) {
            GuailiResult.Failure("行情已获取，但无法保存到小组件，请重试", outcome.persistenceFailure)
        } else outcome.result
    }

    private suspend fun refreshLocked(settings: GuailiSettings): RefreshOutcome {
        val currentFetcher = try {
            fetcherFor(settings.baseUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return RefreshOutcome(GuailiResult.Failure(error.message ?: "Invalid base URL", error))
        }

        val requestStarted = snapshotSink.currentDeviceTime()
        // Signal mode always needs the current candle, independently of matrix display.
        return when (val result = currentFetcher.fetch(settings.copy(closedOnly = false))) {
            is GuailiResult.Failure -> RefreshOutcome(result)
            is GuailiResult.Success -> {
                val received = snapshotSink.currentDeviceTime()
                val serverClock = result.value.serverTime?.let { serverTime ->
                    if (received == null || requestStarted == null) null else GuailiServerClock(
                        serverTimeMillis = serverTime,
                        receivedElapsedMillis = received.elapsedMillis,
                        bootCount = received.bootCount,
                        requestDurationMillis = if (requestStarted.bootCount == received.bootCount) {
                            received.elapsedMillis - requestStarted.elapsedMillis
                        } else -1L,
                    )
                }
                val baseSnapshot = withContext(computationDispatcher) { GuailiSnapshot(
                    table = result.value.toTable(settings.symbols, settings.intervals, settings.closedOnly),
                    updatedAt = nowMillis(),
                    timezone = result.value.timezone,
                    maType = settings.maType,
                    maLength = settings.maLength,
                    signalProfile = settings.signalProfile(),
                    signalRuleVersion = GuailiSignalEvolution.RuleVersion,
                    serverClock = serverClock,
                ) }
                val time = received?.let { assessGuailiTime(baseSnapshot, it) }
                val evaluatedAt = time?.nowMillis ?: baseSnapshot.updatedAt
                val previous = try {
                    snapshotSink.read()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }?.takeIf {
                    it.signalProfile == baseSnapshot.signalProfile &&
                        it.signalRuleVersion == baseSnapshot.signalRuleVersion &&
                        it.timezone == baseSnapshot.timezone &&
                        if (received == null) {
                            !isGuailiSnapshotStale(it.updatedAt, baseSnapshot.updatedAt)
                        } else {
                            val priorTime = assessGuailiTime(it, received)
                            time?.available == true && priorTime.available &&
                                priorTime.cacheAgeMillis!! < GUAILI_STALE_AFTER_MILLIS &&
                                it.serverClock!!.serverTimeMillis <= evaluatedAt
                        }
                }
                val snapshot = withContext(computationDispatcher) { baseSnapshot.copy(signals = if (time != null && !time.available) emptyList() else GuailiSignalEvolution.evaluate(
                    table = baseSnapshot.table,
                    nowMillis = evaluatedAt,
                    timezone = baseSnapshot.timezone,
                    previousTable = previous?.table,
                    previousSignals = previous?.signals.orEmpty(),
                    previousAt = previous?.serverClock?.serverTimeMillis ?: previous?.updatedAt ?: 0,
                )) }
                val persistenceFailure = try {
                    snapshotSink.save(snapshot)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
                RefreshOutcome(GuailiResult.Success(snapshot), persistenceFailure)
            }
        }
    }

    private fun fetcherFor(baseUrl: String): GuailiFetcher {
        if (fetcher == null || fetcherBaseUrl != baseUrl) {
            fetcher = fetcherFactory.create(baseUrl)
            fetcherBaseUrl = baseUrl
        }
        return checkNotNull(fetcher)
    }

    private data class RefreshKey(
        val request: GuailiDataRequest,
        val sink: RefreshSinkIdentity,
    )

    private data class RefreshOutcome(
        val result: GuailiResult<GuailiSnapshot>,
        val persistenceFailure: Exception? = null,
    )

    companion object {
        // One observation and save, with persistence success interpreted by each waiting caller.
        // Distinct destinations and requests retain the original global write ordering.
        private val refreshes = RefreshSingleFlight<RefreshKey, RefreshOutcome>()
    }
}

private data class GuailiDataRequest(
    val baseUrl: String,
    val symbols: List<String>,
    val intervals: List<String>,
    val closedOnly: Boolean,
    val limit: Int,
    val calcLimit: Int,
    val maType: String,
    val maLength: Int,
    val atrLen: Int,
    val atrPercentLen: Int,
    val maxAtrRank: Double,
    val slopeMul: Double,
    val useSlope: Boolean,
)

private fun GuailiSettings.requestKey() = GuailiDataRequest(baseUrl.trimEnd('/'), symbols.toList(),
    intervals.toList(), closedOnly, limit.coerceAtLeast(3), calcLimit, maType, maLength,
    atrLen, atrPercentLen, maxAtrRank, slopeMul, useSlope)

/** Presentation preferences don't reset observations; every indicator input does. */
internal fun GuailiSettings.signalProfile(): String {
    val inputs = listOf(baseUrl.trimEnd('/'), symbols.sorted(), intervals.sorted(), closedOnly,
        limit.coerceAtLeast(3), calcLimit, maType.uppercase(), maLength, atrLen, atrPercentLen, maxAtrRank, slopeMul, useSlope)
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(inputs.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
