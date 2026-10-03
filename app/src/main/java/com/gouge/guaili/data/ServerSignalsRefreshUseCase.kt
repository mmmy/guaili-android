package com.gouge.guaili.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val snapshotSink: ServerSignalsSnapshotSink = ServerSignalsSnapshotSink {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var fetcher: ServerSignalsFetcher? = null
    private var fetcherBaseUrl: String? = null

    suspend fun refresh(
        baseUrl: String,
        requirePersistence: Boolean = true,
        symbols: List<String> = emptyList(),
    ): GuailiResult<ServerSignalsSnapshot> = RefreshCoordinator.mutex.withLock {
        refreshLocked(normalizeServerSignalsBaseUrl(baseUrl), requirePersistence, symbols)
    }

    private suspend fun refreshLocked(
        baseUrl: String,
        requirePersistence: Boolean,
        symbols: List<String>,
    ): GuailiResult<ServerSignalsSnapshot> {
        val current = try {
            if (fetcher == null || fetcherBaseUrl != baseUrl) {
                fetcher = fetcherFactory.create(baseUrl)
                fetcherBaseUrl = baseUrl
            }
            checkNotNull(fetcher)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure(baseUrl, "服务器地址无效，请检查信号V2设置", error)
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
            is GuailiResult.Failure -> failure(baseUrl, result.message, result.cause)
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
                )
                try {
                    // Disabled/config-error responses are successful observations and must replace
                    // older active signals. Network failure never replaces the successful cache.
                    snapshotSink.save(snapshot)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (requirePersistence) {
                        return failure(baseUrl, "信号已获取，但无法保存到小组件，请重试", error)
                    }
                }
                try {
                    snapshotSink.recordFailure(null)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A separate failure-log write cannot invalidate a saved successful snapshot.
                }
                GuailiResult.Success(snapshot)
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

    private object RefreshCoordinator {
        // App, manual widget actions and background work use different use-case instances.
        val mutex = Mutex()
    }
}
