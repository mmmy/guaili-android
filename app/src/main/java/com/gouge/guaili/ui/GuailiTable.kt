package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.gouge.guaili.domain.guailiColorStrength
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
                        hasUnfinishedCandle = hasUnfinishedCandle(state, interval),
                        unfinishedMarkerWidth = dimensions.unfinishedMarkerWidth,
                        unfinishedMarkerBottomPadding = dimensions.unfinishedMarkerBottomPadding,
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
private fun HeaderCell(
    text: String,
    width: Dp,
    height: Dp,
    secondaryText: String? = null,
    hasUnfinishedCandle: Boolean = false,
    unfinishedMarkerWidth: Dp = 12.dp,
    unfinishedMarkerBottomPadding: Dp = 4.dp,
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
        if (hasUnfinishedCandle) {
            Spacer(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = unfinishedMarkerBottomPadding)
                    .width(unfinishedMarkerWidth)
                    .height(2.dp)
                    .background(Color(0xFFFACC15), CircleShape),
            )
        }
    }
}

@Composable
private fun ValueCell(
    cell: GuailiCell?,
    onCellClick: (GuailiCell) -> Unit,
    dimensions: TableDimensions,
) {
    val text = cell?.value?.toString() ?: "-"
    val textColor = if (cell == null) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    } else {
        Color.White
    }

    Box(
        modifier = Modifier
            .width(dimensions.cellWidth)
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
                    "${it.symbol}, ${formatInterval(it.interval)}, value ${it.value ?: "no data"}"
                } ?: "No data"
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
        val trendMarker = when {
            cell?.longTrend == true && cell.shortTrend != true -> "\u2191"
            cell?.shortTrend == true && cell.longTrend != true -> "\u2193"
            else -> null
        }
        trendMarker?.let { marker ->
            Text(
                text = marker,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = dimensions.trendMarkerFontSize,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = dimensions.trendMarkerEndPadding,
                        bottom = 1.dp,
                    ),
            )
        }
    }
}

internal data class TableDimensions(
    val cellWidth: Dp,
    val cellHeight: Dp,
    val trendMarkerFontSize: TextUnit,
    val trendMarkerEndPadding: Dp,
    val unfinishedMarkerWidth: Dp,
    val unfinishedMarkerBottomPadding: Dp,
)

internal fun tableDimensions(density: TableDensity): TableDimensions = when (density) {
    TableDensity.Compact -> TableDimensions(
        cellWidth = 52.dp,
        cellHeight = 40.dp,
        trendMarkerFontSize = 8.sp,
        trendMarkerEndPadding = 2.dp,
        unfinishedMarkerWidth = 10.dp,
        unfinishedMarkerBottomPadding = 3.dp,
    )
    TableDensity.Comfortable -> TableDimensions(
        cellWidth = 60.dp,
        cellHeight = 48.dp,
        trendMarkerFontSize = 10.sp,
        trendMarkerEndPadding = 3.dp,
        unfinishedMarkerWidth = 12.dp,
        unfinishedMarkerBottomPadding = 4.dp,
    )
}

internal fun hasUnfinishedCandle(state: GuailiTableState, interval: String): Boolean =
    state.symbols.any { symbol -> state.cells[symbol]?.get(interval)?.isClosed == false }

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
    if (cell == null) return Color(0xFF11161C)

    val strength = guailiColorStrength(cell.value)
    return when {
        strength < 0f -> blend(Color(0xFF31363D), Color(0xFFBE0041), -strength)
        strength > 0f -> blend(Color(0xFF31363D), Color(0xFF007A1A), strength)
        else -> Color(0xFF31363D)
    }
}

private fun blend(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )
}

internal fun formatInterval(interval: String): String {
    val minutes = interval.toIntOrNull() ?: return interval
    return if (minutes > 0 && minutes % 60 == 0) {
        "${minutes / 60}h"
    } else {
        "${minutes}m"
    }
}
