package com.gouge.xbot.widget

import android.content.Context
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.XbotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class AlertDataCoordinator(context: Context,
    private val publishWidgets: suspend (Context) -> Unit = { AlertWidgetRenderer.requestRenderAll(it).await() },
) {
    private val context = context.applicationContext
    private val store = AlertWidgetStore(this.context)
    private val visibility = AlertVisibilityStore(this.context)
    val observations = revision.map { store.observation() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    // Existing presentation-only consumers may keep reading payloads; session UI uses observations.
    val snapshots = observations.map { it.snapshot }.distinctUntilChanged()

    suspend fun refresh(initializeHomeSelection: Boolean = false): AlertSnapshot = withContext(Dispatchers.IO) {
        val scope = store.currentScope() ?: throw IllegalStateException("请先登录")
        val session = SessionStore(context)
        val generation = session.generation()
        val key = RefreshKey(scope, generation, initializeHomeSelection, visibility.getVisibleIds())
        refreshes.run(key) { refreshCurrent(key, session) }
    }

    private suspend fun refreshCurrent(key: RefreshKey, session: SessionStore): AlertSnapshot {
        val scope = key.scope
        requireCurrent(key, session)
        val startedAtRevision = lock.withLock { editsFor(scope).beginRefresh() }
        val repository = XbotRepository(ServerConfigStore(context), session).forSession(key.generation)
        try {
            val configs = repository.getTvAlertConfigs()
            requireCurrent(key, session)
            var ids = key.visibleIds
            if (key.initializeHomeSelection) {
                if (!session.withCurrentScope(scope) { ids = visibility.resolveVisibleIds(configs) }) {
                    throw CancellationException("账户已切换")
                }
            }
            val cookieIds = configs.filter { it.id in ids }.mapTo(hashSetOf()) { it.cookieId }
            val fetched = repository.getTvAlerts(cookieIds)
            val snapshot = lock.withLock {
                requireCurrent(key, session)
                val snapshot = AlertSnapshot(
                    configs = configs,
                    // A delete/reset that completed while the list was in flight wins for its account.
                    alertsByCookieId = editsFor(scope).merge(store.snapshot()?.alertsByCookieId.orEmpty(), fetched, startedAtRevision),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                if (!store.saveSnapshot(scope, snapshot, expectedGeneration = key.generation)) throw CancellationException("账户已切换")
                snapshot
            }
            changedAndAwaitRender()
            return snapshot
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            var publish = false
            lock.withLock {
                if (scope == store.currentScope() && key.generation == session.generation()) {
                    if (error is HttpException && error.code() == 401) {
                        session.withCurrentGeneration(key.generation) {
                            session.clear()
                            store.clearSnapshot()
                        }
                    } else {
                        store.saveSnapshot(scope, (store.snapshot() ?: AlertSnapshot()).copy(error = "刷新失败，保留上次数据"),
                            expectedGeneration = key.generation)
                    }
                    publish = true
                }
            }
            if (publish) changedAndAwaitRender()
            throw error
        }
    }

    private fun requireCurrent(key: RefreshKey, session: SessionStore) {
        if (key.scope != store.currentScope() || key.generation != session.generation()) throw CancellationException("账户已切换")
    }

    fun currentScope(): String? = store.currentScope()

    suspend fun removeAlert(cookieId: String, alertId: Long, expectedScope: String? = store.currentScope(),
        expectedGeneration: Long = SessionStore(context).generation()) = withContext(Dispatchers.IO) {
        val changed = lock.withLock {
            if (SessionStore(context).generation() != expectedGeneration) return@withLock false
            val scope = store.currentScope() ?: return@withLock false
            if (scope != expectedScope) return@withLock false
            val snapshot = store.snapshot() ?: return@withLock false
            val alerts = snapshot.alertsByCookieId[cookieId].orEmpty().filterNot { it.alertId == alertId }
            // Even an already-absent ID must not be revived by a list requested before this delete.
            editsFor(scope).changed(cookieId)
            if (snapshot.alertsByCookieId[cookieId].orEmpty() == alerts) return@withLock false
            store.saveSnapshot(scope, snapshot.copy(alertsByCookieId = snapshot.alertsByCookieId + (cookieId to alerts)),
                expectedGeneration = expectedGeneration)
        }
        if (changed) changedAndAwaitRender()
    }

    suspend fun replaceAccount(cookieId: String, alerts: List<TvAlertDto>, expectedScope: String? = store.currentScope(),
        expectedGeneration: Long = SessionStore(context).generation()) = withContext(Dispatchers.IO) {
        val changed = lock.withLock {
            if (SessionStore(context).generation() != expectedGeneration) return@withLock false
            val scope = store.currentScope() ?: return@withLock false
            if (scope != expectedScope) return@withLock false
            val snapshot = store.snapshot() ?: return@withLock false
            editsFor(scope).changed(cookieId)
            if (snapshot.alertsByCookieId[cookieId] == alerts) return@withLock false
            store.saveSnapshot(scope, snapshot.copy(alertsByCookieId = snapshot.alertsByCookieId + (cookieId to alerts)),
                expectedGeneration = expectedGeneration)
        }
        if (changed) changedAndAwaitRender()
    }

    fun changed() {
        revision.update { it + 1 }
        AlertWidgetRenderer.requestRenderAll(context)
    }

    internal suspend fun changedAndAwaitRender() {
        revision.update { it + 1 }
        // Workers finish only after the latest queued views were published; UI callers remain main-safe.
        publishWidgets(context)
    }

    private data class RefreshKey(val scope: String, val generation: Long, val initializeHomeSelection: Boolean, val visibleIds: Set<String>)

    companion object {
        private val lock = Mutex()
        private val revision = MutableStateFlow(0L)
        private val refreshes = AlertRefreshFlights<RefreshKey, AlertSnapshot>()
        private var editsScope: String? = null
        private var edits = AlertSnapshotEdits()

        private fun editsFor(scope: String): AlertSnapshotEdits {
            if (editsScope != scope) { editsScope = scope; edits = AlertSnapshotEdits() }
            return edits
        }
    }
}
