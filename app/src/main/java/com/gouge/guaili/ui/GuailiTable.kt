package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiBackgroundArgb
import com.gouge.guaili.settings.GroupLayoutSize
import com.gouge.guaili.settings.LayoutMode
import com.gouge.guaili.settings.SymbolColumnWidthMode
import com.gouge.guaili.settings.SymbolDisplayMode
import com.gouge.guaili.settings.TableDensity
import kotlin.math.max

private val GridLineColor = Color(0xFF27313B)
private val MatrixBackground = Color(0xFF11161C)

@Composable
internal fun GuailiTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
    modifier: Modifier = Modifier,
    intervals: List<String> = state.intervals,
    listState: LazyListState = rememberLazyListState(),
    horizontal: ScrollState = rememberScrollState(),
    summaries: Map<String, MarketSignalSummary> = emptyMap(),
    link: LiveMarketLink? = null,
    onSignalClick: (com.gouge.guaili.signals.ServerSignalItem) -> Unit = {},
    onSymbolSignals: (String) -> Unit = {},
    priceAlerts: Map<String, SymbolPriceAlerts> = emptyMap(),
    alertsUnavailable: Boolean = false,
    onSymbolAlerts: (String) -> Unit = {},
    onPeriodAlert: (String, String) -> Unit = { _, _ -> },
) {
    val symbolPresentation = remember(state.symbols, state.settings.symbolDisplayMode, state.settings.symbolColumnWidthMode) {
        buildSymbolPresentation(state.symbols, state.settings.symbolDisplayMode, state.settings.symbolColumnWidthMode)
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val symbolWidth = (symbolPresentation.widthDp * fontScale.coerceAtLeast(1f)).dp
    val baseDimensions = tableDimensions(state.settings.tableDensity, fontScale)
    val dimensions = baseDimensions.copy(cellHeight = max(baseDimensions.cellHeight.value, 48f * fontScale.coerceAtLeast(1f)).dp)
    val headerHeight = max(dimensions.cellHeight.value, 25.2f * fontScale + 6f).dp

    Column(modifier = modifier.background(MatrixBackground)) {
        Row {
            HeaderCell(
                text = "Symbol",
                secondaryText = symbolPresentation.commonQuote?.let { "/ $it" },
                width = symbolWidth,
                height = headerHeight,
            )
            Row(modifier = Modifier.weight(1f).horizontalScroll(horizontal)) {
                intervals.forEach { interval ->
                    HeaderCell(
                        text = formatInterval(interval),
                        width = dimensions.cellWidth,
                        height = headerHeight,
                    )
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("market-table-list")) {
            items(state.symbols, key = { it }, contentType = { "matrix-row" }) { symbol ->
                var symbolHeight by remember(symbol) { mutableIntStateOf(0) }
                val rowDimensions = dimensions.copy(cellHeight = max(dimensions.cellHeight.value, with(density) { symbolHeight.toDp().value }).dp)
                Row {
                    Column(Modifier.width(symbolWidth).heightIn(min = dimensions.cellHeight).onSizeChanged { symbolHeight = it.height }
                        .background(Color(0xFF202832)).border(0.5.dp, GridLineColor)) {
                        Text(symbolPresentation.displayNames.getValue(symbol), Modifier.padding(horizontal = 4.dp),
                            fontSize = dimensions.valueFontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        summaries[symbol]?.let { summary ->
                            SignalSummaryRow(symbol, summary, onSignalClick, onSymbolSignals, compact = true)
                        }
                        PriceAlertSummary(symbol, priceAlerts[symbol], alertsUnavailable, { onSymbolAlerts(symbol) }, compact = true)
                    }
                    Row(modifier = Modifier.weight(1f).horizontalScroll(horizontal)) {
                        intervals.forEach { interval ->
                            ValueCell(
                                cell = state.cells[symbol]?.get(interval),
                                symbol = symbol,
                                interval = interval,
                                onCellClick = onCellClick,
                                dimensions = rowDimensions,
                                member = link?.takeIf { it.key.symbol == symbol }?.members?.get(interval),
                                alerts = priceAlerts[symbol]?.periods?.get(interval),
                                onAlertClick = { onPeriodAlert(symbol, interval) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GuailiGroupedTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
    modifier: Modifier = Modifier,
    intervals: List<String> = state.intervals,
    listState: LazyListState = rememberLazyListState(),
    summaries: Map<String, MarketSignalSummary> = emptyMap(),
    link: LiveMarketLink? = null,
    onSignalClick: (com.gouge.guaili.signals.ServerSignalItem) -> Unit = {},
    onSymbolSignals: (String) -> Unit = {},
    priceAlerts: Map<String, SymbolPriceAlerts> = emptyMap(),
    alertsUnavailable: Boolean = false,
    onSymbolAlerts: (String) -> Unit = {},
    onPeriodAlert: (String, String) -> Unit = { _, _ -> },
) {
    val symbolPresentation = remember(state.symbols, state.settings.symbolDisplayMode, state.settings.symbolColumnWidthMode) {
        buildSymbolPresentation(state.symbols, state.settings.symbolDisplayMode, state.settings.symbolColumnWidthMode)
    }
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier = modifier.background(MatrixBackground),
    ) {
        val groupDimensions = groupedLayoutDimensions(
            widthDp = maxWidth.value.toInt(),
            size = state.settings.groupLayoutSize,
            density = state.settings.tableDensity,
            fontScale = fontScale,
        )
        val intervalRows = remember(intervals, groupDimensions.columns) { intervals.chunked(groupDimensions.columns) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("market-groups-list")) {
            items(state.symbols, key = { it }, contentType = { "symbol-group" }) { symbol ->
                // Keep the dense, unchanged grid in one layer while its group scrolls.
                Column(modifier = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                    GroupedSymbolHeader(
                        symbol = symbolPresentation.displayNames.getValue(symbol),
                        quote = symbolPresentation.commonQuote,
                        height = groupDimensions.symbolHeaderHeight,
                        summary = summaries[symbol],
                        summaryContent = { summaries[symbol]?.let { SignalSummaryRow(symbol, it, onSignalClick, onSymbolSignals) } },
                        alertContent = { PriceAlertSummary(symbol, priceAlerts[symbol], alertsUnavailable, { onSymbolAlerts(symbol) }) },
                    )
                    intervalRows.forEach { rowIntervals ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                groupDimensions.columnSpacing,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = groupDimensions.horizontalPadding,
                                    vertical = groupDimensions.rowPadding,
                                ),
                        ) {
                            rowIntervals.forEach { interval ->
                                GroupedPeriodCell(
                                    interval = interval,
                                    cell = state.cells[symbol]?.get(interval),
                                    symbol = symbol,
                                    onCellClick = onCellClick,
                                    dimensions = groupDimensions,
                                    modifier = Modifier.weight(1f),
                                    member = link?.takeIf { it.key.symbol == symbol }?.members?.get(interval),
                                    alerts = priceAlerts[symbol]?.periods?.get(interval),
                                    onAlertClick = { onPeriodAlert(symbol, interval) },
                                )
                            }
                            repeat(groupDimensions.columns - rowIntervals.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(groupDimensions.sectionSpacing))
                }
            }
        }
    }
}

@Composable
private fun GroupedSymbolHeader(symbol: String, quote: String?, height: Dp,
    summary: MarketSignalSummary? = null, summaryContent: @Composable () -> Unit = {},
    alertContent: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(Color(0xFF202832))
            .border(0.5.dp, GridLineColor)
            .padding(horizontal = 10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = symbol,
            color = Color(0xFFE5E7EB),
            fontSize = 13.sp,
            lineHeight = 15.6.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        quote?.let {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "/ $it",
                color = Color(0xFF9CA3AF),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (summary != null) Box(Modifier.weight(1f).padding(start = 8.dp)) { summaryContent() }
        }
        alertContent()
    }
}

@Composable
internal fun GroupedPeriodCell(
    interval: String,
    cell: GuailiCell?,
    symbol: String,
    onCellClick: (GuailiCell) -> Unit,
    dimensions: GroupedLayoutDimensions,
    modifier: Modifier = Modifier,
    member: String? = null,
    alerts: PeriodPriceAlerts? = null,
    onAlertClick: () -> Unit = {},
    highlightMember: Boolean = true,
) {
    val trend = cell?.let { trendState(it.longTrend, it.shortTrend) }
    val periodTextColor = trend?.let(::trendTextColor) ?: NeutralTrendTextColor
    val periodTextWeight = if (trend == null || trend == TrendState.Neutral) {
        FontWeight.SemiBold
    } else {
        FontWeight.Bold
    }

    Column(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.periodHeaderHeight)
                .background(Color(0xFF202832))
                .border(0.5.dp, GridLineColor),
        ) {
            Text(
                text = formatInterval(interval) + if (member.isNullOrEmpty()) "" else " $member",
                color = periodTextColor,
                fontSize = dimensions.periodFontSize,
                lineHeight = dimensions.periodFontSize * 1.2f,
                fontWeight = periodTextWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ValueCell(
            cell = cell,
            symbol = symbol,
            interval = interval,
            onCellClick = onCellClick,
            dimensions = dimensions.table,
            modifier = Modifier.fillMaxWidth(),
            member = member,
            showMemberLabel = false,
            alerts = alerts,
            onAlertClick = onAlertClick,
            highlightMember = highlightMember,
        )
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: Dp,
    height: Dp,
    secondaryText: String? = null,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(Color(0xFF202832))
            .border(0.5.dp, GridLineColor)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = Color(0xFFE5E7EB),
                fontSize = 12.sp,
                lineHeight = 14.4.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            secondaryText?.let { secondary ->
                Text(
                    text = secondary,
                    color = Color(0xFF9CA3AF),
                    fontSize = 9.sp,
                    lineHeight = 10.8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ValueCell(
    cell: GuailiCell?,
    symbol: String,
    interval: String,
    onCellClick: (GuailiCell) -> Unit,
    dimensions: TableDimensions,
    modifier: Modifier = Modifier,
    member: String? = null,
    showMemberLabel: Boolean = true,
    alerts: PeriodPriceAlerts? = null,
    onAlertClick: () -> Unit = {},
    highlightMember: Boolean = true,
) {
    val text = cell?.value?.toString() ?: "—"
    val textColor = if (cell == null) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    } else {
        Color.White.copy(alpha = if (cell.rankFilter == false) 0.8f else 1f)
    }
    val background = cellBackground(cell).let {
        if (cell?.rankFilter == false) Color(
            red = (MatrixBackground.red + it.red) / 2f,
            green = (MatrixBackground.green + it.green) / 2f,
            blue = (MatrixBackground.blue + it.blue) / 2f,
        ) else it
    }

    Box(
        modifier = modifier
            .then(if (dimensions.cellWidth > 0.dp) Modifier.width(dimensions.cellWidth) else Modifier)
            .height(dimensions.cellHeight)
            .defaultMinSize(
                minWidth = dimensions.cellWidth,
                minHeight = dimensions.cellHeight,
            )
            .background(background)
            .border(0.5.dp, GridLineColor)
            .then(if (member != null && highlightMember) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .then(if ((alerts?.newTriggerCount ?: 0) > 0) Modifier.border(1.dp, AlertTriggeredColor) else Modifier)
            .testTag("market-cell-$symbol-$interval")
            .then(when {
                cell != null -> Modifier.clickable(role = Role.Button, onClickLabel = "Open cell details") { onCellClick(cell) }
                alerts != null -> Modifier.clickable(role = Role.Button, onClickLabel = "查看价格警报线", onClick = onAlertClick)
                else -> Modifier
            })
            .semantics {
                contentDescription = cell?.let {
                    val trend = trendState(it.longTrend, it.shortTrend).label.lowercase()
                    "${it.symbol}, ${formatInterval(it.interval)}, value ${it.value ?: "no data"}, $trend trend"
                } ?: "$symbol, ${formatInterval(interval)}, no data"
                stateDescription = when (cell?.rankFilter) {
                    true -> "ATR filter passed"
                    false -> "ATR filtered"
                    null -> "ATR filter unavailable"
                } + (if (member != null) ", ${if (highlightMember) "所选实时信号" else "V2信号"}参与周期 $member" else "") +
                    (if (alerts != null) "，${alerts.description}" else "")
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 2.dp).padding(top = if (showMemberLabel && !member.isNullOrEmpty()) 9.dp else 0.dp),
            color = textColor,
            fontSize = dimensions.valueFontSize,
            lineHeight = dimensions.valueFontSize * 1.2f,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (showMemberLabel && !member.isNullOrEmpty()) Text(member, Modifier.align(Alignment.TopEnd).padding(end = 3.dp),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        alerts?.let {
            Box(Modifier.align(Alignment.TopStart).padding(3.dp)) {
                PriceAlertMarker(it, Modifier.testTag("market-period-alert-$symbol-$interval"))
            }
        }
    }
}

internal val AlertTriggeredColor = Color(0xFFFBBF24)
private val AlertMonitoringColor = Color(0xFF7DD3FC)

@Composable
private fun PriceAlertMarker(alerts: PeriodPriceAlerts, modifier: Modifier = Modifier) {
    val waiting = alerts.newTriggerCount == 0 && alerts.waitingCount == alerts.activeCount
    val color = if (alerts.newTriggerCount > 0) AlertTriggeredColor else AlertMonitoringColor
    Box(modifier.size(8.dp).then(
        if (waiting) Modifier.border(1.5.dp, Color(0xFFCBD5E1), CircleShape)
        else Modifier.background(color, CircleShape).border(0.5.dp, MatrixBackground, CircleShape)
    ))
}

@Composable
internal fun PriceAlertSummary(symbol: String, alerts: SymbolPriceAlerts?, unavailable: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    if (alerts == null && !unavailable) return
    val unread = alerts?.newTriggerCount ?: 0
    val color = if (unread > 0) AlertTriggeredColor else AlertMonitoringColor
    val label = if (unavailable) "警报待更新" else "警报 ${alerts?.total ?: 0}"
    Column(Modifier.testTag("market-alerts-$symbol").clickable(role = Role.Button, onClickLabel = "查看${symbol}价格警报", onClick = onClick)
        .semantics { contentDescription = "$symbol，$label，新触发 $unread 条${if (unavailable) "，显示上次结果" else ""}" }
        .padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(if (unread > 0) Icons.Filled.NotificationsActive else Icons.Outlined.Notifications, null, Modifier.size(12.dp), tint = color)
            Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && unread > 0) Text("· 新触发 $unread", fontSize = 10.sp, lineHeight = 12.sp, color = AlertTriggeredColor, maxLines = 1)
        }
        if (compact && unread > 0) Text("新触发 $unread", fontSize = 10.sp, lineHeight = 12.sp, color = AlertTriggeredColor, maxLines = 1)
    }
}

internal data class TableDimensions(
    val cellWidth: Dp,
    val cellHeight: Dp,
    val valueFontSize: TextUnit,
)

internal fun tableDimensions(density: TableDensity, fontScale: Float = 1f): TableDimensions {
    val base = when (density) {
        TableDensity.Compact -> TableDimensions(
            cellWidth = 52.dp,
            cellHeight = 40.dp,
            valueFontSize = 14.sp,
        )
        TableDensity.Comfortable -> TableDimensions(
            cellWidth = 60.dp,
            cellHeight = 48.dp,
            valueFontSize = 14.sp,
        )
    }
    return base.copy(
        cellWidth = (base.cellWidth.value * fontScale.coerceAtLeast(1f)).dp,
        cellHeight = textContainerHeight(base.cellHeight, base.valueFontSize, fontScale),
    )
}

private fun textContainerHeight(minimum: Dp, fontSize: TextUnit, fontScale: Float): Dp =
    max(minimum.value, fontSize.value * fontScale * 1.2f + 6f).dp

internal data class GroupedLayoutDimensions(
    val columns: Int,
    val symbolHeaderHeight: Dp,
    val periodHeaderHeight: Dp,
    val periodFontSize: TextUnit,
    val columnSpacing: Dp,
    val rowPadding: Dp,
    val horizontalPadding: Dp,
    val sectionSpacing: Dp,
    val table: TableDimensions,
)

internal fun groupedLayoutDimensions(
    widthDp: Int,
    size: GroupLayoutSize,
    density: TableDensity,
    fontScale: Float = 1f,
): GroupedLayoutDimensions {
    val base = tableDimensions(density, fontScale)
    val result = when (size) {
        GroupLayoutSize.Standard -> GroupedLayoutDimensions(
            columns = groupedColumnCount(widthDp, size, density, fontScale),
            symbolHeaderHeight = 36.dp,
            periodHeaderHeight = 26.dp,
            periodFontSize = 11.sp,
            columnSpacing = 4.dp,
            rowPadding = 2.dp,
            horizontalPadding = 4.dp,
            sectionSpacing = 6.dp,
            table = base.copy(cellWidth = 0.dp),
        )
        GroupLayoutSize.Compact -> GroupedLayoutDimensions(
            columns = groupedColumnCount(widthDp, size, density, fontScale),
            symbolHeaderHeight = 30.dp,
            periodHeaderHeight = 20.dp,
            periodFontSize = 10.sp,
            columnSpacing = 2.dp,
            rowPadding = 1.dp,
            horizontalPadding = 2.dp,
            sectionSpacing = 4.dp,
            table = base.copy(
                cellWidth = 0.dp,
                cellHeight = if (density == TableDensity.Compact) 32.dp else 36.dp,
                valueFontSize = 13.sp,
            ),
        )
        GroupLayoutSize.TenColumns -> GroupedLayoutDimensions(
            columns = groupedColumnCount(widthDp, size, density, fontScale),
            symbolHeaderHeight = 28.dp,
            periodHeaderHeight = 18.dp,
            periodFontSize = 9.sp,
            columnSpacing = 0.dp,
            rowPadding = 0.dp,
            horizontalPadding = 0.dp,
            sectionSpacing = 3.dp,
            table = base.copy(
                cellWidth = 0.dp,
                cellHeight = 28.dp,
                valueFontSize = 12.sp,
            ),
        )
    }
    return result.copy(
        symbolHeaderHeight = textContainerHeight(result.symbolHeaderHeight, 13.sp, fontScale),
        periodHeaderHeight = textContainerHeight(result.periodHeaderHeight, result.periodFontSize, fontScale),
        table = result.table.copy(cellHeight = textContainerHeight(result.table.cellHeight, result.table.valueFontSize, fontScale)),
    )
}

internal enum class TableLayout {
    Table,
    Groups,
}

internal fun resolveTableLayout(mode: LayoutMode, isLandscape: Boolean): TableLayout = when (mode) {
    LayoutMode.Auto -> if (isLandscape) TableLayout.Table else TableLayout.Groups
    LayoutMode.Table -> TableLayout.Table
    LayoutMode.Groups -> TableLayout.Groups
}

internal fun groupedColumnCount(
    widthDp: Int,
    size: GroupLayoutSize,
    density: TableDensity,
    fontScale: Float = 1f,
): Int {
    if (size == GroupLayoutSize.TenColumns) return (10 / fontScale.coerceAtLeast(1f)).toInt().coerceIn(2, 10)
    val targetWidth = when (size) {
        GroupLayoutSize.Standard -> when (density) {
            TableDensity.Compact -> 92
            TableDensity.Comfortable -> 104
        }
        GroupLayoutSize.Compact -> when (density) {
            TableDensity.Compact -> 56
            TableDensity.Comfortable -> 64
        }
        GroupLayoutSize.TenColumns -> error("Handled above")
    }
    val maxColumns = if (size == GroupLayoutSize.Compact) 8 else 6
    return (widthDp / (targetWidth * fontScale.coerceAtLeast(1f))).toInt().coerceIn(2, maxColumns)
}

internal data class SymbolPresentation(
    val displayNames: Map<String, String>,
    val commonQuote: String?,
    val widthDp: Int,
)

internal fun buildSymbolPresentation(
    symbols: List<String>,
    displayMode: SymbolDisplayMode,
    widthMode: SymbolColumnWidthMode,
): SymbolPresentation {
    val commonQuote = commonQuoteSuffix(symbols)
    val displayNames = symbols.associateWith { symbol ->
        when (displayMode) {
            SymbolDisplayMode.Full -> symbol
            SymbolDisplayMode.Auto -> commonQuote?.let { symbol.dropLast(it.length) } ?: symbol
            SymbolDisplayMode.Base -> stripKnownQuoteSuffix(symbol)
        }
    }
    val shownQuote = when (displayMode) {
        SymbolDisplayMode.Full -> null
        SymbolDisplayMode.Auto, SymbolDisplayMode.Base -> commonQuote
    }
    val widthDp = when (widthMode) {
        SymbolColumnWidthMode.Auto -> {
            val longestLength = displayNames.values.maxOfOrNull(String::length) ?: 6
            (longestLength * 8 + 20).coerceIn(76, 116)
        }
        SymbolColumnWidthMode.Compact -> 76
        SymbolColumnWidthMode.Standard -> 96
        SymbolColumnWidthMode.Wide -> 116
    }
    return SymbolPresentation(displayNames, shownQuote, widthDp)
}

private fun commonQuoteSuffix(symbols: List<String>): String? =
    KnownQuoteSuffixes.firstOrNull { suffix ->
        symbols.isNotEmpty() && symbols.all { symbol ->
            symbol.length > suffix.length && symbol.endsWith(suffix, ignoreCase = true)
        }
    }

private fun stripKnownQuoteSuffix(symbol: String): String {
    val suffix = KnownQuoteSuffixes.firstOrNull { candidate ->
        symbol.length > candidate.length && symbol.endsWith(candidate, ignoreCase = true)
    }
    return suffix?.let { symbol.dropLast(it.length) } ?: symbol
}

private val KnownQuoteSuffixes = listOf("USDT", "USDC", "USD", "BTC", "ETH")

private fun cellBackground(cell: GuailiCell?): Color {
    return Color(guailiBackgroundArgb(cell?.value))
}

internal fun formatInterval(interval: String): String {
    val minutes = interval.toIntOrNull() ?: return interval
    return if (minutes > 0 && minutes % 60 == 0) {
        "${minutes / 60}h"
    } else {
        "${minutes}m"
    }
}
