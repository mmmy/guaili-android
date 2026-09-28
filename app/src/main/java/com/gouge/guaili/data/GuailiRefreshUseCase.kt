package com.gouge.guaili.data

import com.gouge.guaili.domain.toTable
import com.gouge.guaili.domain.GuailiSignalEvolution
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

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
    private val snapshotSink: GuailiSnapshotSink = GuailiSnapshotSink { },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var fetcher: GuailiFetcher? = null
    private var fetcherBaseUrl: String? = null

    suspend fun refresh(settings: GuailiSettings, requirePersistence: Boolean = false): GuailiResult<GuailiSnapshot> =
        RefreshCoordinator.mutex.withLock {
            refreshLocked(settings, requirePersistence)
        }

    private suspend fun refreshLocked(settings: GuailiSettings, requirePersistence: Boolean): GuailiResult<GuailiSnapshot> {
        val currentFetcher = try {
            fetcherFor(settings.baseUrl)
        } catch (error: Exception) {
            return GuailiResult.Failure(error.message ?: "Invalid base URL", error)
        }

        val requestStarted = snapshotSink.currentDeviceTime()
        return when (val result = currentFetcher.fetch(settings)) {
            is GuailiResult.Failure -> result
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
                val baseSnapshot = GuailiSnapshot(
                    table = result.value.toTable(settings.symbols, settings.intervals),
                    updatedAt = nowMillis(),
                    timezone = result.value.timezone,
                    maType = settings.maType,
                    maLength = settings.maLength,
                    signalProfile = settings.signalProfile(),
                    signalRuleVersion = GuailiSignalEvolution.RuleVersion,
                    serverClock = serverClock,
                )
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
                val snapshot = baseSnapshot.copy(signals = if (time != null && !time.available) emptyList() else GuailiSignalEvolution.evaluate(
                    table = baseSnapshot.table,
                    nowMillis = evaluatedAt,
                    timezone = baseSnapshot.timezone,
                    previousTable = previous?.table,
                    previousSignals = previous?.signals.orEmpty(),
                    previousAt = previous?.serverClock?.serverTimeMillis ?: previous?.updatedAt ?: 0,
                ))
                if (requirePersistence) {
                    try {
                        snapshotSink.save(snapshot)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        return GuailiResult.Failure("行情已获取，但无法保存到小组件，请重试", error)
                    }
                } else {
                    snapshotSink.saveIgnoringStorageFailure(snapshot)
                }
                GuailiResult.Success(snapshot)
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

    // App and WorkManager use separate use-case instances, so the lock must be shared
    // across instances to prevent an older response from overwriting a newer snapshot.
    private object RefreshCoordinator {
        val mutex = Mutex()
    }
}

/** Presentation preferences don't reset observations; every indicator input does. */
internal fun GuailiSettings.signalProfile(): String {
    val inputs = listOf(baseUrl.trimEnd('/'), symbols.sorted(), intervals.sorted(), closedOnly,
        limit.coerceAtLeast(3), calcLimit, maType.uppercase(), maLength, atrLen, atrPercentLen, maxAtrRank, slopeMul, useSlope)
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(inputs.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
