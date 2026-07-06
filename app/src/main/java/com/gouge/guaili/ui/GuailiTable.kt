package com.gouge.guaili.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiColorStrength

private val SymbolWidth = 84.dp
private val CellWidth = 56.dp
private val CellHeight = 38.dp

@Composable
fun GuailiTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()

    Column {
        Row {
            HeaderCell(text = "Symbol", width = SymbolWidth)
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                state.intervals.forEach { interval ->
                    HeaderCell(text = formatInterval(interval), width = CellWidth)
                }
            }
        }

        Row(modifier = Modifier.verticalScroll(vertical)) {
            Column {
                state.symbols.forEach { symbol ->
                    HeaderCell(text = symbol, width = SymbolWidth)
                }
            }
            Column(modifier = Modifier.horizontalScroll(horizontal)) {
                state.symbols.forEach { symbol ->
                    Row {
                        state.intervals.forEach { interval ->
                            ValueCell(
                                cell = state.cells[symbol]?.get(interval),
                                onCellClick = onCellClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(CellHeight)
            .background(Color(0xFF202832))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFFE5E7EB),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ValueCell(
    cell: GuailiCell?,
    onCellClick: (GuailiCell) -> Unit,
) {
    val text = cell?.value?.toString() ?: "-"
    val textColor = if (cell == null) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    } else {
        Color.White
    }

    Box(
        modifier = Modifier
            .width(CellWidth)
            .height(CellHeight)
            .defaultMinSize(minWidth = CellWidth, minHeight = CellHeight)
            .background(cellBackground(cell))
            .then(if (cell != null) Modifier.clickable { onCellClick(cell) } else Modifier)
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
        if (cell?.isClosed == false) {
            Spacer(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(5.dp)
                    .background(Color(0xFFFACC15), CircleShape),
            )
        }
    }
}

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
