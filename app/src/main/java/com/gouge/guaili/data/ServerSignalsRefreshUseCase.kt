package com.gouge.guaili.data

import kotlinx.coroutines.CancellationException

fun interface ServerSignalsFetcher {
    suspend fun fetch(symbols: List<String>): GuailiResult<ServerSignalsResponse>
}

fun interface ServerSignalsFetcherFactory {
    fun create(baseUrl: String): ServerSignalsFetcher
}

class ServerSignalsRefreshUseCase(
    private val fetcherFactory: ServerSignalsFetcherFactory = ServerSignalsFetcherFactory { baseUrl ->
        val repository = ServerSignalsRepository.create(baseUrl)
        ServerSignalsFetcher(repository::fetch)
    },
    private val snapshotSink: ServerSignalsSnapshotSink = NoopServerSignalsSnapshotSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var fetcher: ServerSignalsFetcher? = null
    private var fetcherBaseUrl: String? = null

    suspend fun refresh(
        baseUrl: String,
        requirePersistence: Boolean = true,
        symbols: List<String> = emptyList(),
        reuseWithinMillis: Long = 0,
    ): GuailiResult<ServerSignalsSnapshot> {
        // Cache permission belongs to a caller. Once network work is required, all callers
        // share that actual request, including a caller that disallows completed-cache reuse.
        if (reuseWithinMillis > 0 && symbols.isEmpty()) {
            val cached = snapshotSink.read()
            val device = snapshotSink.currentDeviceTime()
            if (cached != null && cached.fullUniverse && cached.belongsTo(baseUrl) && device != null) {
                val age = assessServerSignalsTime(cached, device).cacheAgeMillis
                if (age != null && age in 0..reuseWithinMillis) return GuailiResult.Success(cached)
            }
        }
        val normalizedUrl = normalizeServerSignalsBaseUrl(baseUrl)
        val requestedSymbols = symbols.toList()
        val outcome = refreshes.run(RefreshKey(normalizedUrl, requestedSymbols,
            RefreshSinkIdentity(snapshotSink.refreshIdentity()))) {
            refreshLocked(normalizedUrl, requestedSymbols)
        }
        return if (requirePersistence && outcome.persistenceFailure != null) {
            GuailiResult.Failure("信号已获取，但无法保存快照，请重试", outcome.persistenceFailure)
        } else outcome.result
    }

    private suspend fun refreshLocked(
        baseUrl: String,
        symbols: List<String>,
    ): RefreshOutcome {
        val current = try {
            if (fetcher == null || fetcherBaseUrl != baseUrl) {
                fetcher = fetcherFactory.create(baseUrl)
                fetcherBaseUrl = baseUrl
            }
            checkNotNull(fetcher)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return RefreshOutcome(failure(baseUrl, "服务器地址无效，请检查信号V2设置", error))
        }
        val started = snapshotSink.currentDeviceTime()
        val result = try {
            current.fetch(symbols)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GuailiResult.Failure("无法获取服务器信号，请检查网络后重试", error)
        }
        return when (result) {
            is GuailiResult.Failure -> RefreshOutcome(failure(baseUrl, result.message, result.cause))
            is GuailiResult.Success -> {
                val received = snapshotSink.currentDeviceTime()
                val clock = if (started == null || received == null) null else GuailiServerClock(
                    serverTimeMillis = result.value.serverTime,
                    receivedElapsedMillis = received.elapsedMillis,
                    bootCount = received.bootCount,
                    requestDurationMillis = if (started.bootCount == received.bootCount) {
                        received.elapsedMillis - started.elapsedMillis
                    } else -1L,
                )
                val snapshot = ServerSignalsSnapshot(
                    response = result.value,
                    updatedAt = nowMillis(),
                    baseUrl = baseUrl,
                    serverClock = clock,
                    fullUniverse = symbols.isEmpty(),
                )
                val persistenceFailure = try {
                    // Disabled/config-error responses are successful observations and must replace
                    // older active signals. Network failure never replaces the successful cache.
                    snapshotSink.save(snapshot)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
                if (persistenceFailure != null) {
                    // Record while this observation still owns the ordering lock. A delayed
                    // strict caller must not write an old error after a newer successful save.
                    failure(baseUrl, "信号已获取，但无法保存快照，请重试", persistenceFailure)
                } else {
                    try {
                        snapshotSink.recordFailure(null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A separate failure-log write cannot invalidate a saved successful snapshot.
                    }
                }
                RefreshOutcome(GuailiResult.Success(snapshot), persistenceFailure)
            }
        }
    }

    private suspend fun failure(baseUrl: String, message: String, cause: Throwable?): GuailiResult.Failure {
        try {
            snapshotSink.recordFailure(ServerSignalsFailure(message, nowMillis(), baseUrl))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // An unavailable failure log must not overwrite or destroy a prior successful cache.
        }
        return GuailiResult.Failure(message, cause)
    }

    private data class RefreshKey(
        val baseUrl: String,
        val symbols: List<String>,
        val sink: RefreshSinkIdentity,
    )

    private data class RefreshOutcome(
        val result: GuailiResult<ServerSignalsSnapshot>,
        val persistenceFailure: Exception? = null,
    )

    companion object {
        private val refreshes = RefreshSingleFlight<RefreshKey, RefreshOutcome>()
    }
}
