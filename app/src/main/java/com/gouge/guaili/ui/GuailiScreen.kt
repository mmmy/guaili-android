package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gouge.guaili.domain.GuailiCell
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun GuailiScreen(viewModel: GuailiViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowSize = LocalWindowInfo.current.containerSize
    val compactHeader = windowSize.width > windowSize.height
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedCell by remember { mutableStateOf<GuailiCell?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
    var intervalGroup by remember { mutableStateOf(IntervalGroup.All) }
    val visibleIntervals = remember(state.intervals, intervalGroup) {
        filterIntervals(state.intervals, intervalGroup)
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onSave = { settings ->
                viewModel.saveSettings(settings)
                scope.launch { snackbarHostState.showSnackbar("Settings saved") }
            },
            onDismiss = { showSettings = false },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
        ) {
            Toolbar(
                state = state,
                compact = compactHeader,
                onRefresh = viewModel::refresh,
                onOpenSettings = { showSettings = true },
                onOpenLegend = { showLegend = true },
            )

            state.errorMessage?.let { error ->
                ErrorBanner(
                    message = error,
                    hasCachedData = state.cells.isNotEmpty(),
                    onRetry = viewModel::refresh,
                )
            }

            IntervalFilters(
                selected = intervalGroup,
                onSelected = { intervalGroup = it },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 8.dp),
            ) {
                GuailiTable(
                    state = state,
                    intervals = visibleIntervals,
                    onCellClick = { selectedCell = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (state.isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    selectedCell?.let { cell ->
        CellDetailSheet(
            cell = cell,
            onDismiss = { selectedCell = null },
        )
    }

    if (showLegend) {
        LegendDialog(onDismiss = { showLegend = false })
    }
}

@Composable
private fun Toolbar(
    state: GuailiTableState,
    compact: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLegend: () -> Unit,
) {
    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            Text(
                text = "Guaili Matrix",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(18.dp))
            StatusIndicator(state = state, modifier = Modifier.weight(1f))
            ToolbarActions(
                state = state,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onOpenLegend = onOpenLegend,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Guaili Matrix",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ToolbarActions(
                state = state,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onOpenLegend = onOpenLegend,
            )
        }
        StatusIndicator(state = state, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
    }
}

@Composable
private fun ToolbarActions(
    state: GuailiTableState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLegend: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenLegend) {
            Icon(Icons.Outlined.Info, contentDescription = "Color and marker legend")
        }
        IconButton(
            onClick = onRefresh,
            enabled = !state.isLoading && !state.isRefreshing,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun StatusIndicator(state: GuailiTableState, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor(state), CircleShape),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = statusText(state),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    hasCachedData: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = if (hasCachedData) "Showing cached data. $message" else message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun IntervalFilters(
    selected: IntervalGroup,
    onSelected: (IntervalGroup) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        IntervalGroup.entries.forEach { group ->
            FilterChip(
                selected = selected == group,
                onClick = { onSelected(group) },
                label = { Text(group.label) },
            )
        }
    }
}

@Composable
private fun LegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Matrix legend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendLine(Color(0xFF007A1A), "Positive value")
                LegendLine(Color(0xFFBE0041), "Negative value")
                LegendLine(Color(0xFFFACC15), "Header marker: interval has an unfinished candle")
                Text("Dimmed cells did not pass the ATR rank filter.")
                Text("Arrows show the long or short trend direction.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun LegendLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text)
    }
}

internal enum class IntervalGroup(val label: String) {
    All("All"),
    Short("Short"),
    Medium("Medium"),
    Long("Long"),
}

internal fun filterIntervals(intervals: List<String>, group: IntervalGroup): List<String> {
    if (group == IntervalGroup.All) return intervals

    return intervals.filter { interval ->
        val minutes = interval.toIntOrNull()
        when (group) {
            IntervalGroup.All -> true
            IntervalGroup.Short -> minutes != null && minutes <= 15
            IntervalGroup.Medium -> minutes != null && minutes in 16..240
            IntervalGroup.Long -> minutes == null || minutes > 240
        }
    }
}

private fun statusText(state: GuailiTableState): String {
    val loadState = when {
        state.isLoading -> "Loading"
        state.isRefreshing -> "Refreshing"
        state.isStale -> "Stale"
        state.errorMessage != null -> "Offline"
        else -> "Live"
    }
    val updatedAt = state.lastUpdatedAt?.let { "Updated ${formatTime(it)}" } ?: "Not updated"
    return "$loadState  |  $updatedAt  |  ${state.symbols.size} symbols"
}

@Composable
private fun statusColor(state: GuailiTableState): Color = when {
    state.isLoading || state.isRefreshing -> MaterialTheme.colorScheme.primary
    state.isStale || state.errorMessage != null -> Color(0xFFF59E0B)
    else -> Color(0xFF22C55E)
}

private fun formatTime(epochMillis: Long): String =
    TimeFormatter.format(Instant.ofEpochMilli(epochMillis))

private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
