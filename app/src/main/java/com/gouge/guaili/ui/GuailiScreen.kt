package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gouge.guaili.domain.GuailiCell
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GuailiScreen(viewModel: GuailiViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedCell by remember { mutableStateOf<GuailiCell?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        Toolbar(
            state = state,
            onRefresh = viewModel::refresh,
            onOpenSettings = { showSettings = true },
        )

        state.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 6.dp),
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            GuailiTable(
                state = state,
                onCellClick = { selectedCell = it },
            )
        }
    }

    selectedCell?.let { cell ->
        CellDetailSheet(
            cell = cell,
            onDismiss = { selectedCell = null },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onSave = viewModel::saveSettings,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun Toolbar(
    state: GuailiTableState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(
            onClick = onRefresh,
            enabled = !state.isLoading && !state.isRefreshing,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(
                text = if (state.isRefreshing) "Refreshing" else "Refresh",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onOpenSettings,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(
                text = "Settings",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = statusText(state),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun statusText(state: GuailiTableState): String {
    val loadState = when {
        state.isRefreshing -> "Refreshing"
        state.isStale -> "Stale"
        else -> "Idle"
    }
    val dimensions = "${state.symbols.size}x${state.intervals.size}"
    val updatedAt = state.lastUpdatedAt?.let { "Updated ${formatTime(it)}" } ?: "Not updated"
    return "$loadState | $dimensions | $updatedAt"
}

private fun formatTime(epochMillis: Long): String =
    TimeFormatter.format(Instant.ofEpochMilli(epochMillis))

private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
