package com.gouge.guaili.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gouge.guaili.signals.ServerSignalItem
import com.gouge.guaili.signals.serverMovingAverageLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SignalSummaryRow(
    symbol: String, summary: MarketSignalSummary,
    onSelect: (ServerSignalItem) -> Unit, onList: (String) -> Unit, compact: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("market-summary-$symbol")) {
        val showCount = compact || maxWidth < (summary.items.size * 76).dp
        if (summary.items.isEmpty()) {
            Text(summary.status, Modifier.clickable(role = Role.Button, onClickLabel = "查看${symbol}信号") { onList(symbol) }.padding(4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (showCount) {
            val label = if (summary.items.size == 1) signalSummaryLabel(summary.items.single()) else "${summary.items.size}个信号"
            Text(label, Modifier.clickable(role = Role.Button, onClickLabel = "选择${symbol}信号") {
                    if (summary.items.size == 1) onSelect(summary.items.single()) else onList(symbol)
                }.padding(4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            summary.items.forEach { item ->
                TextButton(onClick = { onSelect(item) }, modifier = Modifier.testTag("market-summary-$symbol-${item.signal.id}"),
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text(signalSummaryLabel(item), maxLines = 1) }
            }
        }
    }
}

@Composable
internal fun MarketLinkBar(
    link: LiveMarketLink, state: MarketSignalsUiState, table: GuailiTableState,
    visibleIntervals: List<String>, reveal: Boolean, canReturn: Boolean,
    onReveal: () -> Unit, onSignals: () -> Unit, onKline: () -> Unit, onClear: () -> Unit, onReturn: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("market-link-bar")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("${link.key.symbol.removeSuffix("USDT")} · ${link.message}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("market-link-label"))
            val missing = link.members.keys.count { it !in visibleIntervals }
            val signalMa = state.snapshot?.response?.let(::serverMovingAverageLabel) ?: "均线待获取"
            val tableMa = "${table.settings.maType}${table.settings.maLength}"
            Text("实时信号 $signalMa · 表格${if (table.settings.closedOnly) "收盘K" else "动态K"} $tableMa · 周期色取前根趋势",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val sampled = state.snapshot?.response?.results?.firstOrNull { it.symbol == link.key.symbol }?.sampledAt
            fun time(at: Long?) = at?.takeIf { it > 0 }?.let {
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
            } ?: "待获取"
            Text("信号采样 ${time(sampled)} · 表格更新 ${time(table.lastUpdatedAt)} · 独立取数",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (missing > 0 || reveal) TextButton(onClick = onReveal, modifier = Modifier.testTag("market-link-reveal")) {
                    Text(if (reveal) "收起临时周期" else "展开${missing}个隐藏周期")
                }
                TextButton(onClick = onSignals, modifier = Modifier.testTag("market-link-signals")) { Text("查看信号") }
                TextButton(onClick = onKline, enabled = link.item != null, modifier = Modifier.testTag("market-link-kline")) { Text("K线") }
                if (canReturn) TextButton(onClick = onReturn, modifier = Modifier.testTag("market-link-return")) { Text("返回定位前") }
                TextButton(onClick = onClear, modifier = Modifier.testTag("market-link-clear")) { Text("清除") }
            }
        }
    }
}
