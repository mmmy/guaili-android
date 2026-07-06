package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.GuailiCell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellDetailSheet(
    cell: GuailiCell,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = "${cell.symbol} ${formatInterval(cell.interval)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            DetailLine("symbol", cell.symbol)
            DetailLine("interval", cell.interval)
            DetailLine("value", cell.value?.toString())
            DetailLine("guaili", cell.guaili?.toString())
            DetailLine("ma", cell.ma?.toString())
            DetailLine("atr14", cell.atr14?.toString())
            DetailLine("atrRank", cell.atrRank?.toString())
            DetailLine("rankFilter", cell.rankFilter?.toString())
            DetailLine("longTrend", cell.longTrend?.toString())
            DetailLine("shortTrend", cell.shortTrend?.toString())
            DetailLine("isClosed", cell.isClosed?.toString())
            DetailLine("openTime", cell.openTime)
            DetailLine("closeTime", cell.closeTime)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(92.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
