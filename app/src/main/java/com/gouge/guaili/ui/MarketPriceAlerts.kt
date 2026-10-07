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

    fun setForeground(active: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (!active) { refreshJob?.cancel(); mutable.value = mutable.value.copy(loading = false); return }
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
            mutable.value = mutable.value.copy(loading = true, now = nowMillis())
            try {
                when (val result = load()) {
                    is PriceAlertResult.Success -> mutable.value = mutable.value.copy(
                        alerts = result.value, loading = false, error = null, now = nowMillis())
                    is PriceAlertResult.Failure -> mutable.value = mutable.value.copy(
                        loading = false, error = "价格警报状态更新失败，显示上次结果。${result.message}", now = nowMillis())
                }
            } finally {
                mutable.value = mutable.value.copy(loading = false)
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
