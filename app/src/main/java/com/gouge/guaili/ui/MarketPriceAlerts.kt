package com.gouge.guaili.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.PriceAlertDto
import com.gouge.guaili.data.PriceAlertRepository
import com.gouge.guaili.data.PriceAlertResult
import com.gouge.guaili.data.PriceAlertSeenStore
import com.gouge.guaili.data.PriceAlertStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A rearmed alert can trigger again, even when its ID and trigger time are unchanged.
internal fun PriceAlertDto.triggerKey(): String? = if (status == "triggered")
    "$id:$armGeneration:${triggeredAt ?: 0L}" else null

internal data class PeriodPriceAlerts(val activeCount: Int = 0, val newTriggerCount: Int = 0, val waitingCount: Int = 0) {
    val count get() = activeCount + newTriggerCount
    val description get() = listOfNotNull(
        "已设置 $activeCount 条价格警报".takeIf { activeCount > 0 },
        "$waitingCount 条暂未监测".takeIf { waitingCount > 0 },
        "新触发 $newTriggerCount 条".takeIf { newTriggerCount > 0 },
    ).joinToString("，")
}

internal data class SymbolPriceAlerts(val total: Int, val periods: Map<String, PeriodPriceAlerts>) {
    val newTriggerCount get() = periods.values.sumOf { it.newTriggerCount }
}

internal data class MarketPriceAlertsState(
    val alerts: List<PriceAlertDto> = emptyList(),
    val seenTriggers: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val seenError: String? = null,
    val now: Long = System.currentTimeMillis(),
) {
    fun summaries(): Map<String, SymbolPriceAlerts> = alerts.groupBy { it.symbol }.mapValues { (_, alerts) ->
        val periods = alerts.groupBy { it.interval }.mapValues { (_, group) ->
            val active = group.filter { it.status == "active" && (it.expiresAt == null || it.expiresAt > now) }
            PeriodPriceAlerts(active.size, group.count { it.triggerKey()?.let { key -> key !in seenTriggers } == true },
                active.count { it.dataStatus != "live" })
        }.filterValues { it.count > 0 }
        SymbolPriceAlerts(alerts.size, periods)
    }

    fun preferredAlert(symbol: String, interval: String): PriceAlertDto? = alerts
        .filter { it.symbol == symbol && it.interval == interval }
        .sortedWith(compareByDescending<PriceAlertDto> { it.triggerKey()?.let { key -> key !in seenTriggers } == true }
            .thenByDescending { it.status == "active" && (it.expiresAt == null || it.expiresAt > now) }
            .thenByDescending { it.triggeredAt ?: 0L }.thenByDescending { it.id })
        .firstOrNull()
}

/** One read-only request for the entire matrix; no per-cell polling or server mutations. */
internal class MarketPriceAlertsViewModel(
    private val load: suspend () -> PriceAlertResult<List<PriceAlertDto>>,
    private val seenStore: PriceAlertSeenStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutable = MutableStateFlow(MarketPriceAlertsState(seenTriggers = seenStore.readSeenTriggers(), now = nowMillis()))
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private val seenWriter = Mutex()
    private val saved = mutableMapOf<Long, com.gouge.guaili.data.PriceAlertDto?>()
    private var changeGeneration = 0L
    private var boundaryJob: Job? = null
    private var foreground = false
    private var refreshAgain = false

    fun accept(confirmation: PriceAlertConfirmation) {
        val id = confirmation.alert?.id ?: confirmation.operation.alertId ?: return
        val alert = confirmation.alert
        changeGeneration++
        saved[id] = alert
        val previous = mutable.value.alerts.firstOrNull { it.id == id }
        if (alert == null || previous == null || alert.revision >= previous.revision) {
            mutable.value = mutable.value.copy(alerts = mutable.value.alerts.filterNot { it.id == id } + listOfNotNull(alert), now = nowMillis())
        }
        if (foreground) {
            if (refreshJob?.isActive == true) refreshAgain = true else refresh()
        }
        scheduleBoundary()
    }
    private fun scheduleBoundary() {
        boundaryJob?.cancel()
        if (!foreground) return
        val now = nowMillis()
        val next = mutable.value.alerts.asSequence().filter { it.status == "active" }.flatMap {
            sequenceOf(it.expiresAt, it.geometry.first.timeMs.takeIf { _ -> it.geometry.extend != "both" },
                it.geometry.second.timeMs.takeIf { _ -> it.geometry.extend == "none" }?.let { end -> if (end < Long.MAX_VALUE) end + 1 else end })
        }.filterNotNull().filter { it > now }.minOrNull() ?: return
        boundaryJob = viewModelScope.launch {
            delay((next - now).coerceAtMost(86_400_000))
            mutable.value = mutable.value.copy(now = nowMillis())
            scheduleBoundary()
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        pollJob?.cancel()
        pollJob = null
        if (!active) { refreshJob?.cancel(); boundaryJob?.cancel(); mutable.value = mutable.value.copy(loading = false); return }
        scheduleBoundary()
        pollJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(10_000)
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val generation = changeGeneration
            mutable.value = mutable.value.copy(loading = true, now = nowMillis())
            try {
                when (val result = load()) {
                    is PriceAlertResult.Success -> {
                        val records = result.value.associateBy { it.id }.toMutableMap()
                        val iterator = saved.iterator()
                        while (iterator.hasNext()) {
                            val (id, alert) = iterator.next()
                            val incoming = records[id]
                            if (alert == null) records.remove(id)
                            else if (incoming != null && incoming.revision < alert.revision || incoming == null && generation != changeGeneration) records[id] = alert
                            if (generation == changeGeneration && (alert == null || incoming == null || incoming.revision >= alert.revision)) iterator.remove()
                        }
                        mutable.value = mutable.value.copy(alerts = records.values.toList(), loading = false, error = null, now = nowMillis())
                        scheduleBoundary()
                    }
                    is PriceAlertResult.Failure -> mutable.value = mutable.value.copy(
                        loading = false, error = "价格警报状态更新失败，显示上次结果。${result.message}", now = nowMillis())
                }
            } finally {
                if (refreshJob === coroutineContext[Job]) {
                    mutable.value = mutable.value.copy(loading = false)
                    refreshJob = null
                    if (foreground && refreshAgain) { refreshAgain = false; refresh() }
                }
            }
        }
    }

    fun acknowledge(alerts: List<PriceAlertDto>) {
        val keys = alerts.mapNotNull { it.triggerKey() }.toSet()
        if (keys.all { it in mutable.value.seenTriggers }) return
        viewModelScope.launch {
            seenWriter.withLock {
                val next = mutable.value.seenTriggers + keys
                try {
                    seenStore.saveSeenTriggers(next)
                    mutable.value = mutable.value.copy(seenTriggers = next, seenError = null)
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) {
                    mutable.value = mutable.value.copy(seenError = "无法保存已查看状态，请重新打开警报列表重试")
                }
            }
        }
    }

    companion object {
        fun factory(context: Context, baseUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = PriceAlertRepository.create(baseUrl)
                return MarketPriceAlertsViewModel(repository::listAll, PriceAlertStore(context, baseUrl)) as T
            }
        }
    }
}
