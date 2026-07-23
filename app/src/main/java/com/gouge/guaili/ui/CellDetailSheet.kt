package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.GuailiCell
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellDetailSheet(
    cell: GuailiCell,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = "${cell.symbol}  ${formatInterval(cell.interval)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = cell.value?.let { "Signal value $it" } ?: "No signal value",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusChip("Candle", if (cell.isClosed == true) "Closed" else "Live")
                StatusChip("ATR filter", if (cell.rankFilter == true) "Passed" else "Filtered")
                val trend = trendState(cell.longTrend, cell.shortTrend).label
                StatusChip("Trend", trend)
            }

            DetailSection("Indicators")
            DetailLine("Guaili", formatDecimal(cell.guaili, 4))
            DetailLine("Moving average", formatDecimal(cell.ma, 4))
            DetailLine("ATR", formatDecimal(cell.atr14, 4))
            DetailLine("ATR rank", formatDecimal(cell.atrRank, 2, "%"))

            DetailSection("Candle time")
            DetailLine("Open", formatDateTime(cell.openTime))
            DetailLine("Close", formatDateTime(cell.closeTime))
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String) {
    AssistChip(
        onClick = {},
        label = {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun DetailSection(title: String) {
    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun formatDecimal(value: Double?, digits: Int, suffix: String = ""): String {
    if (value == null) return "-"
    return String.format(Locale.US, "%.${digits}f%s", value, suffix)
}

internal fun formatDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return try {
        OffsetDateTime.parse(value).format(DetailTimeFormatter)
    } catch (_: Exception) {
        value
    }
}

private val DetailTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
