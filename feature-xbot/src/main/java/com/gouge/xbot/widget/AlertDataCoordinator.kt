package com.gouge.xbot.widget

import android.content.Context
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.XbotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

class AlertDataCoordinator(context: Context) {
    private val context = context.applicationContext
    private val store = AlertWidgetStore(this.context)
    private val visibility = AlertVisibilityStore(this.context)
    val snapshots = revision.map { store.snapshot() }

    suspend fun refresh(initializeHomeSelection: Boolean = false): AlertSnapshot = lock.withLock {
        val scope = store.currentScope() ?: throw IllegalStateException("请先登录")
        val session = SessionStore(context)
        val generation = session.generation()
        val repository = XbotRepository(ServerConfigStore(context), session).forSession(generation)
        try {
            val configs = repository.getTvAlertConfigs()
            if (scope != store.currentScope()) throw CancellationException("账户已切换")
            val ids = if (initializeHomeSelection) visibility.resolveVisibleIds(configs) else visibility.getVisibleIds()
            val cookieIds = configs.filter { it.id in ids }.mapTo(hashSetOf()) { it.cookieId }
            val fetched = repository.getTvAlerts(cookieIds)
            if (scope != store.currentScope()) throw CancellationException("账户已切换")
            val snapshot = AlertSnapshot(
                configs = configs,
                alertsByCookieId = store.snapshot()?.alertsByCookieId.orEmpty() + fetched,
                updatedAtMillis = System.currentTimeMillis(),
            )
            store.saveSnapshot(scope, snapshot)
            changed()
            snapshot
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (scope == store.currentScope()) {
                if (error is HttpException && error.code() == 401) {
                    session.withCurrentGeneration(generation) {
                        session.clear()
                        store.clearSnapshot()
                    }
                } else {
                    store.saveSnapshot(scope, (store.snapshot() ?: AlertSnapshot()).copy(error = "刷新失败，保留上次数据"))
                }
                changed()
            }
            throw error
        }
    }

    fun currentScope(): String? = store.currentScope()

    suspend fun removeAlert(cookieId: String, alertId: Long, expectedScope: String? = store.currentScope()) = lock.withLock {
        val scope = store.currentScope() ?: return@withLock
        if (scope != expectedScope) return@withLock
        val snapshot = store.snapshot() ?: return@withLock
        store.saveSnapshot(scope, snapshot.copy(alertsByCookieId = snapshot.alertsByCookieId +
            (cookieId to snapshot.alertsByCookieId[cookieId].orEmpty().filterNot { it.alertId == alertId })))
        changed()
    }

    suspend fun replaceAccount(cookieId: String, alerts: List<TvAlertDto>, expectedScope: String? = store.currentScope()) = lock.withLock {
        val scope = store.currentScope() ?: return@withLock
        if (scope != expectedScope) return@withLock
        val snapshot = store.snapshot() ?: return@withLock
        store.saveSnapshot(scope, snapshot.copy(alertsByCookieId = snapshot.alertsByCookieId + (cookieId to alerts)))
        changed()
    }

    fun changed() {
        revision.update { it + 1 }
        runCatching { AlertWidgetRenderer.renderAll(context) }
            .onFailure { android.util.Log.w("AlertWidget", "Widget rendering failed", it) }
    }

    companion object {
        private val lock = Mutex()
        private val revision = MutableStateFlow(0L)
    }
}
