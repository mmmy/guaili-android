package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.data.ServerIntervalEvidence
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiIntervalDurationMillis
import com.gouge.guaili.signals.ServerSignalItem
import java.time.Instant

internal data class SignalFocusMember(val cell: GuailiCell, val part: String)
internal data class SignalFocusRow(val item: ServerSignalItem, val members: List<SignalFocusMember>)
internal data class SignalFocusGroup(val symbol: String, val rows: List<SignalFocusRow>)

/** Project only eligible V2 items. Membership and values always share their server response. */
internal fun signalFocusGroups(state: MarketSignalsUiState, symbols: List<String>): List<SignalFocusGroup> {
    if (state.presentation.status !in setOf("ready", "degraded")) return emptyList()
    val response = state.snapshot?.response ?: return emptyList()
    val items = state.presentation.signals.groupBy { it.symbol }
    val evidence = response.results.associateBy { it.symbol }
    return symbols.distinct().mapNotNull { symbol ->
        val periods = evidence[symbol]?.perIntervalQuality?.associateBy { it.interval } ?: return@mapNotNull null
        val rows = items[symbol].orEmpty().distinctBy { it.signal.id }.mapNotNull row@{ item ->
            val members = item.signal.runs.flatMapIndexed { index, run ->
                val part = if (item.signal.kind != "conflict") "" else
                    (if (index == 0) "短" else "长") + (if (run.direction == "positive") "+" else "−")
                run.intervals.map { interval ->
                    val value = periods[interval] ?: return@row null
                    if (value.availability != "ready") return@row null
                    SignalFocusMember(value.asSignalCell(symbol), part)
                }
            }.distinctBy { it.cell.interval }.sortedByDescending { guailiIntervalDurationMillis(it.cell.interval) }
            SignalFocusRow(item, members).takeIf { members.isNotEmpty() }
        }.sortedBy { row -> when (row.item.signal.kind) {
            "conflict" -> 0
            "extreme" -> if (row.item.signal.direction == "positive") 1 else 2
            "compression" -> 3
            else -> 4
        } }
        SignalFocusGroup(symbol, rows).takeIf { rows.isNotEmpty() }
    }
}

private fun ServerIntervalEvidence.asSignalCell(symbol: String) = GuailiCell(
    symbol = symbol, interval = interval, value = value, guaili = guaili, ma = ma,
    atr14 = atr14, atrRank = atrRank, rankFilter = true,
    // V2 evidence carries the trend of this dynamic candle, rather than the table's preceding candle.
    longTrend = longTrend, shortTrend = shortTrend, signalLongTrend = longTrend, signalShortTrend = shortTrend,
    isClosed = isClosed, openTime = openTime?.let { Instant.ofEpochMilli(it).toString() },
    closeTime = closeTime?.let { Instant.ofEpochMilli(it).toString() }, availability = availability,
    reason = reason, historyCount = historyCount,
)

@Composable
internal fun SignalFocusTable(
    groups: List<SignalFocusGroup>,
    table: GuailiTableState,
    signals: MarketSignalsUiState,
    listState: LazyListState,
    expandedSymbols: List<String>,
    onExpand: (String) -> Unit,
    onCellClick: (GuailiCell) -> Unit,
    onTableCellClick: (GuailiCell) -> Unit,
    onSignalClick: (ServerSignalItem) -> Unit,
    priceAlerts: Map<String, SymbolPriceAlerts>,
    alertsUnavailable: Boolean,
    onSymbolAlerts: (String) -> Unit,
    onPeriodAlert: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    selection: MarketLinkKey? = null,
    tvAlerts: Map<String, SymbolTvAlerts> = emptyMap(),
    onSymbolTvAlerts: (String) -> Unit = {},
    onPeriodTvAlerts: (String, String) -> Unit = { _, _ -> },
) {
    val names = remember(table.symbols, table.settings.symbolDisplayMode, table.settings.symbolColumnWidthMode) {
        buildSymbolPresentation(table.symbols, table.settings.symbolDisplayMode, table.settings.symbolColumnWidthMode)
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.background(Color(0xFF11161C)).testTag("market-signal-focus")) {
        val availableWidth = maxWidth.value
        val dimensions = groupedLayoutDimensions(maxWidth.value.toInt(), table.settings.groupLayoutSize,
            table.settings.tableDensity, fontScale)
        val labelWidth = (66 * fontScale.coerceAtLeast(1f)).dp
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("market-signal-focus-list")) {
            if (groups.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("market-signal-focus-empty")) {
                    Text(signals.presentation.message, color = MaterialTheme.colorScheme.onSurface)
                    Text("仅显示当前有效 V2 信号；可关闭上方开关查看全级别", Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(groups, key = { it.symbol }, contentType = { "signal-focus-group" }) { group ->
                Column(Modifier.fillMaxWidth().testTag("market-focus-symbol-${group.symbol}")) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF202832)).padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(names.displayNames[group.symbol] ?: group.symbol, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        names.commonQuote?.let { Text(" / $it", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("  ${group.rows.size}个信号", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        PriceAlertSummary(group.symbol, priceAlerts[group.symbol], alertsUnavailable, { onSymbolAlerts(group.symbol) })
                        TvAlertSummary(group.symbol, tvAlerts[group.symbol], { onSymbolTvAlerts(group.symbol) })
                        TextButton(onClick = { onExpand(group.symbol) }, modifier = Modifier.testTag("market-focus-expand-${group.symbol}"),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(if (group.symbol in expandedSymbols) "收起" else "全级别", fontSize = 11.sp)
                        }
                    }
                    val largest = group.rows.maxOf { it.members.size }
                    val minimumWidth = if (group.rows.any { it.item.signal.kind == "conflict" }) 48f else 36f
                    val cellWidth = ((availableWidth - labelWidth.value - 4f) / largest)
                        .coerceIn(minimumWidth * fontScale.coerceAtLeast(1f), 56f * fontScale.coerceAtLeast(1f)).dp
                    val rowDimensions = dimensions.copy(
                        periodHeaderHeight = dimensions.periodHeaderHeight.coerceAtLeast((18 * fontScale.coerceAtLeast(1f)).dp),
                        periodFontSize = maxOf(dimensions.periodFontSize.value, 10f).sp,
                        table = dimensions.table.copy(cellWidth = 0.dp))
                    group.rows.forEach { row -> key(row.item.signal.id) {
                        val selected = selection?.symbol == group.symbol && selection.id == row.item.signal.id
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .testTag("market-focus-row-${group.symbol}-${row.item.signal.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(labelWidth).heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = "定位此实时信号") { onSignalClick(row.item) }
                                .padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                                Text(signalSummaryLabel(row.item), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.item.signal.kind == "conflict") Text(row.item.signal.runs.mapIndexed { index, run ->
                                    (if (index == 0) "短" else "长") + (if (run.direction == "positive") "+" else "−")
                                }.joinToString(" / "), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                row.members.forEach { member ->
                                    GroupedPeriodCell(member.cell.interval, member.cell, group.symbol, onCellClick,
                                        rowDimensions, Modifier.width(cellWidth), member = member.part.takeIf { selected || it.isNotEmpty() },
                                        alerts = priceAlerts[group.symbol]?.periods?.get(member.cell.interval),
                                        onAlertClick = { onPeriodAlert(group.symbol, member.cell.interval) }, highlightMember = selected,
                                        tvAlerts = tvAlerts[group.symbol]?.period(member.cell.interval),
                                        onTvAlertClick = { onPeriodTvAlerts(group.symbol, member.cell.interval) })
                                }
                            }
                        }
                    } }
                    if (group.symbol in expandedSymbols) {
                        Column(Modifier.testTag("market-focus-context-${group.symbol}")) {
                            Text("全级别 · 表格参数 · ${if (table.settings.closedOnly) "收盘K" else "动态K"} · " +
                                marketDataStatusLabel(table, signals, false) + " · " + marketDataTime(table.lastUpdatedAt),
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (table.isLoading || table.isRefreshing) Text("正在更新表格…", Modifier.padding(horizontal = 8.dp), fontSize = 11.sp)
                            table.errorMessage?.let { Text(it, Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                            table.intervals.chunked(dimensions.columns).forEach { intervals ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = dimensions.horizontalPadding, vertical = dimensions.rowPadding),
                                    horizontalArrangement = Arrangement.spacedBy(dimensions.columnSpacing)) {
                                    intervals.forEach { interval -> GroupedPeriodCell(interval, table.cells[group.symbol]?.get(interval), group.symbol,
                                        onTableCellClick, dimensions, Modifier.weight(1f),
                                        alerts = priceAlerts[group.symbol]?.periods?.get(interval), onAlertClick = { onPeriodAlert(group.symbol, interval) },
                                        tvAlerts = tvAlerts[group.symbol]?.period(interval),
                                        onTvAlertClick = { onPeriodTvAlerts(group.symbol, interval) }) }
                                    repeat(dimensions.columns - intervals.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(dimensions.sectionSpacing))
                }
            }
        }
    }
}
