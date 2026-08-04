package com.gouge.guaili.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val UpColor = Color(0xFF22C55E)
private val DownColor = Color(0xFFEF5350)
private val EmaNeutralColor = Color(0xFFE5E7EB)
private val UpperColor = Color(0xFFEF5350)
private val LowerColor = Color(0xFF26A69A)
private val ChannelFillColor = Color(0x332196F3)

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
    val viewModel: KlineViewModel = viewModel(
        key = "kline:$baseUrl",
        factory = KlineViewModel.factory(baseUrl),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var symbol by rememberSaveable(initialSymbol) { mutableStateOf(initialSymbol) }
    var interval by rememberSaveable(initialInterval) { mutableStateOf(initialInterval) }
    var channelVisible by rememberSaveable { mutableStateOf(true) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
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
    LaunchedEffect(symbol, interval, closedOnly, refreshSeconds, isForeground) {
        if (!isForeground) return@LaunchedEffect
        while (true) {
            delay(refreshSeconds.coerceAtLeast(1) * 1_000L)
            viewModel.load(symbol, interval, closedOnly, force = true)
        }
    }
    LaunchedEffect(state.rows) {
        selectedIndex = state.rows.lastIndex
    }

    val selected = state.rows.getOrNull(selectedIndex) ?: state.rows.lastOrNull()

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
                        showChannel = channelVisible,
                        selectedIndex = selectedIndex,
                        onSelectedIndex = { selectedIndex = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
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
private fun SelectedCandleSummary(row: KlineChartRow?) {
    val candle = row?.candle
    val channel = row?.channel
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
    showChannel: Boolean,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleBars by remember { mutableFloatStateOf(min(72, rows.size).toFloat().coerceAtLeast(12f)) }
    var rightOffsetBars by remember { mutableFloatStateOf(0f) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val leftPaddingPx = with(density) { 8.dp.toPx() }
    val rightPaddingPx = with(density) { 68.dp.toPx() }

    LaunchedEffect(rows.size) {
        visibleBars = min(72, rows.size).toFloat().coerceAtLeast(1f)
        rightOffsetBars = 0f
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasWidth = it.width.coerceAtLeast(1) }
            .pointerInput(rows.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val plotWidth = (canvasWidth - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
                    val oldVisible = visibleBars
                    val minimumVisible = min(12, rows.size).toFloat().coerceAtLeast(1f)
                    visibleBars = (visibleBars / zoom).coerceIn(
                        minimumVisible,
                        rows.size.toFloat().coerceAtLeast(1f),
                    )
                    val barsPerPixel = oldVisible / plotWidth
                    rightOffsetBars = (rightOffsetBars + pan.x * barsPerPixel).coerceIn(
                        0f,
                        (rows.size - visibleBars).coerceAtLeast(0f),
                    )
                }
            }
            .pointerInput(rows.size) {
                detectTapGestures { position ->
                    val viewport = calculateKlineViewport(rows.size, visibleBars, rightOffsetBars)
                    val plotWidth = (canvasWidth - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
                    val slotWidth = plotWidth / viewport.visibleSpan
                    onSelectedIndex(
                        viewport.indexAtX(position.x, leftPaddingPx, slotWidth),
                    )
                }
            },
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
        val pricePadding = ((rawMax - rawMin) * .06).takeIf { it > 0.0 } ?: rawMax * .001
        val priceMin = rawMin - pricePadding
        val priceMax = rawMax + pricePadding
        val priceRange = (priceMax - priceMin).coerceAtLeast(0.0000001)
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

        val selectedLocal = selectedIndex - viewport.drawStart
        if (selectedLocal in visible.indices) {
            val selected = visible[selectedLocal]
            val x = xAt(selectedLocal)
            val y = yAt(selected.candle.close)
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
        }
    }
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
    val maximumOffset = (rowCount - visibleSpan).coerceAtLeast(0f)
    val offset = rightOffsetBars.coerceIn(0f, maximumOffset)
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
