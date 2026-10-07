package com.gouge.guaili.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gouge.guaili.signals.serverSignalQualityLines
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun marketDataStatusLabel(table: GuailiTableState, signals: MarketSignalsUiState, onlySignals: Boolean): String {
    if (!onlySignals) return when {
        table.isLoading -> "Loading"
        table.isStale -> "Stale"
        table.errorMessage != null -> "Offline"
        table.isRefreshing -> "刷新中"
        table.lastUpdatedAt == null -> "Waiting"
        else -> "Live"
    }
    return when (signals.presentation.status) {
        "ready" -> if (signals.refreshError != null) "更新失败" else if (signals.isRefreshing) "刷新中" else "Live"
        "degraded" -> signals.presentation.compactQualityLabel ?: "部分可用"
        "stale" -> "已过期"
        "disabled" -> "已关闭"
        "warming_up" -> "预热中"
        "time_uncertain" -> "待校准"
        "config_error" -> "配置有误"
        else -> if (signals.refreshError != null) "连接失败" else "等待数据"
    }
}

internal fun signalFocusSourceLabel(signals: MarketSignalsUiState): String {
    val config = signals.snapshot?.response?.indicatorConfig
    return "V2 · 动态K · " + (config?.let { "${it.maType}${it.maLength}" } ?: "均线?")
}

internal fun signalFocusCellContext(signals: MarketSignalsUiState): String =
    signalFocusSourceLabel(signals) + " · 采样 ${marketDataTime(signals.presentation.sampledAt)} · 当前K均线方向"

internal fun marketDataTime(time: Long?): String = time?.let {
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
} ?: "尚未获取"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketDataStatusSheet(
    table: GuailiTableState,
    signals: MarketSignalsUiState,
    onlySignals: Boolean,
    groups: List<SignalFocusGroup>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag("market-data-status-details")) {
            Text("数据状态", style = MaterialTheme.typography.titleLarge)
            Text(marketDataStatusLabel(table, signals, onlySignals), Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium)
            if (onlySignals) {
                Text(signalFocusSourceLabel(signals))
                Text("${groups.size}个匹配品种 · ${groups.sumOf { it.rows.size }}条信号 · 监控${signals.symbols.size}个品种")
                Text("采样时间：${marketDataTime(signals.presentation.sampledAt)}")
                Text("获取时间：${marketDataTime(signals.presentation.fetchedAt)}")
                Text(signals.clockMessage)
                Text("周期颜色表示当前动态K的均线方向", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("表格 · ${if (table.settings.closedOnly) "收盘K" else "动态K"} · ${table.settings.maType}${table.settings.maLength}")
                Text("更新时间：${marketDataTime(table.lastUpdatedAt)} · ${table.symbols.size}个品种")
            }
            Text("自动刷新：${signals.autoRefreshSeconds}秒", Modifier.padding(top = 8.dp))
            if (onlySignals || signals.presentation.warning != null) {
                Text(signals.presentation.message.takeIf { signals.presentation.signals.isEmpty() } ?: "V2 当前有效信号已更新")
                signals.presentation.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                signals.snapshot?.response?.results?.filter { it.symbol in signals.symbols }?.forEach { row ->
                    val lines = serverSignalQualityLines(row)
                    if (lines.isNotEmpty()) {
                        Text(row.symbol, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (!onlySignals) table.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            signals.preferenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onRefresh, enabled = if (onlySignals) !signals.isRefreshing else !table.isLoading && !table.isRefreshing,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("刷新") }
        }
    }
}
