package com.gouge.guaili.data

import com.gouge.guaili.domain.toTable
import com.gouge.guaili.settings.GuailiSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun refresh(settings: GuailiSettings): GuailiResult<GuailiSnapshot> =
        RefreshCoordinator.mutex.withLock {
            refreshLocked(settings)
        }

    private suspend fun refreshLocked(settings: GuailiSettings): GuailiResult<GuailiSnapshot> {
        val currentFetcher = try {
            fetcherFor(settings.baseUrl)
        } catch (error: Exception) {
            return GuailiResult.Failure(error.message ?: "Invalid base URL", error)
        }

        return when (val result = currentFetcher.fetch(settings)) {
            is GuailiResult.Failure -> result
            is GuailiResult.Success -> {
                val snapshot = GuailiSnapshot(
                    table = result.value.toTable(settings.symbols, settings.intervals),
                    updatedAt = nowMillis(),
                )
                snapshotSink.saveIgnoringStorageFailure(snapshot)
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
