package com.gouge.guaili.ui

import com.gouge.guaili.domain.guailiIntervalDurationMillis
import com.gouge.guaili.settings.MarketView
import com.gouge.guaili.signals.ServerSignalItem
import com.gouge.guaili.signals.serverSignalKind
import com.gouge.guaili.signals.serverSignalsCacheLifetime
import kotlinx.serialization.Serializable

@Serializable
internal data class MarketLinkKey(val symbol: String, val id: String)

internal data class LiveMarketLink(
    val key: MarketLinkKey,
    val item: ServerSignalItem?,
    val members: Map<String, String>,
    val message: String,
)

internal data class MarketSignalSummary(val items: List<ServerSignalItem>, val status: String)

internal fun signalSummaryLabel(item: ServerSignalItem): String {
    val signal = item.signal
    val kind = when (signal.kind) {
        "extreme" -> if (signal.direction == "positive") "共+" else "共−"
        "compression" -> "近"
        "conflict" -> "分"
        else -> "信号"
    }
    return "$kind${signal.totalLevelCount}级"
}

/** A selection is an identity, never a saved response. Only current eligible cards supply members. */
internal fun resolveLiveMarketLink(key: MarketLinkKey?, state: MarketSignalsUiState): LiveMarketLink? {
    key ?: return null
    val item = state.presentation.signals.firstOrNull { it.symbol == key.symbol && it.signal.id == key.id }
    val members = buildMap {
        item?.signal?.runs?.forEachIndexed { index, run ->
            val label = if (item.signal.kind != "conflict") "" else
                (if (index == 0) "短" else "长") + (if (run.direction == "positive") "+" else "−")
            run.intervals.forEach { put(it, label) }
        }
    }
    val raw = state.snapshot?.response?.results?.firstOrNull { it.symbol == key.symbol }
        ?.signals?.firstOrNull { it.id == key.id }
    val message = when {
        item != null -> signalSummaryLabel(item)
        key.symbol !in state.symbols -> "所选品种已被筛选隐藏"
        raw != null && serverSignalKind(raw.kind) !in state.preferences.kinds -> "所选信号类型已关闭"
        state.presentation.status !in setOf("ready", "degraded") -> state.presentation.message
        raw == null -> "所选信号已不在当前结果"
        else -> "所选信号当前不可展示"
    }
    return LiveMarketLink(key, item, members, message)
}

internal fun marketSignalSummaries(state: MarketSignalsUiState, symbols: List<String>): Map<String, MarketSignalSummary> {
    val grouped = state.presentation.signals.groupBy { it.symbol }
    return symbols.associateWith { symbol ->
        val row = state.snapshot?.response?.results?.firstOrNull { it.symbol == symbol }
        val status = when {
            symbol !in state.symbols -> "未监控"
            state.preferences.kinds.isEmpty() -> "类型已关闭"
            state.presentation.status !in setOf("ready", "degraded") -> state.presentation.message
            row == null -> "服务器未计算"
            state.presentation.nowMillis != null && row.sampledAt > 0 &&
                state.presentation.nowMillis - row.sampledAt > serverSignalsCacheLifetime(state.snapshot!!.response) -> "采样过期"
            row.dataStatus == "stale" -> "数据过期"
            row.dataStatus == "recovering" -> "恢复中"
            row.dataStatus == "warming_up" -> "预热中"
            row.dataStatus == "degraded" -> "部分周期不可用"
            else -> "无可见信号"
        }
        MarketSignalSummary(grouped[symbol].orEmpty(), status)
    }
}

internal fun linkedVisibleIntervals(visible: List<String>, available: List<String>, members: Set<String>, reveal: Boolean): List<String> =
    if (!reveal) visible else (visible + available.filter { it in members }).distinct()
        .sortedByDescending(::guailiIntervalDurationMillis)

@Serializable
internal data class MarketScrollAnchor(val key: String? = null, val index: Int = 0, val offset: Int = 0)

@Serializable
internal data class MarketLinkOrigin(
    val view: MarketView,
    val selection: MarketLinkKey?,
    val reveal: Boolean,
    val scopeSymbol: String?,
    val scopeInterval: String?,
    val narrowPaneOpen: Boolean,
    val intervalGroup: String,
    val layoutMode: String,
    val cellSymbol: String? = null,
    val cellInterval: String? = null,
    val table: MarketScrollAnchor,
    val groups: MarketScrollAnchor,
    val signals: MarketScrollAnchor,
    val pane: MarketScrollAnchor,
    val horizontalInterval: String?,
    val horizontalOffset: Int,
    val onlySignalCells: Boolean = false,
    val focus: MarketScrollAnchor = MarketScrollAnchor(),
    val cellFromSignals: Boolean = false,
)

@Serializable
internal data class MarketLinkSession(
    val baseUrl: String = "",
    val selection: MarketLinkKey? = null,
    val viewOverride: MarketView? = null,
    val reveal: Boolean = false,
    val scopeSymbol: String? = null,
    val scopeInterval: String? = null,
    val narrowPaneOpen: Boolean = false,
    val locationRequest: Int = 0,
    val origins: List<MarketLinkOrigin> = emptyList(),
) {
    fun push(origin: MarketLinkOrigin) = copy(origins = (origins + origin).takeLast(8))
}
