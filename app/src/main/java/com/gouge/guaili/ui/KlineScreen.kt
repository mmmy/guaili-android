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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.gouge.guaili.data.AlertDto
import com.gouge.guaili.data.AlertPatchDto
import com.gouge.guaili.data.AlertRequestDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
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

@Composable
fun KlineScreen(
    baseUrl: String,
    symbols: List<String>,
    intervals: List<String>,
    initialSymbol: String,
    initialInterval: String,
    refreshSeconds: Int,
    closedOnly: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val viewModel: KlineViewModel = viewModel(
        key = "kline:$baseUrl",
        factory = KlineViewModel.factory(baseUrl),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var symbol by rememberSaveable(initialSymbol) { mutableStateOf(initialSymbol) }
    var interval by rememberSaveable(initialInterval) { mutableStateOf(initialInterval) }
    var channelVisible by rememberSaveable { mutableStateOf(true) }
    var amplitudeSignalVisible by rememberSaveable { mutableStateOf(true) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var cursorMode by rememberSaveable { mutableStateOf(false) }
    var cursorIndex by remember { mutableIntStateOf(-1) }
    var cursorPrice by remember { mutableStateOf<Double?>(null) }
    var cursorX by remember { mutableFloatStateOf(0f) }
    var selectedAlertId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAlertEditor by rememberSaveable { mutableStateOf(false) }
    var editorPrice by rememberSaveable { mutableStateOf("") }
    var editorDirection by rememberSaveable { mutableStateOf("cross_any") }
    var editorExpiresAt by rememberSaveable { mutableStateOf("") }
    var editorWebhook by rememberSaveable { mutableStateOf("") }
    var editorMessage by rememberSaveable { mutableStateOf("{\"symbol\":\"{{ticker}}\",\"price\":\"{{price}}\"}") }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isForeground = true
                Lifecycle.Event.ON_STOP -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(symbol, interval, closedOnly) {
        viewModel.load(symbol, interval, closedOnly)
    }
    val currentAlerts = state.alerts.filter { it.symbol.equals(symbol, true) && it.interval == interval }
    val selectedAlert = currentAlerts.firstOrNull { it.id == selectedAlertId }
    fun openEditor(alert: AlertDto?, price: Double) {
        selectedAlertId = alert?.id
        editorPrice = formatPrice(if (alert != null) alert.price else price)
        editorDirection = alert?.direction ?: "cross_any"
        editorExpiresAt = alert?.expiresAt?.let(::formatDeviceDateTime) ?: ""
        editorWebhook = alert?.webhookUrl ?: ""
        editorMessage = alert?.messageTemplate ?: "{\"symbol\":\"{{ticker}}\",\"price\":\"{{price}}\"}"
        showAlertEditor = true
    }
    LaunchedEffect(symbol, interval, closedOnly, refreshSeconds, isForeground) {
        if (!isForeground) return@LaunchedEffect
        while (true) {
            delay(refreshSeconds.coerceAtLeast(1) * 1_000L)
            viewModel.load(symbol, interval, closedOnly, force = true)
        }
    }
    LaunchedEffect(state.rows) {
        selectedIndex = state.rows.lastIndex
        if (cursorIndex !in state.rows.indices) {
            cursorMode = false
            cursorIndex = -1
            cursorPrice = null
        }
    }

    val selected = if (cursorMode) {
        state.rows.getOrNull(cursorIndex) ?: state.rows.lastOrNull()
    } else {
        state.rows.getOrNull(selectedIndex) ?: state.rows.lastOrNull()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            KlineToolbar(
                symbol = symbol,
                interval = interval,
                symbols = symbols,
                intervals = intervals,
                isBusy = state.isLoading || state.isRefreshing,
                onSymbolSelected = { symbol = it },
                onIntervalSelected = { interval = it },
                onRefresh = { viewModel.load(symbol, interval, closedOnly, force = true) },
                onBack = onBack,
            )
            selectedAlert?.let { alert ->
                AlertFloatingToolbar(
                    alert = alert,
                    onEdit = { openEditor(alert, alert.price) },
                    onToggle = {
                        viewModel.updateAlert(alert.id, AlertPatchDto(status = if (alert.status == "active") "disabled" else "active"))
                    },
                    onDelete = { viewModel.deleteAlert(alert.id) { if (it) selectedAlertId = null } },
                    onHistory = { viewModel.loadAlertEvents(alert.id) },
                    events = state.alertEvents[alert.id].orEmpty(),
                    onDismiss = { selectedAlertId = null },
                )
            }
            if (state.isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                FilterChip(
                    selected = channelVisible,
                    onClick = { channelVisible = !channelVisible },
                    label = { Text("乖离通道") },
                )
                Spacer(modifier = Modifier.width(12.dp))
                KlineLegend(channelVisible)
                Spacer(modifier = Modifier.width(18.dp))
                FilterChip(
                    selected = amplitudeSignalVisible,
                    onClick = { amplitudeSignalVisible = !amplitudeSignalVisible },
                    label = { Text("波幅信号") },
                )
                Spacer(modifier = Modifier.width(10.dp))
                SignalLegend(amplitudeSignalVisible)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectedCandleSummary(selected)

            state.errorMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                viewModel.load(symbol, interval, closedOnly, force = true)
                            },
                        ) { Text("Retry") }
                    }
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator()
                    state.rows.isEmpty() && state.errorMessage == null -> Text(
                        "No K-line data",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.rows.isNotEmpty() -> KlineChart(
                        rows = state.rows,
                        alerts = currentAlerts,
                        selectedAlertId = selectedAlertId,
                        cursorMode = cursorMode,
                        cursorIndex = cursorIndex,
                        cursorPrice = cursorPrice,
                        cursorX = cursorX,
                        showChannel = channelVisible,
                        showAmplitudeSignal = amplitudeSignalVisible,
                        selectedIndex = selectedIndex,
                        onSelectedIndex = { selectedIndex = it },
                        onAlertSelected = { selectedAlertId = it },
                        onCursorModeChange = { enabled ->
                            cursorMode = enabled
                            if (!enabled) {
                                cursorIndex = -1
                                cursorPrice = null
                            }
                        },
                        onCursorPosition = { index, price, x ->
                            cursorIndex = index
                            cursorPrice = price
                            cursorX = x
                        },
                        onAlertDragEnd = { id, price ->
                            currentAlerts.firstOrNull { it.id == id }?.let { alert ->
                                selectedAlertId = id
                                editorPrice = formatPrice(price)
                                editorDirection = alert.direction
                                editorExpiresAt = alert.expiresAt?.let(::formatDeviceDateTime) ?: ""
                                editorWebhook = alert.webhookUrl
                                editorMessage = alert.messageTemplate
                                showAlertEditor = true
                            }
                        },
                        onAddAlert = { price ->
                            cursorMode = false
                            cursorIndex = -1
                            cursorPrice = null
                            openEditor(null, price)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showAlertEditor) {
        AlertEditorDialog(
            existing = selectedAlert,
            price = editorPrice,
            direction = editorDirection,
            expiresAt = editorExpiresAt,
            webhookUrl = editorWebhook,
            messageTemplate = editorMessage,
            busy = state.isAlertBusy,
            error = state.alertError,
            onPriceChange = { editorPrice = it },
            onDirectionChange = { editorDirection = it },
            onExpiresAtChange = { editorExpiresAt = it },
            onWebhookChange = { editorWebhook = it },
            onMessageChange = { editorMessage = it },
            onDismiss = { showAlertEditor = false },
            onSave = {
                val price = editorPrice.toDoubleOrNull()
                val expires = editorExpiresAt.trim().takeIf { it.isNotEmpty() }?.let(::parseDeviceDateTime)
                if (price != null && price > 0.0) {
                    if (selectedAlert == null) {
                        viewModel.createAlert(AlertRequestDto(symbol, interval, price, editorDirection, expires, editorWebhook, editorMessage)) {
                            if (it != null) { showAlertEditor = false; selectedAlertId = it.id }
                        }
                    } else {
                        viewModel.updateAlert(selectedAlert.id, AlertPatchDto(price, editorDirection, expires, editorWebhook, editorMessage, "active")) {
                            if (it != null) showAlertEditor = false
                        }
                    }
                }
            },
        )
    }
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
        IconButton(onClick = onRefresh, enabled = !isBusy) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh K-line")
        }
    }
}

@Composable
private fun AlertFloatingToolbar(
    alert: AlertDto,
    events: List<com.gouge.guaili.data.AlertEventDto>,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 5.dp,
        shadowElevation = 5.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text("${formatPrice(alert.price)}  ${directionLabel(alert.direction)}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit alert") }
            IconButton(onClick = onToggle) { Icon(if (alert.status == "active") Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = "Toggle alert") }
            IconButton(onClick = { onHistory(); showHistory = true }) { Icon(Icons.Outlined.History, contentDescription = "Alert history") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete alert") }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
        }
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("触发记录") },
            text = {
                Column {
                    if (events.isEmpty()) Text("暂无触发记录")
                    events.forEach { event ->
                        Text("${formatDeviceDateTime(event.triggeredAt)}  ${formatPrice(event.triggerPrice)}  ${directionLabel(event.direction)}")
                        Text(event.deliveryStatus ?: "等待投递", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除警报？") },
            text = { Text("删除后，该警报的触发记录也会一并删除。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                Button(onClick = { confirmDelete = false; onDelete() }) { Text("删除") }
            },
        )
    }
}

@Composable
private fun AlertEditorDialog(
    existing: AlertDto?,
    price: String,
    direction: String,
    expiresAt: String,
    webhookUrl: String,
    messageTemplate: String,
    busy: Boolean,
    error: String?,
    onPriceChange: (String) -> Unit,
    onDirectionChange: (String) -> Unit,
    onExpiresAtChange: (String) -> Unit,
    onWebhookChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "设置警报" else "修改警报") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(price, onPriceChange, label = { Text("价格") }, singleLine = true)
                Text("触发方向", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("cross_up" to "上穿", "cross_down" to "下穿", "cross_any" to "任意").forEach { (value, label) ->
                        RadioButton(selected = direction == value, onClick = { onDirectionChange(value) })
                        Text(label)
                    }
                }
                OutlinedTextField(expiresAt, onExpiresAtChange, label = { Text("过期时间（设备时间，可空）") }, singleLine = true)
                OutlinedTextField(webhookUrl, onWebhookChange, label = { Text("Webhook URL") }, singleLine = true)
                OutlinedTextField(messageTemplate, onMessageChange, label = { Text("JSON 消息模板") }, minLines = 2, maxLines = 4)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = onSave, enabled = !busy) { Text(if (busy) "保存中" else "保存") }
        },
    )
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
    alerts: List<AlertDto>,
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
    onAlertDragEnd: (Long, Double) -> Unit,
    onAddAlert: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCursorMode by rememberUpdatedState(cursorMode)
    var visibleBars by remember { mutableFloatStateOf(min(72, rows.size).toFloat().coerceAtLeast(12f)) }
    var rightOffsetBars by remember { mutableFloatStateOf(0f) }
    var verticalScale by remember { mutableFloatStateOf(1f) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }
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
            .pointerInput(rows.size, canvasWidth, canvasHeight, alerts) {
                var lastAxisTapAt = 0L

                fun viewport() = calculateKlineViewport(rows.size, visibleBars, rightOffsetBars)
                fun bounds() = calculatePriceBounds(rows, viewport(), showChannel, verticalScale)
                fun plotRight() = (canvasWidth - rightPaddingPx).coerceAtLeast(leftPaddingPx + 1f)
                fun priceAt(y: Float): Double {
                    val priceBounds = bounds()
                    val top = 10.dp.toPx()
                    val bottom = canvasHeight * .70f
                    return priceBounds.priceMax - ((y - top) / (bottom - top)).coerceIn(0f, 1f) * priceBounds.priceRange
                }
                fun updateCursor(position: Offset) {
                    val currentViewport = viewport()
                    val right = plotRight()
                    val x = position.x.coerceIn(leftPaddingPx, right)
                    val slotWidth = (right - leftPaddingPx) / currentViewport.visibleSpan
                    onCursorPosition(
                        currentViewport.indexAtX(x, leftPaddingPx, slotWidth),
                        priceAt(position.y),
                        x,
                    )
                }
                fun alertAt(position: Offset): AlertDto? {
                    val priceBounds = bounds()
                    val price = priceAt(position.y)
                    return alerts.minByOrNull { abs(it.price - price) }
                        ?.takeIf { abs(it.price - price) <= priceBounds.priceRange * .035 }
                }
                fun zoomBy(factor: Float) {
                    if (!factor.isFinite() || factor <= 0f) return
                    val minimumVisible = min(12, rows.size).toFloat().coerceAtLeast(1f)
                    visibleBars = (visibleBars / factor).coerceIn(
                        minimumVisible,
                        rows.size.toFloat().coerceAtLeast(1f),
                    )
                }

                awaitEachGesture {
                    val firstEvent = awaitPointerEvent()
                    val down = firstEvent.changes.firstOrNull { it.pressed }
                        ?: return@awaitEachGesture
                    val startedInCursorMode = currentCursorMode
                    val alertCandidate = if (startedInCursorMode) null else alertAt(down.position)
                    val downPosition = down.position
                    var lastPosition = down.position
                    var lastAlertPrice = alertCandidate?.price
                    var previousPinchDistance: Float? = null
                    var previousPinchCenter: Float? = null
                    var mode = if (startedInCursorMode) 3 else 0
                    var moved = false
                    val deadline = android.os.SystemClock.uptimeMillis() + 500L

                    while (true) {
                        val event = if (mode == 0) {
                            val remaining = (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() } ?: run {
                                mode = 3
                                onCursorModeChange(true)
                                updateCursor(lastPosition)
                                null
                            }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) continue

                        val active = event.changes.filter { it.pressed }
                        if (active.size >= 2) {
                            mode = 4
                            moved = true
                            val first = active[0].position
                            val second = active[1].position
                            val dx = second.x - first.x
                            val dy = second.y - first.y
                            val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            val centerX = (first.x + second.x) / 2f
                            previousPinchDistance?.let { previous ->
                                if (previous > 0f) zoomBy(distance / previous)
                            }
                            if (!startedInCursorMode && !currentCursorMode) {
                                previousPinchCenter?.let { previousCenter ->
                                    val plotWidth = (plotRight() - leftPaddingPx).coerceAtLeast(1f)
                                    rightOffsetBars = (rightOffsetBars + (centerX - previousCenter) * visibleBars / plotWidth).coerceIn(
                                        -(visibleBars - 3f).coerceAtLeast(0f),
                                        (rows.size - visibleBars).coerceAtLeast(0f),
                                    )
                                }
                            }
                            previousPinchDistance = distance
                            previousPinchCenter = centerX
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == 4) {
                            event.changes.forEach { it.consume() }
                            if (active.isEmpty()) break
                            continue
                        }

                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            when (mode) {
                                0 -> {
                                    if (alertCandidate != null) {
                                        onAlertSelected(alertCandidate.id)
                                    } else if (downPosition.x >= plotRight()) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (now - lastAxisTapAt <= 300L) verticalScale = 1f
                                        lastAxisTapAt = now
                                    } else {
                                        val currentViewport = viewport()
                                        val slotWidth = (plotRight() - leftPaddingPx) / currentViewport.visibleSpan
                                        onSelectedIndex(currentViewport.indexAtX(downPosition.x, leftPaddingPx, slotWidth))
                                    }
                                }
                                2 -> if (alertCandidate != null && lastAlertPrice != null) {
                                    onAlertDragEnd(alertCandidate.id, lastAlertPrice)
                                }
                                3 -> if (startedInCursorMode && !moved) {
                                    onCursorModeChange(false)
                                }
                            }
                            break
                        }

                        val dx = change.position.x - lastPosition.x
                        val dy = change.position.y - lastPosition.y
                        val totalDx = change.position.x - downPosition.x
                        val totalDy = change.position.y - downPosition.y
                        val passedSlop = abs(totalDx) > 8.dp.toPx() || abs(totalDy) > 8.dp.toPx()
                        if (mode == 0 && passedSlop) {
                            mode = when {
                                alertCandidate != null -> 2
                                downPosition.x >= plotRight() -> 5
                                else -> 1
                            }
                        }

                        when (mode) {
                            1 -> {
                                moved = true
                                val plotWidth = (plotRight() - leftPaddingPx).coerceAtLeast(1f)
                                rightOffsetBars = (rightOffsetBars + dx * visibleBars / plotWidth).coerceIn(
                                    -(visibleBars - 3f).coerceAtLeast(0f),
                                    (rows.size - visibleBars).coerceAtLeast(0f),
                                )
                                change.consume()
                            }
                            2 -> {
                                moved = true
                                lastAlertPrice = priceAt(change.position.y)
                                change.consume()
                            }
                            3 -> {
                                if (passedSlop || !startedInCursorMode) {
                                    moved = true
                                    updateCursor(change.position)
                                    change.consume()
                                }
                            }
                            5 -> {
                                moved = true
                                verticalScale = (verticalScale * exp((-dy / 220f).toDouble()).toFloat()).coerceIn(.25f, 4f)
                                change.consume()
                            }
                        }
                        lastPosition = change.position
                    }
                }
            }
    ) {
        if (rows.isEmpty()) return@Canvas

        val viewport = calculateKlineViewport(rows.size, visibleBars, rightOffsetBars)
        val visible = rows.subList(viewport.drawStart, viewport.endExclusive)
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
        val priceMin = priceCenter - scaledRange / 2.0
        val priceMax = priceCenter + scaledRange / 2.0
        val priceRange = scaledRange
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

        alerts.forEach { alert ->
            val y = yAt(alert.price)
            if (y in priceTop..priceBottom) {
                val selected = alert.id == selectedAlertId
                val color = when {
                    selected -> Color(0xFF60A5FA)
                    alert.status == "disabled" -> Color(0xFF64748B)
                    alert.status == "triggered" -> Color(0xFFF59E0B)
                    else -> Color(0xFFA78BFA)
                }
                drawLine(
                    color,
                    Offset(plotLeft, y),
                    Offset(plotRight + 58.dp.toPx(), y),
                    strokeWidth = if (selected) 2.5.dp.toPx() else 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
                )
                drawChartText(
                    formatPrice(alert.price),
                    plotRight + 5.dp.toPx(), y + 4.dp.toPx(), color,
                )
            }
        }

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
            onClick = { onAddAlert(cursorPrice) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, (y - buttonHalf).toInt()) },
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Outlined.Add, contentDescription = "Add alert")
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
