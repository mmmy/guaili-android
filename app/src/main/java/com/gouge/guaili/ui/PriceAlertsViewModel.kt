package com.gouge.guaili.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

data class PendingPriceAlert(val operation: PriceAlertOperation, val phase: String = "saving", val error: String? = null) {
    val geometry: PriceAlertGeometry? get() = operation.queuedGeometry ?: operation.body["geometry"]?.let {
        runCatching { PriceAlertRepository.json.decodeFromJsonElement<PriceAlertGeometry>(it) }.getOrNull()
    }
    fun label(): String = when (phase) { "saving" -> "正在保存"; "retrying" -> "正在读取最新配置"; "uncertain" -> "保存结果待确认"; else -> "保存失败" }
}
data class PriceAlertHistory(val events: List<PriceAlertEvent> = emptyList(), val busy: Boolean = false, val more: Boolean = false, val error: String? = null)
data class PriceAlertsState(
    val alerts: List<PriceAlertDto> = emptyList(),
    val markets: Map<String, PriceAlertMarket> = emptyMap(),
    val pending: Map<Long, PendingPriceAlert> = emptyMap(),
    val histories: Map<Long, PriceAlertHistory> = emptyMap(),
    val undo: Map<Long, PriceAlertGeometry> = emptyMap(),
    val error: String? = null,
    val notice: String? = null,
    val loading: Boolean = false,
)

/** Each alert has one immutable in-flight request and at most one newer geometry. */
class PriceAlertsViewModel(private val api: PriceAlertGateway, private val journal: PriceAlertJournal) : ViewModel() {
    private val mutable = MutableStateFlow(PriceAlertsState(pending = journal.read().associate {
        it.key to if (it.requiresUserRetry) PendingPriceAlert(it, "failed", "上次修改未保存，可重试或放弃") else PendingPriceAlert(it, "uncertain")
    }))
    val state = mutable.asStateFlow()
    private val writers = Mutex()
    private val network = Semaphore(4)
    private val jobs = mutableMapOf<Long, Job>()
    private val deleted = mutableSetOf<Long>()
    private var loadJob: Job? = null
    private var loadSymbol = ""
    private val historyJobs = mutableMapOf<Long, Job>()

    fun refresh(symbol: String) {
        if (loadJob?.isActive == true && loadSymbol == symbol) return
        loadJob?.cancel()
        loadSymbol = symbol
        loadJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true)
            when (val result = api.list(symbol)) {
                is PriceAlertResult.Success -> {
                    val current = mutable.value.alerts.associateBy { it.id }
                    val merged = result.value.filterNot { it.id in deleted }.map { incoming ->
                        current[incoming.id]?.takeIf { it.revision > incoming.revision } ?: incoming
                    }
                    mutable.value = mutable.value.copy(alerts = (mutable.value.alerts.filterNot { it.symbol == symbol } + merged).sortedByDescending { it.id }, loading = false, error = null)
                }
                is PriceAlertResult.Failure -> mutable.value = mutable.value.copy(loading = false, error = result.message)
            }
            when (val result = api.market(symbol)) {
                is PriceAlertResult.Success -> mutable.value = mutable.value.copy(markets = mutable.value.markets + (symbol to result.value))
                is PriceAlertResult.Failure -> mutable.value = mutable.value.copy(markets = mutable.value.markets - symbol)
            }
            mutable.value.pending.filterValues { it.phase == "uncertain" }.keys.forEach { run(it, recovering = true) }
        }
    }
    fun clearNotice() { mutable.value = mutable.value.copy(notice = null) }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    fun create(body: JsonObject) {
        if (0L in mutable.value.pending) return
        enqueue(PriceAlertOperation("create", newAlertMutationId(), body = body))
    }
    fun edit(alert: PriceAlertDto, fields: JsonObject) {
        if (alert.id in mutable.value.pending) return
        enqueue(PriceAlertOperation("patch", newAlertMutationId(), alert.id, alert.revision, fields, alert.geometry))
    }
    fun move(id: Long, geometry: PriceAlertGeometry) {
        if (!geometry.valid()) return
        val alert = mutable.value.alerts.firstOrNull { it.id == id } ?: return
        val pending = mutable.value.pending[id]
        if (pending != null) {
            if (pending.operation.method != "patch" || "geometry" !in pending.operation.body) return
            mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to pending.copy(operation = pending.operation.copy(queuedGeometry = geometry))))
            persist()
        } else if (geometry != alert.geometry) {
            edit(alert, buildJsonObject { put("geometry", PriceAlertRepository.json.encodeToJsonElement(geometry)) })
        }
    }
    fun toggle(alert: PriceAlertDto) = edit(alert, buildJsonObject {
        put("status", if (alert.status == "active") "disabled" else "active")
    })
    fun delete(alert: PriceAlertDto) {
        if (alert.id in mutable.value.pending) return
        enqueue(PriceAlertOperation("delete", newAlertMutationId(), alert.id, alert.revision, buildJsonObject {}))
    }
    fun undo(id: Long) {
        val geometry = mutable.value.undo[id] ?: return
        val alert = mutable.value.alerts.firstOrNull { it.id == id } ?: return
        if (id in mutable.value.pending) return
        enqueue(PriceAlertOperation("patch", newAlertMutationId(), id, alert.revision,
            buildJsonObject { put("geometry", PriceAlertRepository.json.encodeToJsonElement(geometry)) }, alert.geometry, isUndo = true))
    }

    fun retry(id: Long) {
        val pending = mutable.value.pending[id] ?: return
        if (jobs[id]?.isActive == true) return
        if (pending.phase != "failed") { run(id, recovering = true); return }
        mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to pending.copy(phase = "retrying", error = null)))
        val retryJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val alert = if (id != 0L) (api.get(id) as? PriceAlertResult.Success)?.value else null
                val current = mutable.value.pending[id]
                if (current?.operation?.mutationId != pending.operation.mutationId || current.phase != "retrying") return@launch
                if (id != 0L && alert == null) {
                    mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to current.copy(phase = "failed", error = "无法读取最新警报，请稍后重试")))
                    return@launch
                }
                if (alert != null) accept(alert)
                val op = current.operation.copy(mutationId = newAlertMutationId(), expectedRevision = alert?.revision ?: 0,
                    body = current.operation.queuedGeometry?.let { g -> JsonObject(current.operation.body + ("geometry" to PriceAlertRepository.json.encodeToJsonElement(g))) } ?: current.operation.body,
                    queuedGeometry = null, previousGeometry = alert?.geometry)
                enqueue(op)
            } finally {
                if (jobs[id] === coroutineContext[Job]) jobs.remove(id)
                if (mutable.value.pending[id]?.phase == "saving") run(id)
            }
        }
        jobs[id] = retryJob
        retryJob.start()
    }
    fun discardFailed(id: Long) {
        val pending = mutable.value.pending[id] ?: return
        if (pending.phase !in listOf("failed", "retrying")) return
        jobs[id]?.cancel()
        mutable.value = mutable.value.copy(pending = mutable.value.pending - id)
        persist()
    }
    private fun enqueue(raw: PriceAlertOperation) {
        val body = buildJsonObject {
            raw.body.forEach { (key, value) -> if (key !in listOf("mutationId", "expectedRevision")) put(key, value) }
            put("mutationId", raw.mutationId)
            if (raw.method != "create") put("expectedRevision", raw.expectedRevision)
        }
        val op = raw.copy(body = body, requiresUserRetry = false)
        mutable.value = mutable.value.copy(pending = mutable.value.pending + (op.key to PendingPriceAlert(op)), error = null)
        run(op.key)
    }
    private suspend fun saveJournal() = writers.withLock { journal.write(mutable.value.pending.values.map { it.operation }) }
    private fun persist() { viewModelScope.launch { runCatching { saveJournal() }.onFailure { mutable.value = mutable.value.copy(error = "无法保存操作记录") } } }
    private fun run(id: Long, recovering: Boolean = false) {
        if (jobs[id]?.isActive == true) return
        val operationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                saveJournal() // Persist the exact request before any network write.
                while (true) {
                    val pending = mutable.value.pending[id] ?: break
                    mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to pending.copy(phase = "saving", error = null)))
                    val op = pending.operation
                    val result = network.withPermit {
                        var response = if (recovering) api.recover(op.mutationId) else api.send(op)
                        if (recovering && response is PriceAlertResult.Failure && response.code == 404) response = api.send(op)
                        if (response is PriceAlertResult.Failure && response.uncertain || response is PriceAlertResult.Success && !validResult(op, response.value)) {
                            response = api.recover(op.mutationId)
                            if (response is PriceAlertResult.Failure && response.code == 404) response = api.send(op)
                            if (response is PriceAlertResult.Success && !validResult(op, response.value)) response = PriceAlertResult.Failure("服务器返回内容无法确认，需核对保存结果")
                        }
                        response
                    }
                    if (result is PriceAlertResult.Failure) {
                        val latest = mutable.value.pending[id] ?: break
                        mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to latest.copy(
                            operation = latest.operation.copy(requiresUserRetry = !result.uncertain),
                            phase = if (result.uncertain) "uncertain" else "failed", error = result.message)))
                        saveJournal()
                        if (result.code == 409 && id != 0L) (api.get(id) as? PriceAlertResult.Success)?.value?.let(::accept)
                        break
                    }
                    result as PriceAlertResult.Success
                    val latest = mutable.value.pending[id] ?: break
                    if (op.method == "delete") {
                        deleted += id
                        mutable.value = mutable.value.copy(alerts = mutable.value.alerts.filterNot { it.id == id }, pending = mutable.value.pending - id, notice = "警报已删除，历史记录仍保留")
                        saveJournal(); break
                    }
                    val confirmed = PriceAlertRepository.json.decodeFromJsonElement<PriceAlertDto>(result.value)
                    accept(confirmed)
                    val undo = if (op.isUndo) mutable.value.undo - confirmed.id else if (op.previousGeometry != null && confirmed.geometry != op.previousGeometry)
                        mutable.value.undo + (confirmed.id to op.previousGeometry) else mutable.value.undo
                    val queued = latest.operation.queuedGeometry
                    mutable.value = mutable.value.copy(pending = mutable.value.pending - id, undo = undo,
                        notice = if (id == 0L) "警报已保存" else if (op.previousGeometry != confirmed.geometry && confirmed.status == "active") "已保存并重新布防" else "已保存 · ${confirmed.stateLabel()}")
                    if (queued != null && queued != confirmed.geometry) {
                        val next = PriceAlertOperation("patch", newAlertMutationId(), confirmed.id, confirmed.revision,
                            buildJsonObject {}, confirmed.geometry)
                        val nextBody = buildJsonObject { put("mutationId", next.mutationId); put("expectedRevision", confirmed.revision); put("geometry", PriceAlertRepository.json.encodeToJsonElement(queued)) }
                        mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to PendingPriceAlert(next.copy(body = nextBody))))
                        saveJournal()
                    } else { saveJournal(); break }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
                val p = mutable.value.pending[id]
                if (p != null) mutable.value = mutable.value.copy(pending = mutable.value.pending + (id to p.copy(phase = "uncertain", error = "操作记录暂不可用，请刷新核对保存结果")))
            } finally {
                if (jobs[id] === coroutineContext[Job]) jobs.remove(id)
                // The acknowledgement can become visible while journal cleanup is still suspended.
                // Drain an action queued by the user in that interval instead of leaving it unsent.
                if (mutable.value.pending[id]?.phase == "saving") run(id)
            }
        }
        jobs[id] = operationJob
        operationJob.start()
    }
    private fun validResult(op: PriceAlertOperation, value: JsonElement): Boolean = runCatching {
        if (op.method == "delete") {
            val obj = value.jsonObject
            obj["deleted"]?.jsonPrimitive?.boolean == true && obj["id"]?.jsonPrimitive?.long == op.alertId &&
                (obj["revision"]?.jsonPrimitive?.long ?: 0) > op.expectedRevision
        } else {
            val alert = PriceAlertRepository.json.decodeFromJsonElement<PriceAlertDto>(value)
            alert.id > 0 && alert.geometry.valid() && alert.revision > op.expectedRevision &&
                (op.alertId == null || alert.id == op.alertId)
        }
    }.getOrDefault(false)
    private fun accept(alert: PriceAlertDto) {
        val prior = mutable.value.alerts.firstOrNull { it.id == alert.id }
        if (prior != null && prior.revision > alert.revision) return
        mutable.value = mutable.value.copy(alerts = (mutable.value.alerts.filterNot { it.id == alert.id } + alert).sortedByDescending { it.id })
    }
    fun history(id: Long, older: Boolean = false) {
        if (historyJobs[id]?.isActive == true) return
        historyJobs[id] = viewModelScope.launch {
            val previous = mutable.value.histories[id] ?: PriceAlertHistory()
            mutable.value = mutable.value.copy(histories = mutable.value.histories + (id to previous.copy(busy = true, error = null)))
            when (val result = api.events(id, if (older) previous.events.lastOrNull()?.armGeneration else null)) {
                is PriceAlertResult.Success -> mutable.value = mutable.value.copy(histories = mutable.value.histories + (id to PriceAlertHistory(
                    events = result.value, more = result.value.size == 200)))
                is PriceAlertResult.Failure -> mutable.value = mutable.value.copy(histories = mutable.value.histories + (id to previous.copy(busy = false, error = result.message)))
            }
        }
    }
    companion object {
        fun factory(context: Context, url: String) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(PriceAlertsViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return PriceAlertsViewModel(PriceAlertRepository.create(url), PriceAlertStore(context, url)) as T
            }
        }
    }
}
