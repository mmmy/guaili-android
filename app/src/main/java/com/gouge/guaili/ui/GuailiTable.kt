package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiBackgroundArgb
import com.gouge.guaili.settings.GroupLayoutSize
import com.gouge.guaili.settings.LayoutMode
import com.gouge.guaili.settings.SymbolColumnWidthMode
import com.gouge.guaili.settings.SymbolDisplayMode
import com.gouge.guaili.settings.TableDensity

private val GridLineColor = Color(0xFF27313B)

@Composable
fun GuailiTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
    modifier: Modifier = Modifier,
    intervals: List<String> = state.intervals,
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val symbolPresentation = buildSymbolPresentation(
        symbols = state.symbols,
        displayMode = state.settings.symbolDisplayMode,
        widthMode = state.settings.symbolColumnWidthMode,
    )
    val symbolWidth = symbolPresentation.widthDp.dp
    val dimensions = tableDimensions(state.settings.tableDensity)

    Column(modifier = modifier.background(Color(0xFF11161C))) {
        Row {
            HeaderCell(
                text = "Symbol",
                secondaryText = symbolPresentation.commonQuote?.let { "/ $it" },
                width = symbolWidth,
                height = dimensions.cellHeight,
            )
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                intervals.forEach { interval ->
                    HeaderCell(
                        text = formatInterval(interval),
                        width = dimensions.cellWidth,
                        height = dimensions.cellHeight,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(vertical),
        ) {
            Column {
                state.symbols.forEach { symbol ->
                    HeaderCell(
                        text = symbolPresentation.displayNames.getValue(symbol),
                        width = symbolWidth,
                        height = dimensions.cellHeight,
                    )
                }
            }
            Column(modifier = Modifier.horizontalScroll(horizontal)) {
                state.symbols.forEach { symbol ->
                    Row {
                        intervals.forEach { interval ->
                            ValueCell(
                                cell = state.cells[symbol]?.get(interval),
                                onCellClick = onCellClick,
                                dimensions = dimensions,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuailiGroupedTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
    modifier: Modifier = Modifier,
    intervals: List<String> = state.intervals,
) {
    val vertical = rememberScrollState()
    val symbolPresentation = buildSymbolPresentation(
        symbols = state.symbols,
        displayMode = state.settings.symbolDisplayMode,
        widthMode = state.settings.symbolColumnWidthMode,
    )

    BoxWithConstraints(
        modifier = modifier.background(Color(0xFF11161C)),
    ) {
        val groupDimensions = groupedLayoutDimensions(
            widthDp = maxWidth.value.toInt(),
            size = state.settings.groupLayoutSize,
            density = state.settings.tableDensity,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical),
        ) {
            state.symbols.forEach { symbol ->
                GroupedSymbolHeader(
                    symbol = symbolPresentation.displayNames.getValue(symbol),
                    quote = symbolPresentation.commonQuote,
                    height = groupDimensions.symbolHeaderHeight,
                )
                intervals.chunked(groupDimensions.columns).forEach { rowIntervals ->
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
                                onCellClick = onCellClick,
                                dimensions = groupDimensions,
                                modifier = Modifier.weight(1f),
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

@Composable
private fun GroupedSymbolHeader(symbol: String, quote: String?, height: Dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF202832))
            .border(0.5.dp, GridLineColor)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = symbol,
            color = Color(0xFFE5E7EB),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        quote?.let {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "/ $it",
                color = Color(0xFF9CA3AF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun GroupedPeriodCell(
    interval: String,
    cell: GuailiCell?,
    onCellClick: (GuailiCell) -> Unit,
    dimensions: GroupedLayoutDimensions,
    modifier: Modifier = Modifier,
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
                text = formatInterval(interval),
                color = periodTextColor,
                fontSize = dimensions.periodFontSize,
                fontWeight = periodTextWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ValueCell(
            cell = cell,
            onCellClick = onCellClick,
            dimensions = dimensions.table,
            modifier = Modifier.fillMaxWidth(),
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
    onCellClick: (GuailiCell) -> Unit,
    dimensions: TableDimensions,
    modifier: Modifier = Modifier.width(dimensions.cellWidth),
) {
    val text = cell?.value?.toString() ?: "-"
    val textColor = if (cell == null) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    } else {
        Color.White
    }

    Box(
        modifier = modifier
            .height(dimensions.cellHeight)
            .defaultMinSize(
                minWidth = dimensions.cellWidth,
                minHeight = dimensions.cellHeight,
            )
            .background(cellBackground(cell))
            .border(0.5.dp, GridLineColor)
            .graphicsLayer(alpha = if (cell?.rankFilter == false) 0.5f else 1f)
            .then(if (cell != null) Modifier.clickable { onCellClick(cell) } else Modifier)
            .semantics {
                contentDescription = cell?.let {
                    val trend = trendState(it.longTrend, it.shortTrend).label.lowercase()
                    "${it.symbol}, ${formatInterval(it.interval)}, value ${it.value ?: "no data"}, $trend trend"
                } ?: "No data"
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = dimensions.valueFontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

internal data class TableDimensions(
    val cellWidth: Dp,
    val cellHeight: Dp,
    val valueFontSize: TextUnit,
)

internal fun tableDimensions(density: TableDensity): TableDimensions = when (density) {
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
): GroupedLayoutDimensions {
    val base = tableDimensions(density)
    return when (size) {
        GroupLayoutSize.Standard -> GroupedLayoutDimensions(
            columns = groupedColumnCount(widthDp, size, density),
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
            columns = groupedColumnCount(widthDp, size, density),
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
            columns = 10,
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
): Int {
    if (size == GroupLayoutSize.TenColumns) return 10
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
    return (widthDp / targetWidth).coerceIn(2, maxColumns)
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
