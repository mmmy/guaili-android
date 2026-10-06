package com.gouge.guaili.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gouge.guaili.domain.ChannelTrend
import com.gouge.guaili.domain.KlineChartRow
import com.gouge.guaili.data.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.Saver
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Notifications
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val UpColor = Color(0xFF22C55E)
private val DownColor = Color(0xFFEF5350)
private val EmaNeutralColor = Color(0xFFE5E7EB)
private val UpperColor = Color(0xFFEF5350)
private val LowerColor = Color(0xFF26A69A)
private val ChannelFillColor = Color(0x332196F3)
private val WeakTopColor = Color(0xFFFFB020)
private val WeakBottomColor = Color(0xFF38BDF8)

private val EditorSessionSaver = Saver<PriceAlertEditorSession?, String>(
    save = { it?.let { session -> PriceAlertRepository.json.encodeToString(PriceAlertEditorSession.serializer(), session) } ?: "null" },
    restore = { if (it == "null") null else PriceAlertRepository.json.decodeFromString<PriceAlertEditorSession>(it) },
)

@Composable
fun KlineScreen(
    baseUrl: String, symbols: List<String>, intervals: List<String>, initialSymbol: String,
    initialInterval: String, refreshSeconds: Int, closedOnly: Boolean, onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var selectionBarHeight by remember { mutableIntStateOf(0) }
    val viewModel: KlineViewModel = viewModel(key = "kline:$baseUrl", factory = KlineViewModel.factory(baseUrl))
    val alertViewModel: PriceAlertsViewModel = viewModel(key = "price-alerts:$baseUrl", factory = PriceAlertsViewModel.factory(context, baseUrl))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val alertState by alertViewModel.state.collectAsStateWithLifecycle()
    val presetStore = remember(baseUrl) { PriceAlertStore(context, baseUrl) }
    var preset by remember(baseUrl) { mutableStateOf(presetStore.preset()) }
    var symbol by rememberSaveable(initialSymbol) { mutableStateOf(initialSymbol) }
    var interval by rememberSaveable(initialInterval) { mutableStateOf(initialInterval) }
    var channelVisible by rememberSaveable { mutableStateOf(true) }
    var amplitudeSignalVisible by rememberSaveable { mutableStateOf(true) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var cursorMode by rememberSaveable { mutableStateOf(false) }
    var cursorIndex by remember { mutableIntStateOf(-1) }
    var cursorPrice by remember { mutableStateOf<Double?>(null) }
    var cursorX by remember { mutableFloatStateOf(0f) }
    var selectedAlertId by rememberSaveable(baseUrl, symbol) { mutableStateOf<Long?>(null) }
    var drawingKind by rememberSaveable(symbol, interval) { mutableStateOf<String?>(null) }
    var drawingHasStart by remember { mutableStateOf(false) }
    var cancelGesture by remember { mutableIntStateOf(0) }
    var editor by rememberSaveable(baseUrl, stateSaver = EditorSessionSaver) { mutableStateOf<PriceAlertEditorSession?>(null) }
    var savingEditor by rememberSaveable { mutableStateOf(false) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var historyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var legacyId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var nextRefreshAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; cancelGesture++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(symbol, interval, closedOnly, foreground) {
        if (foreground) { viewModel.load(symbol, interval, closedOnly); alertViewModel.refresh(symbol) }
    }
    LaunchedEffect(baseUrl, foreground) { if (foreground) viewModel.loadAlerts() }
    LaunchedEffect(symbol, interval, closedOnly, refreshSeconds, foreground) {
        if (!foreground) {
            nextRefreshAt = 0L
            return@LaunchedEffect
        }
        runKlineRefreshSchedule(refreshSeconds, android.os.SystemClock::elapsedRealtime, { nextRefreshAt = it }) {
            viewModel.load(symbol, interval, closedOnly, force = true)
            alertViewModel.refresh(symbol)
        }
    }
    LaunchedEffect(alertState.notice) { alertState.notice?.let { snackbar.showSnackbar(it); alertViewModel.clearNotice() } }
    LaunchedEffect(alertState.pending, savingEditor) {
        if (savingEditor && editor != null && (editor?.existing?.id ?: 0L) !in alertState.pending) {
            selectedAlertId = editor?.existing?.id ?: alertState.alerts.firstOrNull { it.symbol == editor?.symbol }?.id
            editor = null; savingEditor = false
        }
    }
    LaunchedEffect(state.rows) {
        selectedIndex = state.rows.lastIndex
        if (cursorIndex !in state.rows.indices) { cursorMode = false; cursorIndex = -1; cursorPrice = null }
    }
    BackHandler {
        when {
            editor != null -> { editor = null; savingEditor = false }
            historyId != null -> historyId = null
            legacyId != null -> legacyId = null
            showList -> showList = false
            drawingKind != null -> { drawingKind = null; cancelGesture++ }
            selectedAlertId != null -> { selectedAlertId = null; cancelGesture++ }
            cursorMode -> cursorMode = false
            else -> onBack()
        }
    }
    val alerts = remember(alertState.alerts, symbol) { alertState.alerts.filter { it.symbol.equals(symbol, true) } }
    val legacyAlerts = remember(state.alerts, symbol) { state.alerts.filter { it.symbol.equals(symbol, true) } }
    val selectedAlert = alerts.firstOrNull { it.id == selectedAlertId }
    val market = alertState.markets[symbol]
    val selected = state.rows.getOrNull(if (cursorMode) cursorIndex else selectedIndex) ?: state.rows.lastOrNull()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = {
        SnackbarHost(snackbar, modifier = Modifier.padding(bottom = if (selectedAlert != null) with(density) { selectionBarHeight.toDp() } + 8.dp else 0.dp))
    }) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            KlineToolbar(symbol, interval, symbols, intervals, state.isLoading || state.isRefreshing,
                { symbol = it; cancelGesture++ }, { interval = it; cancelGesture++ },
                { viewModel.load(symbol, interval, closedOnly, force = true); alertViewModel.refresh(symbol) }, onBack,
                refreshSeconds, nextRefreshAt, foreground)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = state.rows.isNotEmpty(), modifier = Modifier.testTag("draw-horizontal-alert"), onClick = {
                    drawingKind = "horizontal_segment"; drawingHasStart = false; cursorMode = false; selectedAlertId = null; cancelGesture++
                }) { Icon(Icons.Outlined.HorizontalRule, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text("水平线") }
                TextButton(enabled = state.rows.isNotEmpty(), modifier = Modifier.testTag("draw-trend-alert"), onClick = {
                    drawingKind = "trend_segment"; drawingHasStart = false; cursorMode = false; selectedAlertId = null; cancelGesture++
                }) { Icon(Icons.AutoMirrored.Outlined.ShowChart, null); Spacer(Modifier.width(6.dp)); Text("趋势线") }
                TextButton(onClick = { showList = true; alertViewModel.refresh(symbol); viewModel.loadAlerts() }, modifier = Modifier.testTag("open-price-alerts")) {
                    Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(6.dp)); Text("警报 ${alerts.size + legacyAlerts.size}")
                }
            }
            if (drawingKind != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (drawingHasStart) "点选终点，完成线段" else "点选起点，开始绘图", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { drawingKind = null; cancelGesture++ }) { Text("取消绘图") }
            }
            alertState.pending[0L]?.let { pending ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("新警报 · ${pending.label()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val fields = pending.operation.body
                        val geometry = pending.geometry
                        if (geometry != null) {
                            editor = PriceAlertEditorSession(fields["symbol"]!!.jsonPrimitive.content, fields["interval"]!!.jsonPrimitive.content, geometry)
                            savingEditor = true
                        }
                    }) { Text("查看保存状态") }
                }
            }
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(channelVisible, { channelVisible = !channelVisible }, label = { Text("乖离通道") })
                Spacer(Modifier.width(12.dp)); KlineLegend(channelVisible); Spacer(Modifier.width(18.dp))
                FilterChip(amplitudeSignalVisible, { amplitudeSignalVisible = !amplitudeSignalVisible }, label = { Text("波幅信号") })
                Spacer(Modifier.width(12.dp)); SignalLegend(amplitudeSignalVisible)
            }
            SelectedCandleSummary(selected)
            (state.errorMessage ?: alertState.error)?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.load(symbol, interval, closedOnly, force = true); alertViewModel.refresh(symbol) }) { Text("重试") }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                when {
                    state.isLoading -> CircularProgressIndicator()
                    state.rows.isEmpty() -> Text("暂无 K 线数据")
                    else -> KlineChart(
                        rows = state.rows, alerts = alerts, pending = alertState.pending, selectedAlertId = selectedAlertId,
                        drawingKind = drawingKind, drawingExtend = preset.extend, tickSize = market?.step(), interval = interval, cancelGesture = cancelGesture,
                        cursorMode = cursorMode, cursorIndex = cursorIndex, cursorPrice = cursorPrice, cursorX = cursorX,
                        showChannel = channelVisible, showAmplitudeSignal = amplitudeSignalVisible, selectedIndex = selectedIndex,
                        onSelectedIndex = { selectedIndex = it }, onAlertSelected = { selectedAlertId = it; cursorMode = false },
                        onCursorModeChange = { cursorMode = it; if (!it) { cursorIndex = -1; cursorPrice = null } },
                        onCursorPosition = { i, price, x -> cursorIndex = i; cursorPrice = price; cursorX = x },
                        onAlertDragEnd = { id, geometry -> selectedAlertId = id; alertViewModel.move(id, geometry) },
                        onDrawingStart = { drawingHasStart = it },
                        onDrawComplete = { geometry -> drawingKind = null; editor = PriceAlertEditorSession(symbol, interval, geometry); savingEditor = false },
                        onAddAlert = { geometry -> cursorMode = false; editor = PriceAlertEditorSession(symbol, interval, geometry); savingEditor = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                selectedAlert?.let { alert ->
                    PriceAlertSelectionBar(alert, alertState.pending[alert.id], alert.id in alertState.undo,
                        onEdit = { editor = PriceAlertEditorSession(alert.symbol, alert.interval, alert.geometry, alert); savingEditor = false },
                        onToggle = {
                            if (alert.status != "active" && alert.expiresAt != null && alert.expiresAt <= System.currentTimeMillis()) {
                                editor = PriceAlertEditorSession(alert.symbol, alert.interval, alert.geometry, alert); savingEditor = false
                            } else alertViewModel.toggle(alert)
                        }, onHistory = { historyId = alert.id; alertViewModel.history(alert.id) }, onUndo = { alertViewModel.undo(alert.id) },
                        onDelete = { alertViewModel.delete(alert) }, onClose = { selectedAlertId = null; cancelGesture++ },
                        onRetry = { alertViewModel.retry(alert.id) }, onDiscard = { alertViewModel.discardFailed(alert.id) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).onSizeChanged { selectionBarHeight = it.height })
                }
            }
        }
    }
    editor?.let { session ->
        key(session) {
            PriceAlertEditorSheet(session, preset, alertState.markets[session.symbol], alertState.pending[session.existing?.id ?: 0L],
                onDismiss = { editor = null; savingEditor = false }, onSave = { fields, default ->
                    savingEditor = true
                    if (session.existing == null) alertViewModel.create(fields) else alertViewModel.edit(session.existing, fields)
                    if (default != null) scope.launch { runCatching { presetStore.savePreset(default) }.onSuccess { preset = default }.onFailure { snackbar.showSnackbar("警报已提交，默认预设保存失败") } }
                }, onRetry = { alertViewModel.retry(session.existing?.id ?: 0L) }, onDiscard = { alertViewModel.discardFailed(session.existing?.id ?: 0L); savingEditor = false })
        }
    }
    if (showList) PriceAlertListSheet(alerts, alertState.pending, alertState.error, { showList = false }, { selectedAlertId = it; showList = false }, { alertViewModel.refresh(symbol); viewModel.loadAlerts() },
        legacy = legacyAlerts, onLegacySelect = { legacyId = it; showList = false })
    legacyId?.let { id -> state.alerts.firstOrNull { it.id == id }?.let { alert ->
        LegacyPriceAlertSheet(alert, state.isAlertBusy, state.alertError, { legacyId = null },
            { patch -> viewModel.updateAlert(id, patch) }, { viewModel.deleteAlert(id) { if (it) legacyId = null } })
    } }
    historyId?.let { id -> alerts.firstOrNull { it.id == id }?.let { alert ->
        PriceAlertHistorySheet(alert, alertState.histories[id] ?: PriceAlertHistory(), { historyId = null }, { alertViewModel.history(id) }, { alertViewModel.history(id, older = true) })
    } }
}

@Composable
private fun KlineToolbar(
    symbol: String,
    interval: String,
    symbols: List<String>,
    intervals: List<String>,
    isBusy: Boolean,
    onSymbolSelected: (String) -> Unit,
    onIntervalSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    refreshSeconds: Int,
    nextRefreshAt: Long,
    foreground: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = "K-line",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            KlineSelector(symbol, symbols, onSymbolSelected)
            KlineSelector(formatInterval(interval), intervals, onIntervalSelected) {
                formatInterval(it)
            }
        }
        Box(Modifier.size(48.dp)) {
            IconButton(onClick = onRefresh, enabled = !isBusy, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh K-line", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            KlineRefreshIndicator(refreshSeconds, nextRefreshAt, foreground,
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(12.dp))
        }
    }
}

@Composable
private fun KlineSelector(
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    optionLabel: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(value, maxLines = 1)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun KlineLegend(visible: Boolean) {
    val contentColor = if (visible) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("EMA20", EmaNeutralColor, contentColor)
        LegendItem("Upper", UpperColor, contentColor)
        LegendItem("Lower", LowerColor, contentColor)
    }
}

@Composable
private fun LegendItem(label: String, color: Color, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 2.dp)
                .background(if (contentColor == MaterialTheme.colorScheme.outline) color.copy(alpha = .35f) else color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = contentColor, fontSize = 11.sp)
    }
}

@Composable
private fun SignalLegend(visible: Boolean) {
    val contentColor = if (visible) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("弱势顶", WeakTopColor, contentColor)
        LegendItem("弱势底", WeakBottomColor, contentColor)
    }
}

@Composable
private fun SelectedCandleSummary(row: KlineChartRow?) {
    val candle = row?.candle
    val channel = row?.channel
    val amplitudeSignal = row?.amplitudeSignal
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        SummaryValue("Time", candle?.openTimeMillis?.let(::formatChartDateTime) ?: "-")
        SummaryValue("O", formatPrice(candle?.open))
        SummaryValue("H", formatPrice(candle?.high), UpColor)
        SummaryValue("L", formatPrice(candle?.low), DownColor)
        SummaryValue("C", formatPrice(candle?.close))
        SummaryValue("EMA20", formatPrice(channel?.ema20))
        SummaryValue("乖离", channel?.guaili?.let { String.format(Locale.US, "%.2f ATR", it) } ?: "-")
        val signal = when {
            amplitudeSignal?.weakTop == true && amplitudeSignal.weakBottom -> "弱势顶 / 弱势底"
            amplitudeSignal?.weakTop == true -> "弱势顶"
            amplitudeSignal?.weakBottom == true -> "弱势底"
            else -> "-"
        }
        val signalColor = when {
            amplitudeSignal?.weakTop == true -> WeakTopColor
            amplitudeSignal?.weakBottom == true -> WeakBottomColor
            else -> null
        }
        SummaryValue("波幅", signal, signalColor)
        SummaryValue("Vol", formatCompact(candle?.volume))
    }
}

@Composable
private fun SummaryValue(label: String, value: String, valueColor: Color? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$label ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KlineChart(
    rows: List<KlineChartRow>,
    alerts: List<PriceAlertDto>,
    pending: Map<Long, PendingPriceAlert>,
    drawingKind: String?,
    drawingExtend: String,
    tickSize: java.math.BigDecimal?,
    interval: String,
    cancelGesture: Int,
    selectedAlertId: Long?,
    cursorMode: Boolean,
    cursorIndex: Int,
    cursorPrice: Double?,
    cursorX: Float,
    showChannel: Boolean,
    showAmplitudeSignal: Boolean,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    onAlertSelected: (Long?) -> Unit,
    onCursorModeChange: (Boolean) -> Unit,
    onCursorPosition: (Int, Double, Float) -> Unit,
    onAlertDragEnd: (Long, PriceAlertGeometry) -> Unit,
    onDrawingStart: (Boolean) -> Unit,
    onDrawComplete: (PriceAlertGeometry) -> Unit,
    onAddAlert: (PriceAlertGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCursorMode by rememberUpdatedState(cursorMode)
    val currentRows by rememberUpdatedState(rows)
    val timeIndex = remember(rows) { LongArray(rows.size) { rows[it].candle.openTimeMillis } }
    val currentTimes by rememberUpdatedState(timeIndex)
    val currentAlerts by rememberUpdatedState(alerts)
    val currentPending by rememberUpdatedState(pending)
    val currentSelected by rememberUpdatedState(selectedAlertId)
    val currentTick by rememberUpdatedState(tickSize)
    val currentChannel by rememberUpdatedState(showChannel)
    val currentSelect by rememberUpdatedState(onAlertSelected)
    val currentMove by rememberUpdatedState(onAlertDragEnd)
    var preview by remember { mutableStateOf<Pair<Long, PriceAlertGeometry>?>(null) }
    var firstDrawingPoint by remember { mutableStateOf<PriceAlertPoint?>(null) }
    var drawingPointer by remember { mutableStateOf<PriceAlertPoint?>(null) }
    var frozenRows by remember { mutableStateOf<List<KlineChartRow>?>(null) }
    var frozenTransform by remember { mutableStateOf<AlertChartTransform?>(null) }
    LaunchedEffect(drawingKind, cancelGesture) { firstDrawingPoint = null; drawingPointer = null; onDrawingStart(false) }

    var visibleBars by remember { mutableFloatStateOf(min(72, rows.size).toFloat().coerceAtLeast(12f)) }
    var rightOffsetBars by remember { mutableFloatStateOf(0f) }
    var verticalScale by remember { mutableFloatStateOf(1f) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }
    val chartBackground = MaterialTheme.colorScheme.background
    val alertAxisPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val leftPaddingPx = with(density) { 8.dp.toPx() }
    val rightPaddingPx = with(density) { 68.dp.toPx() }

    LaunchedEffect(rows.size) {
        visibleBars = min(72, rows.size).toFloat().coerceAtLeast(1f)
        rightOffsetBars = 0f
        verticalScale = 1f
    }

    Box(modifier = modifier) {
    Canvas(
        modifier = Modifier.fillMaxSize()
            .onSizeChanged {
                canvasWidth = it.width.coerceAtLeast(1)
                canvasHeight = it.height.coerceAtLeast(1)
            }
            .testTag("price-alert-chart")
            .pointerInput(canvasWidth, canvasHeight, drawingKind, cancelGesture, interval) {
                var lastAxisTapAt = 0L
                fun viewport() = calculateKlineViewport(currentRows.size, visibleBars, rightOffsetBars)
                fun bounds() = calculatePriceBounds(currentRows, viewport(), currentChannel, verticalScale)
                fun plotRight() = (canvasWidth - rightPaddingPx).coerceAtLeast(leftPaddingPx + 1f)
                fun transform(): AlertChartTransform {
                    val b = bounds()
                    return AlertChartTransform(currentTimes, viewport(), leftPaddingPx,
                        plotRight(), 10.dp.toPx(), canvasHeight * .70f, b.priceMin, b.priceMax, alertIntervalMillis(interval))
                }
                fun updateCursor(position: Offset) {
                    val v = viewport(); val x = position.x.coerceIn(leftPaddingPx, plotRight())
                    onCursorPosition(v.indexAtX(x, leftPaddingPx, (plotRight() - leftPaddingPx) / v.visibleSpan), transform().priceAt(position.y), x)
                }
                fun hit(position: Offset, mapping: AlertChartTransform): Pair<PriceAlertDto, Int>? {
                    if (position.x !in mapping.left..mapping.right || position.y !in mapping.top..mapping.bottom) return null
                    val selected = currentAlerts.firstOrNull { it.id == currentSelected }
                    val radius = 24.dp.toPx()
                    if (selected != null) {
                        val g = currentPending[selected.id]?.geometry ?: selected.geometry
                        listOf(g.first, g.second).forEachIndexed { index, point ->
                            val dx = mapping.xAt(point.timeMs) - position.x; val dy = mapping.yAt(point.price) - position.y
                            if (dx * dx + dy * dy <= radius * radius) return selected.copy(geometry = g) to index + 1
                        }
                    }
                    var best: Pair<PriceAlertDto, Int>? = null
                    var distance = radius * radius
                    currentAlerts.forEach { alert ->
                        val g = currentPending[alert.id]?.geometry ?: alert.geometry
                        mapping.segments(g).forEach { line ->
                            val d = line.distanceSquared(position.x, position.y)
                            if (d < distance) { distance = d; best = alert.copy(geometry = g) to 0 }
                        }
                    }
                    return best
                }
                fun zoomBy(factor: Float) {
                    if (!factor.isFinite() || factor <= 0f) return
                    visibleBars = (visibleBars / factor).coerceIn(min(12, currentRows.size).toFloat().coerceAtLeast(1f), currentRows.size.toFloat().coerceAtLeast(1f))
                }
                awaitEachGesture {
                    val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                    if (currentRows.isEmpty()) return@awaitEachGesture
                    val mapping = transform()
                    val startedInCursorMode = currentCursorMode && drawingKind == null
                    val candidate = if (startedInCursorMode || drawingKind != null) null else hit(down.position, mapping)
                    val downPoint = mapping.point(down.position.x, down.position.y)
                    val step = currentTick
                    val originalRows = currentRows
                    var lastPosition = down.position
                    var finalGeometry: PriceAlertGeometry? = null
                    var pinchDistance: Float? = null
                    var pinchCenter: Float? = null
                    var mode = if (drawingKind != null) 6 else if (startedInCursorMode) 3 else 0
                    var moved = false
                    val deadline = android.os.SystemClock.uptimeMillis() + 500L
                    try {
                        while (true) {
                            val event = if (mode == 0) {
                                withTimeoutOrNull((deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() } ?: run {
                                    if (candidate != null) { currentSelect(candidate.first.id); mode = 2 }
                                    else { mode = 3; onCursorModeChange(true); updateCursor(lastPosition) }
                                    null
                                }
                            } else awaitPointerEvent()
                            if (event == null) continue
                            val active = event.changes.filter { it.pressed }
                            if (active.size >= 2) {
                                mode = 4; moved = true; preview = null; frozenRows = null; frozenTransform = null; drawingPointer = null
                                val distance = (active[1].position - active[0].position).getDistance()
                                val center = (active[0].position.x + active[1].position.x) / 2f
                                pinchDistance?.let { if (it > 0) zoomBy(distance / it) }
                                pinchCenter?.let { before -> rightOffsetBars = (rightOffsetBars + (center - before) * visibleBars / (plotRight() - leftPaddingPx)).coerceIn(-(visibleBars - 3f).coerceAtLeast(0f), (currentRows.size - visibleBars).coerceAtLeast(0f)) }
                                pinchDistance = distance; pinchCenter = center
                                event.changes.forEach { it.consume() }; continue
                            }
                            if (mode == 4) { event.changes.forEach { it.consume() }; if (active.isEmpty()) break; continue }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                when (mode) {
                                    0 -> if (candidate != null) currentSelect(candidate.first.id) else if (down.position.x >= plotRight()) {
                                        val now = android.os.SystemClock.uptimeMillis(); if (now - lastAxisTapAt < 300) verticalScale = 1f; lastAxisTapAt = now
                                    } else {
                                        currentSelect(null)
                                        val v = viewport(); onSelectedIndex(v.indexAtX(down.position.x, leftPaddingPx, (plotRight() - leftPaddingPx) / v.visibleSpan))
                                    }
                                    2 -> if (candidate != null && moved && finalGeometry != null) currentMove(candidate.first.id, finalGeometry)
                                    3 -> if (startedInCursorMode && !moved) onCursorModeChange(false)
                                    6 -> if (change.position.x in mapping.left..mapping.right && change.position.y in mapping.top..mapping.bottom) {
                                        val point = mapping.point(change.position.x, change.position.y).let { it.copy(price = alignAlertPrice(it.price, step)) }
                                        val first = firstDrawingPoint
                                        if (first == null) { firstDrawingPoint = point; onDrawingStart(true) }
                                        else {
                                            val second = if (drawingKind == "horizontal_segment") point.copy(price = first.price) else point
                                            val geometry = PriceAlertGeometry(drawingKind!!, first, second, drawingExtend).normalized()
                                            if (geometry.valid()) { firstDrawingPoint = null; onDrawingStart(false); onDrawComplete(geometry) }
                                        }
                                    }
                                }
                                break
                            }
                            val dx = change.position.x - lastPosition.x; val dy = change.position.y - lastPosition.y
                            val slop = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            if (mode == 0 && slop) mode = if (candidate != null) 2 else if (down.position.x >= plotRight()) 5 else 1
                            when (mode) {
                                1 -> {
                                    moved = true
                                    rightOffsetBars = (rightOffsetBars + dx * visibleBars / (plotRight() - leftPaddingPx)).coerceIn(-(visibleBars - 3f).coerceAtLeast(0f), (currentRows.size - visibleBars).coerceAtLeast(0f))
                                    change.consume()
                                }
                                2 -> if (candidate != null) {
                                    moved = true; currentSelect(candidate.first.id)
                                    frozenRows = originalRows; frozenTransform = mapping
                                    val point = mapping.point(change.position.x, change.position.y)
                                    val geometry = movedAlertGeometry(candidate.first.geometry, candidate.second, downPoint, point)
                                    if (geometry != null) {
                                        finalGeometry = geometry.copy(first = geometry.first.copy(price = alignAlertPrice(geometry.first.price, step)), second = geometry.second.copy(price = alignAlertPrice(geometry.second.price, step)))
                                        preview = candidate.first.id to finalGeometry
                                    }
                                    change.consume()
                                }
                                3 -> if (slop || !startedInCursorMode) { moved = true; updateCursor(change.position); change.consume() }
                                5 -> { moved = true; verticalScale = (verticalScale * exp((-dy / 220f).toDouble()).toFloat()).coerceIn(.25f, 4f); change.consume() }
                                6 -> { drawingPointer = mapping.point(change.position.x, change.position.y); frozenRows = originalRows; frozenTransform = mapping; change.consume() }
                            }
                            lastPosition = change.position
                        }
                    } finally { preview = null; drawingPointer = null; frozenRows = null; frozenTransform = null }
                }
            }
    ) {
        val chartRows = frozenRows ?: rows
        if (chartRows.isEmpty()) return@Canvas

        val viewport = calculateKlineViewport(chartRows.size, visibleBars, rightOffsetBars)
        val visible = chartRows.subList(viewport.drawStart, viewport.endExclusive)
        val plotLeft = leftPaddingPx
        val plotRight = size.width - rightPaddingPx
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val priceTop = 10.dp.toPx()
        val priceBottom = size.height * .70f
        val volumeTop = size.height * .76f
        val volumeBottom = size.height - 27.dp.toPx()
        val priceValues = buildList {
            visible.forEach { row ->
                add(row.candle.high)
                add(row.candle.low)
                if (showChannel) {
                    row.channel.upper?.let(::add)
                    row.channel.lower?.let(::add)
                }
            }
        }
        val rawMin = priceValues.minOrNull() ?: 0.0
        val rawMax = priceValues.maxOrNull() ?: 1.0
        val rawRange = (rawMax - rawMin).coerceAtLeast(0.0000001)
        val pricePadding = (rawRange * .06).coerceAtLeast(0.0000001)
        val fittedRange = rawRange + pricePadding * 2.0
        val scaledRange = (fittedRange / verticalScale.toDouble()).coerceAtLeast(0.0000001)
        val priceCenter = (rawMax + rawMin) / 2.0
        val priceMin = frozenTransform?.minimum ?: (priceCenter - scaledRange / 2.0)
        val priceMax = frozenTransform?.maximum ?: (priceCenter + scaledRange / 2.0)
        val priceRange = priceMax - priceMin
        val slotWidth = plotWidth / viewport.visibleSpan
        val bodyWidth = (slotWidth * .62f).coerceIn(1.5.dp.toPx(), 13.dp.toPx())
        val gridColor = Color(0xFF2A333D)
        val labelColor = Color(0xFF8D99A6)

        fun xAt(localIndex: Int): Float = viewport.xForIndex(
            index = viewport.drawStart + localIndex,
            plotLeft = plotLeft,
            slotWidth = slotWidth,
        )
        fun yAt(price: Double): Float = priceBottom -
            (((price - priceMin) / priceRange).toFloat() * (priceBottom - priceTop))

        repeat(5) { gridIndex ->
            val fraction = gridIndex / 4f
            val y = priceTop + (priceBottom - priceTop) * fraction
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
            val price = priceMax - priceRange * fraction
            drawChartText(formatPrice(price), plotRight + 5.dp.toPx(), y + 4.dp.toPx(), labelColor)
        }
        drawLine(gridColor, Offset(plotLeft, volumeTop), Offset(plotRight, volumeTop), strokeWidth = 1f)

        drawContext.canvas.save()
        drawContext.canvas.clipRect(plotLeft, priceTop, plotRight, volumeBottom)

        if (showChannel) {
            val channelRows = visible.mapIndexedNotNull { index, row ->
                val upper = row.channel.upper
                val lower = row.channel.lower
                if (upper != null && lower != null) Triple(index, upper, lower) else null
            }
            if (channelRows.size > 1) {
                val fillPath = Path().apply {
                    val first = channelRows.first()
                    moveTo(xAt(first.first), yAt(first.second))
                    channelRows.drop(1).forEach { (index, upper, _) -> lineTo(xAt(index), yAt(upper)) }
                    channelRows.asReversed().forEach { (index, _, lower) -> lineTo(xAt(index), yAt(lower)) }
                    close()
                }
                drawPath(fillPath, ChannelFillColor)
                drawSeriesLine(channelRows.map { xAt(it.first) to yAt(it.second) }, UpperColor.copy(alpha = .65f))
                drawSeriesLine(channelRows.map { xAt(it.first) to yAt(it.third) }, LowerColor.copy(alpha = .65f))
            }
        }

        visible.forEachIndexed { index, row ->
            val candle = row.candle
            val x = xAt(index)
            val color = if (candle.close >= candle.open) UpColor else DownColor
            drawLine(color, Offset(x, yAt(candle.high)), Offset(x, yAt(candle.low)), strokeWidth = 1.dp.toPx())
            val openY = yAt(candle.open)
            val closeY = yAt(candle.close)
            val top = min(openY, closeY)
            val height = max(abs(openY - closeY), 1.5.dp.toPx())
            drawRect(
                color = color,
                topLeft = Offset(x - bodyWidth / 2f, top),
                size = Size(bodyWidth, height),
                )
            }
        if (showChannel) {
            visible.windowed(2).forEachIndexed { index, pair ->
                val first = pair[0].channel.ema20
                val second = pair[1].channel.ema20
                if (first != null && second != null) {
                    val color = when (pair[1].channel.trend) {
                        ChannelTrend.Long -> UpColor
                        ChannelTrend.Short -> DownColor
                        ChannelTrend.Neutral -> EmaNeutralColor
                    }
                    drawLine(
                        color,
                        Offset(xAt(index), yAt(first)),
                        Offset(xAt(index + 1), yAt(second)),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }

        if (showAmplitudeSignal) {
            visible.forEachIndexed { index, row ->
                val x = xAt(index)
                if (row.amplitudeSignal.weakTop) {
                    drawSignalMarker(
                        x = x,
                        y = yAt(row.candle.high) - 8.dp.toPx(),
                        pointsDown = true,
                        color = WeakTopColor,
                    )
                }
                if (row.amplitudeSignal.weakBottom) {
                    drawSignalMarker(
                        x = x,
                        y = yAt(row.candle.low) + 8.dp.toPx(),
                        pointsDown = false,
                        color = WeakBottomColor,
                    )
                }
            }
        }

        val maxVolume = visible.maxOfOrNull { it.candle.volume }?.coerceAtLeast(0.0000001) ?: 1.0
        visible.forEachIndexed { index, row ->
            val height = ((row.candle.volume / maxVolume).toFloat() * (volumeBottom - volumeTop))
            val color = if (row.candle.close >= row.candle.open) UpColor else DownColor
            drawRect(
                color = color.copy(alpha = .5f),
                topLeft = Offset(xAt(index) - bodyWidth / 2f, volumeBottom - height),
                size = Size(bodyWidth, height),
            )
        }
        drawContext.canvas.restore()

        drawChartText("VOL", plotLeft, volumeTop + 13.dp.toPx(), labelColor)

        val labelSteps = 4
        val firstCoreLocal = viewport.coreStart - viewport.drawStart
        val lastCoreLocal = visible.lastIndex
        repeat(labelSteps) { labelIndex ->
            val localIndex = if (labelSteps == 1) firstCoreLocal else {
                firstCoreLocal + (
                    labelIndex * ((lastCoreLocal - firstCoreLocal).toFloat() / (labelSteps - 1))
                ).toInt()
            }
            val x = xAt(localIndex)
            drawLine(gridColor, Offset(x, priceTop), Offset(x, volumeBottom), strokeWidth = 1f)
            val text = formatChartAxisTime(visible[localIndex].candle.openTimeMillis)
            drawChartText(text, x - 18.dp.toPx(), size.height - 7.dp.toPx(), labelColor)
        }

        val mapping = frozenTransform ?: AlertChartTransform(timeIndex, viewport,
            plotLeft, plotRight, priceTop, priceBottom, priceMin, priceMax, alertIntervalMillis(interval))
        drawContext.canvas.save()
        drawContext.canvas.clipRect(plotLeft, priceTop, plotRight, priceBottom)
        fun drawAlert(g: PriceAlertGeometry, color: Color, width: Float, ghost: Boolean = false, handles: Boolean = false, label: String = "") {
            val segments = mapping.segments(g)
            segments.forEach { line -> drawLine(color, Offset(line.x1, line.y1), Offset(line.x2, line.y2), width,
                pathEffect = if (ghost) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null) }
            if (handles) listOf(g.first, g.second).forEach { point ->
                val position = Offset(mapping.xAt(point.timeMs), mapping.yAt(point.price))
                drawCircle(chartBackground, 7.dp.toPx(), position)
                drawCircle(Color(0xFF60A5FA), 5.dp.toPx(), position)
            }
            if (label.isNotBlank()) segments.lastOrNull()?.let { line -> drawChartText(label, (line.x2 - 100.dp.toPx()).coerceAtLeast(plotLeft), line.y2 - 8.dp.toPx(), color) }
        }
        alerts.forEach { alert ->
            val draft = if (preview?.first == alert.id) preview?.second else pending[alert.id]?.geometry
            val selected = alert.id == selectedAlertId
            val color = if (alert.status == "triggered") Color(0xFF8D99A6) else alertColor(alert.color).copy(alpha = if (alert.status == "disabled") .55f else 1f)
            if (draft != null && draft != alert.geometry) drawAlert(alert.geometry, color.copy(alpha = .35f), 1.dp.toPx(), ghost = true)
            drawAlert(draft ?: alert.geometry, color, (alert.lineWidth + if (selected) .5f else 0f).dp.toPx(),
                ghost = draft != null, handles = selected, label = alert.label)
        }
        firstDrawingPoint?.let { first ->
            val second = drawingPointer
            if (second != null && second.timeMs != first.timeMs) {
                val end = if (drawingKind == "horizontal_segment") second.copy(price = first.price) else second
                drawAlert(PriceAlertGeometry(drawingKind ?: "horizontal_segment", first, end, "none").normalized(), Color(0xFF22AB94), 2.dp.toPx())
            }
            drawCircle(Color(0xFF60A5FA), 6.dp.toPx(), Offset(mapping.xAt(first.timeMs), mapping.yAt(first.price)))
        }
        drawContext.canvas.restore()
        val labelAlert = alerts.firstOrNull { it.id == selectedAlertId }
        labelAlert?.let { alert ->
            val g = preview?.takeIf { it.first == alert.id }?.second ?: pending[alert.id]?.geometry ?: alert.geometry
            val time = mapping.timeAt(plotRight)
            val price = g.priceAt(time) ?: g.second.price
            val y = mapping.yAt(price)
            if (y in priceTop..priceBottom) drawAlertAxisPrice(price, plotRight + 5.dp.toPx(), y + 4.dp.toPx(),
                size.width - plotRight - 8.dp.toPx(), tickSize, Color(0xFF60A5FA), alertAxisPaint)
        }

        if (cursorMode && cursorPrice != null && cursorIndex in rows.indices) {
            val x = cursorX.coerceIn(plotLeft, plotRight)
            val y = yAt(cursorPrice)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            drawLine(
                Color(0xFF94A3B8),
                Offset(x, priceTop),
                Offset(x, volumeBottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
            drawLine(
                Color(0xFF94A3B8),
                Offset(plotLeft, y),
                Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
            drawChartText(formatPrice(cursorPrice), plotRight + 5.dp.toPx(), y + 4.dp.toPx(), Color(0xFF60A5FA))
        }
    }
    if (cursorMode && cursorPrice != null && canvasHeight > 0) {
        val viewport = calculateKlineViewport(rows.size, visibleBars, rightOffsetBars)
        val bounds = calculatePriceBounds(rows, viewport, showChannel, verticalScale)
        val chartTop = with(density) { 10.dp.toPx() }
        val chartBottom = canvasHeight * .70f
        val y = chartBottom - (((cursorPrice - bounds.priceMin) / bounds.priceRange).toFloat() * (chartBottom - chartTop))
        val buttonHalf = with(density) { 24.dp.toPx() }
        IconButton(
            onClick = {
                val mapping = AlertChartTransform(timeIndex, viewport, leftPaddingPx, canvasWidth - rightPaddingPx, chartTop, chartBottom, bounds.priceMin, bounds.priceMax, alertIntervalMillis(interval))
                val time = mapping.timeAt(cursorX)
                val price = alignAlertPrice(cursorPrice, tickSize)
                onAddAlert(PriceAlertGeometry("horizontal_segment", PriceAlertPoint(time, price), PriceAlertPoint(time + alertIntervalMillis(interval) * 12, price), drawingExtend))
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, (y - buttonHalf).toInt()) },
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Outlined.Add, contentDescription = "在此价格设置警报")
            }
        }
    }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSignalMarker(
    x: Float,
    y: Float,
    pointsDown: Boolean,
    color: Color,
) {
    val halfWidth = 5.dp.toPx()
    val halfHeight = 4.dp.toPx()
    val path = Path().apply {
        if (pointsDown) {
            moveTo(x - halfWidth, y - halfHeight)
            lineTo(x + halfWidth, y - halfHeight)
            lineTo(x, y + halfHeight)
        } else {
            moveTo(x - halfWidth, y + halfHeight)
            lineTo(x + halfWidth, y + halfHeight)
            lineTo(x, y - halfHeight)
        }
        close()
    }
    drawPath(path, color)
}

internal data class KlineViewport(
    val coreStart: Int,
    val drawStart: Int,
    val endExclusive: Int,
    val visibleSpan: Float,
    val fractionalOffset: Float,
) {
    fun xForIndex(index: Int, plotLeft: Float, slotWidth: Float): Float =
        plotLeft + slotWidth * (index - coreStart + .5f + fractionalOffset)

    fun indexAtX(x: Float, plotLeft: Float, slotWidth: Float): Int {
        val relativeIndex = floor((x - plotLeft) / slotWidth - fractionalOffset).toInt()
        return (coreStart + relativeIndex).coerceIn(drawStart, endExclusive - 1)
    }
}

internal fun calculateKlineViewport(
    rowCount: Int,
    visibleBars: Float,
    rightOffsetBars: Float,
): KlineViewport {
    require(rowCount > 0)
    val visibleSpan = visibleBars.coerceIn(1f, rowCount.toFloat())
    // Keep a small future margin so the latest candles can be dragged toward
    // the left edge, as in TradingView, instead of stopping at the right edge.
    val minimumOffset = -(visibleSpan - 3f).coerceAtLeast(0f)
    val maximumOffset = (rowCount - visibleSpan).coerceAtLeast(0f)
    val offset = rightOffsetBars.coerceIn(minimumOffset, maximumOffset)
    if (offset < 0f) {
        val coreCount = min(ceil(visibleSpan).toInt(), rowCount)
        val coreStart = (rowCount - coreCount).coerceAtLeast(0)
        return KlineViewport(
            coreStart = coreStart,
            drawStart = (coreStart - 1).coerceAtLeast(0),
            endExclusive = rowCount,
            visibleSpan = visibleSpan,
            fractionalOffset = offset,
        )
    }

    val wholeOffset = floor(offset).toInt()
    val fractionalOffset = offset - wholeOffset
    val coreCount = min(ceil(visibleSpan).toInt(), rowCount)
    val endExclusive = (rowCount - wholeOffset).coerceIn(1, rowCount)
    val coreStart = (endExclusive - coreCount).coerceAtLeast(0)

    return KlineViewport(
        coreStart = coreStart,
        drawStart = (coreStart - 1).coerceAtLeast(0),
        endExclusive = endExclusive,
        visibleSpan = visibleSpan,
        fractionalOffset = fractionalOffset,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesLine(
    points: List<Pair<Float, Float>>,
    color: Color,
) {
    points.windowed(2).forEach { pair ->
        drawLine(
            color,
            Offset(pair[0].first, pair[0].second),
            Offset(pair[1].first, pair[1].second),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChartText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
) {
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            text,
            x,
            y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                textSize = 10.sp.toPx()
            },
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAlertAxisPrice(
    price: Double, x: Float, y: Float, width: Float, step: java.math.BigDecimal?, color: Color, paint: Paint,
) {
    if (!price.isFinite() || width <= 0f) return
    paint.color = color.toArgb()
    paint.textSize = 10.sp.toPx()
    var label: String? = null
    val precision = ((step?.stripTrailingZeros()?.scale() ?: 4) + 2).coerceIn(0, 12)
    for (digits in precision downTo 0) {
        val candidate = String.format(Locale.US, "%.${digits}f", price)
        if (candidate.toDoubleOrNull() != 0.0 && paint.measureText(candidate) <= width) { label = candidate; break }
    }
    if (label == null) for (digits in 6 downTo 1) {
        val candidate = String.format(Locale.US, "%.${digits}g", price)
        if (paint.measureText(candidate) <= width) { label = candidate; break }
    }
    label?.let { text -> drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) } }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private fun formatPrice(value: Double?): String {
    if (value == null || !value.isFinite()) return "-"
    val digits = when {
        kotlin.math.abs(value) >= 1_000 -> 2
        kotlin.math.abs(value) >= 1 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun formatCompact(value: Double?): String {
    if (value == null) return "-"
    return when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.2fK", value / 1_000)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private fun formatChartDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Shanghai")).format(
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"),
    )

private fun formatChartAxisTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Shanghai")).format(
        DateTimeFormatter.ofPattern("MM-dd HH:mm"),
    )

private fun formatDeviceDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    )

private fun parseDeviceDateTime(value: String): Long? = runCatching {
    java.time.LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

private fun directionLabel(direction: String): String = when (direction) {
    "cross_up" -> "上穿"
    "cross_down" -> "下穿"
    else -> "任意"
}

private data class PriceBounds(val priceMin: Double, val priceMax: Double, val priceRange: Double)

private fun calculatePriceBounds(
    rows: List<KlineChartRow>,
    viewport: KlineViewport,
    showChannel: Boolean,
    verticalScale: Float,
): PriceBounds {
    val visible = rows.subList(viewport.drawStart, viewport.endExclusive)
    val values = buildList {
        visible.forEach { row ->
            add(row.candle.high)
            add(row.candle.low)
            if (showChannel) {
                row.channel.upper?.let(::add)
                row.channel.lower?.let(::add)
            }
        }
    }
    val rawMin = values.minOrNull() ?: 0.0
    val rawMax = values.maxOrNull() ?: 1.0
    val rawRange = (rawMax - rawMin).coerceAtLeast(0.0000001)
    val padding = (rawRange * .06).coerceAtLeast(0.0000001)
    val scaledRange = ((rawRange + padding * 2.0) / verticalScale.toDouble()).coerceAtLeast(0.0000001)
    val center = (rawMax + rawMin) / 2.0
    return PriceBounds(center - scaledRange / 2.0, center + scaledRange / 2.0, scaledRange)
}
