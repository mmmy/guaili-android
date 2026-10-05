package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.LayoutMode
import com.gouge.guaili.settings.MarketView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun GuailiScreen(
    viewModel: GuailiViewModel,
    signalsViewModel: ServerSignalsViewModel,
    requestedKlineTarget: KlineTarget? = null,
    onRequestedKlineConsumed: () -> Unit = {},
    onOpenAppSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signalsState by signalsViewModel.state.collectAsStateWithLifecycle()
    val signalListState = rememberLazyListState()
    val showSignals = signalsState.preferences.view == MarketView.SignalsV2
    var klineFromSignals by rememberSaveable { mutableStateOf(false) }
    val windowSize = LocalWindowInfo.current.containerSize
    val compactHeader = windowSize.width > windowSize.height
    val compactFilters = compactHeader && windowSize.height / LocalDensity.current.density < 360f
    val tableLayout = resolveTableLayout(state.settings.layoutMode, compactHeader)
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedCell by remember { mutableStateOf<GuailiCell?>(null) }
    var klineSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    var klineInterval by rememberSaveable { mutableStateOf<String?>(null) }
    val klineTarget = klineSymbol?.let { symbol ->
        klineInterval?.let { interval -> KlineTarget(symbol, interval) }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var intervalGroup by rememberSaveable { mutableStateOf(IntervalGroup.All) }
    val visibleIntervals = remember(state.intervals, intervalGroup) {
        filterIntervals(state.intervals, intervalGroup)
    }

    LaunchedEffect(requestedKlineTarget) {
        requestedKlineTarget?.let { target ->
            klineFromSignals = false
            klineSymbol = target.symbol
            klineInterval = target.interval
            onRequestedKlineConsumed()
        }
    }

    val contentActive = klineTarget == null && !showSettings && !showHelp
    DisposableEffect(lifecycleOwner, viewModel, signalsViewModel, contentActive, showSignals) {
        fun update(active: Boolean) {
            viewModel.setForeground(active && contentActive && !showSignals)
            signalsViewModel.setForeground(active && contentActive && showSignals)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> update(true)
                Lifecycle.Event.ON_STOP -> update(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        update(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
            signalsViewModel.setForeground(false)
        }
    }

    klineTarget?.let { target ->
        KlineScreen(
            baseUrl = state.settings.baseUrl,
            symbols = if (klineFromSignals) (signalsState.availableSymbols + target.symbol).distinct() else state.settings.symbols,
            intervals = if (klineFromSignals) (signalsState.snapshot?.response?.results
                ?.firstOrNull { it.symbol == target.symbol }?.perIntervalQuality.orEmpty().map { it.interval } +
                state.settings.intervals + target.interval).distinct() else state.settings.intervals,
            initialSymbol = target.symbol,
            initialInterval = target.interval,
            refreshSeconds = state.settings.autoRefreshSeconds,
            closedOnly = if (klineFromSignals) false else state.settings.closedOnly,
            onBack = {
                klineSymbol = null
                klineInterval = null
            },
        )
        return
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onSave = { settings ->
                viewModel.saveSettingsAndAwait(settings)
                scope.launch { snackbarHostState.showSnackbar("Settings saved") }
            },
            onDismiss = { showSettings = false },
        )
        return
    }

    if (showHelp) {
        GuailiSignalHelpScreen(onBack = { showHelp = false })
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
            MarketViewSelector(signalsState.preferences.view, signalsViewModel::setView)
            if (showSignals) {
                MarketSignalsContent(
                    state = signalsState,
                    listState = signalListState,
                    onRefresh = signalsViewModel::refresh,
                    onToggleSymbol = signalsViewModel::toggleSymbol,
                    onSelectAll = signalsViewModel::selectAllSymbols,
                    onToggleKind = signalsViewModel::toggleKind,
                    onOpenSettings = { onOpenAppSettings?.invoke() ?: run { showSettings = true } },
                    onOpenKline = { target ->
                        klineFromSignals = true
                        klineSymbol = target.symbol
                        klineInterval = target.interval
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Toolbar(
                    state = state,
                    compact = compactHeader,
                    compactFilters = compactFilters,
                    intervalGroup = intervalGroup,
                    onSelectIntervalGroup = { intervalGroup = it },
                    onRefresh = viewModel::refresh,
                    tableLayout = tableLayout,
                    onToggleLayout = {
                        val next = when (tableLayout) {
                            TableLayout.Table -> LayoutMode.Groups
                            TableLayout.Groups -> LayoutMode.Table
                        }
                        viewModel.setLayoutMode(next)
                    },
                    onOpenSettings = { onOpenAppSettings?.invoke() ?: run { showSettings = true } },
                    onOpenHelp = { showHelp = true },
                )

                state.errorMessage?.let { error ->
                    ErrorBanner(
                        message = error,
                        hasCachedData = state.cells.isNotEmpty(),
                        onRetry = viewModel::refresh,
                    )
                }

                if (!compactFilters) {
                    IntervalFilters(
                        selected = intervalGroup,
                        onSelected = { intervalGroup = it },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 8.dp),
                ) {
                    when (tableLayout) {
                        TableLayout.Table -> GuailiTable(
                            state = state,
                            intervals = visibleIntervals,
                            onCellClick = { selectedCell = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        TableLayout.Groups -> GuailiGroupedTable(
                            state = state,
                            intervals = visibleIntervals,
                            onCellClick = { selectedCell = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
    }

    selectedCell?.let { cell ->
        CellDetailSheet(
            cell = cell,
            onOpenKline = {
                klineFromSignals = false
                klineSymbol = cell.symbol
                klineInterval = cell.interval
                selectedCell = null
            },
            onDismiss = { selectedCell = null },
        )
    }

}

data class KlineTarget(
    val symbol: String,
    val interval: String,
)

@Composable
private fun Toolbar(
    state: GuailiTableState,
    compact: Boolean,
    compactFilters: Boolean,
    intervalGroup: IntervalGroup,
    onSelectIntervalGroup: (IntervalGroup) -> Unit,
    onRefresh: () -> Unit,
    tableLayout: TableLayout,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val singleRow = !compactFilters && compact && maxWidth.value / LocalDensity.current.fontScale >= 700f
        if (singleRow) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(18.dp))
                StatusIndicator(state = state, modifier = Modifier.weight(1f))
                ToolbarActions(
                    state = state,
                    onRefresh = onRefresh,
                    tableLayout = tableLayout,
                    onToggleLayout = onToggleLayout,
                    onOpenSettings = onOpenSettings,
                    onOpenHelp = onOpenHelp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compactFilters) 2.dp else 6.dp, bottom = if (compactFilters) 0.dp else 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Guaili Matrix",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ToolbarActions(
                        state = state,
                        onRefresh = onRefresh,
                        tableLayout = tableLayout,
                        onToggleLayout = onToggleLayout,
                        onOpenSettings = onOpenSettings,
                        onOpenHelp = onOpenHelp,
                    )
                }
                if (compactFilters) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        StatusIndicator(state, Modifier.weight(1f), compact = true)
                        CompactIntervalFilter(intervalGroup, onSelectIntervalGroup)
                    }
                } else {
                    StatusIndicator(state = state, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolbarActions(
    state: GuailiTableState,
    onRefresh: () -> Unit,
    tableLayout: TableLayout,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleLayout) {
            Icon(
                imageVector = when (tableLayout) {
                    TableLayout.Table -> Icons.Outlined.GridView
                    TableLayout.Groups -> Icons.AutoMirrored.Outlined.ViewList
                },
                contentDescription = when (tableLayout) {
                    TableLayout.Table -> "Switch to symbol groups"
                    TableLayout.Groups -> "Switch to compact table"
                },
            )
        }
        IconButton(onClick = onOpenHelp) {
            Icon(Icons.Outlined.Info, contentDescription = "乖离信号帮助")
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
private fun StatusIndicator(state: GuailiTableState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor(state), CircleShape),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = statusText(state, compact),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactIntervalFilter(selected: IntervalGroup, onSelected: (IntervalGroup) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Period filter: ${selected.label}" },
        ) {
            Text(selected.label)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            IntervalGroup.entries.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.label) },
                    onClick = { onSelected(group); expanded = false },
                )
            }
        }
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

internal enum class IntervalGroup(val label: String) {
    All("All"),
    Short("Short"),
    Medium("Medium"),
    Long("Long"),
}

internal fun filterIntervals(intervals: List<String>, group: IntervalGroup): List<String> {
    if (group == IntervalGroup.All) return intervals

    return intervals.filter { interval ->
        val minutes = intervalMinutes(interval)
        when (group) {
            IntervalGroup.All -> true
            IntervalGroup.Short -> minutes != null && minutes <= 15.0
            IntervalGroup.Medium -> minutes != null && minutes > 15.0 && minutes <= 240.0
            IntervalGroup.Long -> minutes == null || minutes > 240.0
        }
    }
}

private fun intervalMinutes(interval: String): Double? {
    val normalized = interval.trim().uppercase()
    val multiplier = when (normalized.lastOrNull()) {
        'S' -> 1.0 / 60.0
        'D' -> 24.0 * 60.0
        'W' -> 7.0 * 24.0 * 60.0
        else -> return normalized.toDoubleOrNull()
    }
    val value = normalized.dropLast(1).toDoubleOrNull() ?: return null
    return value * multiplier
}

private fun statusText(state: GuailiTableState, compact: Boolean = false): String {
    val loadState = when {
        state.isLoading -> "Loading"
        state.isRefreshing -> "Refreshing"
        state.isStale -> "Stale"
        state.errorMessage != null -> "Offline"
        else -> "Live"
    }
    val updatedAt = state.lastUpdatedAt?.let { "Updated ${formatTime(it)}" } ?: "Not updated"
    if (compact) {
        val time = state.lastUpdatedAt?.let(::formatTime) ?: "Not updated"
        return "$loadState · $time · ${if (state.settings.closedOnly) "Closed" else "Live candles"}"
    }
    val candleMode = if (state.settings.closedOnly) "Closed only" else "Live candles"
    return "$loadState  |  $candleMode  |  $updatedAt  |  ${state.symbols.size} symbols"
}

@Composable
private fun statusColor(state: GuailiTableState): Color = when {
    state.isLoading || state.isRefreshing -> MaterialTheme.colorScheme.primary
    state.isStale || state.errorMessage != null || state.lastUpdatedAt == null -> Color(0xFFF59E0B)
    else -> Color(0xFF22C55E)
}

private fun formatTime(epochMillis: Long): String =
    TimeFormatter.format(Instant.ofEpochMilli(epochMillis))

private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
