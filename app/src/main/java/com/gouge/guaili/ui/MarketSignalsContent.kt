package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gouge.guaili.data.ServerSignalsResponse
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.MarketView
import com.gouge.guaili.signals.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LocalSignalPalette = staticCompositionLocalOf { SignalCardStyle.Dark }

/** The app owns its theme independently of the launcher/widget night mode. */
@Composable
private fun appSignalPalette(): SignalCardPalette {
    val colors = MaterialTheme.colorScheme
    fun Color.argb() = toArgb().toLong() and 0xFFFFFFFFL
    return SignalCardPalette(
        background = colors.background.argb(),
        card = colors.surfaceVariant.argb(),
        badge = colors.outlineVariant.argb(),
        primary = colors.onSurface.argb(),
        secondary = colors.onSurfaceVariant.argb(),
        positive = colors.secondary.argb(),
        negative = colors.error.argb(),
        accent = colors.primary.argb(),
        warning = if (colors.background.luminance() < 0.5f) SignalCardStyle.Dark.warning else SignalCardStyle.Light.warning,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketViewSelector(selected: MarketView, onSelect: (MarketView) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        MarketView.entries.forEachIndexed { index, view ->
            SegmentedButton(
                selected = selected == view,
                onClick = { onSelect(view) },
                shape = SegmentedButtonDefaults.itemShape(index, MarketView.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.testTag(if (view == MarketView.Table) "market-view-table" else "market-view-v2"),
            ) { Text(if (view == MarketView.Table) "表格" else "v2 信号") }
        }
    }
}

@Composable
internal fun MarketSignalsContent(
    state: MarketSignalsUiState,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onToggleSymbol: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleKind: (GuailiSignalKind) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenKline: (KlineTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStatus by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var symbolMenu by remember { mutableStateOf(false) }
    val display = state.presentation
    val response = state.snapshot?.response
    val palette = appSignalPalette()
    CompositionLocalProvider(LocalSignalPalette provides palette, LocalContentColor provides Color(palette.primary)) {
        Column(modifier.testTag("market-signals-v2").background(Color(palette.background)).padding(10.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth / LocalDensity.current.fontScale < 320.dp
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (narrow) "信号 v2 · ${display.signals.size}条" else "乖离信号 v2 · ${display.signals.size}条",
                        Modifier.weight(1f), fontSize = SignalCardStyle.HeaderFontSp.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(when { state.isRefreshing -> "刷新中"; state.refreshError != null -> "失败"; else -> signalTime(display.fetchedAt) },
                        Modifier.padding(horizontal = 4.dp).clickable(role = Role.Button) { showStatus = !showStatus },
                        fontSize = 9.sp, color = Color(if (state.refreshError != null) palette.warning else palette.secondary), maxLines = 1)
                    Text(serverSignalStatusLabel(display, narrow),
                        Modifier.testTag("market-v2-status").padding(horizontal = 4.dp)
                            .clickable(onClickLabel = "查看信号数据状态", role = Role.Button) { showStatus = !showStatus },
                        fontSize = 9.sp, color = Color(if (display.status == "ready") palette.accent else palette.warning), maxLines = 1)
                    Text("编辑", Modifier.testTag("market-v2-edit").padding(horizontal = 4.dp)
                        .clickable(role = Role.Button) { showFilters = !showFilters }, fontSize = 10.sp, color = Color(palette.accent))
                    Row(Modifier.testTag("market-v2-refresh").padding(start = 4.dp)
                        .clickable(enabled = !state.isRefreshing, onClickLabel = "刷新 v2 信号", role = Role.Button, onClick = onRefresh),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Refresh, "刷新 v2 信号", Modifier.size(14.dp), tint = Color(palette.accent))
                        if (!narrow) Text("刷新", fontSize = 11.sp, color = Color(palette.accent))
                    }
                }
            }
            if (showFilters) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        FilterChip(selected = true, onClick = { symbolMenu = true }, label = { Text("品种 ${state.symbols.size}") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(palette.accent).copy(alpha = 0.1f),
                                selectedLabelColor = Color(palette.accent),
                                containerColor = Color(palette.background),
                                labelColor = Color(palette.secondary),
                            ),
                            modifier = Modifier.testTag("market-v2-symbols"))
                        DropdownMenu(expanded = symbolMenu, onDismissRequest = { symbolMenu = false }) {
                            DropdownMenuItem(text = { Text("选择全部品种") }, onClick = onSelectAll)
                            state.availableSymbols.forEach { symbol ->
                                DropdownMenuItem(text = { Text(symbol.removeSuffix("USDT")) }, onClick = { onToggleSymbol(symbol) },
                                    modifier = Modifier.testTag("market-v2-symbol-$symbol"),
                                    trailingIcon = { if (symbol in state.symbols) Icon(Icons.Outlined.Check, "已选择") })
                            }
                            DropdownMenuItem(text = { Text("完成") }, onClick = { symbolMenu = false })
                        }
                    }
                    GuailiSignalKind.entries.forEach { kind ->
                        FilterChip(selected = kind in state.preferences.kinds, onClick = { onToggleKind(kind) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(palette.accent).copy(alpha = 0.1f),
                                selectedLabelColor = Color(palette.accent),
                                containerColor = Color(palette.background),
                                labelColor = Color(palette.secondary),
                            ),
                            label = { Text(when (kind) {
                                GuailiSignalKind.Extreme -> "乖离共振"
                                GuailiSignalKind.Compression -> "近均线"
                                GuailiSignalKind.Conflict -> "长短分歧"
                            }) }, modifier = Modifier.testTag("market-v2-kind-${kind.name}"))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("服务器计算 · 实时动态K · ${response?.let(::serverMovingAverageLabel) ?: "均线参数待获取"}",
                        Modifier.weight(1f), fontSize = 10.sp, color = Color(palette.secondary))
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "行情服务设置", tint = Color(palette.accent)) }
                }
            }
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("market-v2-loading"))
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 3.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(SignalCardStyle.CardSpacingDp.dp)) {
                state.preferenceError?.let { error -> item("preference-error") { SignalNotice(error) } }
                if (showStatus && display.signals.isNotEmpty()) display.warning?.let { warning -> item("quality-warning") { SignalNotice(warning) } }
                if (showStatus) item("status") {
                    Column(Modifier.fillMaxWidth().testTag("market-v2-status-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("数据状态", style = MaterialTheme.typography.titleMedium)
                        Text(display.detail, style = MaterialTheme.typography.bodyMedium)
                        Text("手机自动刷新：${state.autoRefreshSeconds}秒（在行情设置中修改）", style = MaterialTheme.typography.bodySmall)
                        Text(state.clockMessage, style = MaterialTheme.typography.bodySmall)
                        state.snapshot?.let { snapshot ->
                            Text("来源：${snapshot.baseUrl}", style = MaterialTheme.typography.bodySmall)
                            Text("服务器采样：${signalTime(display.sampledAt, true)}\n手机获取：${signalTime(display.fetchedAt, true)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("过期或不可用的周期不参与信号展示。首次观测时间是服务器采样观察时间。",
                            style = MaterialTheme.typography.bodySmall, color = Color(palette.secondary))
                        response?.results?.filter { it.symbol in state.symbols }?.forEach { row ->
                            HorizontalDivider()
                            Text("${row.symbol.removeSuffix("USDT")} · ${row.perIntervalQuality.count { it.availability == "ready" }}个周期可判断",
                                style = MaterialTheme.typography.titleSmall)
                            serverSignalQualityLines(row).forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = Color(palette.warning)) }
                        }
                        HorizontalDivider()
                    }
                }
                if (display.signals.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("market-v2-empty"),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (state.isRefreshing && state.snapshot == null) "正在获取服务器信号…" else display.message,
                            fontSize = SignalCardStyle.TitleFontSp.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                        Text(display.detail, fontSize = SignalCardStyle.BodyFontSp.sp, color = Color(palette.secondary), maxLines = 2)
                        display.warning?.let { SignalNotice(it) }
                    }
                }
                if (response != null) items(display.signals, key = { "${it.symbol}/${it.signal.id}" }) { item ->
                    ServerSignalCard(item, response, display.nowMillis, onOpenKline)
                }
            }
        }
    }
}

@Composable
private fun SignalNotice(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(vertical = 4.dp), fontSize = 10.sp,
        color = Color(LocalSignalPalette.current.warning))
}

@Composable
private fun ServerSignalCard(item: ServerSignalItem, response: ServerSignalsResponse, now: Long?, onOpenKline: (KlineTarget) -> Unit) {
    var expanded by rememberSaveable(item.symbol, item.signal.id) { mutableStateOf(false) }
    val signal = item.signal
    val row = response.results.firstOrNull { it.symbol == item.symbol }
    val presentation = serverSignalPresentation(item, response, now) ?: return
    val palette = LocalSignalPalette.current
    Column(Modifier.fillMaxWidth().testTag("market-v2-card-${item.symbol}-${signal.id}")
        .background(Color(palette.card))) {
        CompactSignalCard(presentation, serverMovingAverageLabel(response), expanded,
            onOpenKline = { onOpenKline(KlineTarget(item.symbol, signal.anchorInterval)) },
            onToggleDetails = { expanded = !expanded },
            key = "${item.symbol}-${signal.id}")
        if (expanded) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = Color(palette.badge))
                Text("首次观测：${signalTime(signal.firstObservedAt, true)}", style = MaterialTheme.typography.bodySmall)
                Text("参与周期与动态乖离", style = MaterialTheme.typography.titleSmall)
                signal.runs.forEach { run ->
                    Text(when (run.direction) { "positive" -> "正乖离"; "negative" -> "负乖离"; else -> "近均线" },
                        style = MaterialTheme.typography.labelLarge)
                    run.intervals.forEach { interval ->
                        val evidence = row?.perIntervalQuality?.firstOrNull { it.interval == interval }
                        Text("${serverDisplayInterval(interval)} · 数值 ${evidence?.value ?: "—"} · 乖离 ${evidence?.guaili?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                val matches = row?.signals.orEmpty().filter { candidate -> validServerSignal(candidate) &&
                    candidate.runs.flatMap { it.intervals }.all { period -> row?.perIntervalQuality?.any {
                        it.interval == period && it.availability == "ready"
                    } == true }
                }
                Text("该品种完整匹配：${matches.size}项", style = MaterialTheme.typography.titleSmall)
                matches.forEach { candidate ->
                    Text("${serverSignalTitle(candidate)} · ${serverSignalRange(candidate)}", style = MaterialTheme.typography.bodySmall)
                }
                row?.let(::serverSignalQualityLines).orEmpty().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(palette.warning))
                }
                TextButton(onClick = { expanded = false }) { Text("收起详情", color = Color(palette.accent)) }
            }
        }
    }
}

@Composable
private fun CompactSignalCard(signal: GuailiSignal, maLabel: String, expanded: Boolean,
    onOpenKline: () -> Unit, onToggleDetails: () -> Unit, key: String) {
    val palette = LocalSignalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 320.dp
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("market-v2-kline-$key")
            .combinedClickable(onClickLabel = "查看${signal.symbol} ${serverDisplayInterval(signal.anchorInterval)} K线",
                onClick = onOpenKline, onLongClickLabel = "查看信号详情", onLongClick = onToggleDetails)
            .padding(horizontal = SignalCardStyle.HorizontalPaddingDp.dp, vertical = SignalCardStyle.VerticalPaddingDp.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(signal.symbol.removeSuffix("USDT"), Modifier.width(SignalCardStyle.SymbolWidthDp.dp * LocalDensity.current.fontScale),
                    fontSize = SignalCardStyle.TitleFontSp.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(signalTitle(signal), Modifier.weight(1f), fontSize = SignalCardStyle.TitleFontSp.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold, color = Color(palette.titleColor(signal)), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text("${signal.totalLevelCount}级", Modifier.testTag("market-v2-expand-$key")
                    .background(Color(palette.badge), RectangleShape)
                    .clickable(onClickLabel = if (expanded) "收起信号详情" else "展开信号详情", role = Role.Button, onClick = onToggleDetails)
                    .padding(horizontal = 4.dp, vertical = 2.dp), fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold, color = Color(palette.secondary), maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            if (signal.kind == GuailiSignalKind.Conflict) {
                if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    signal.runs.forEachIndexed { index, run ->
                        Text(signalRunLabel(run, index == 0, signal.transitionOnly), Modifier.weight(1f),
                            fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp, color = Color(palette.secondary),
                            textAlign = if (index == 0) TextAlign.Start else TextAlign.End)
                    }
                } else signal.runs.forEachIndexed { index, run ->
                    Text(signalRunLabel(run, index == 0, signal.transitionOnly), fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp,
                        color = Color(palette.secondary))
                }
                Spacer(Modifier.height(2.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signal.kind != GuailiSignalKind.Conflict) Text(signalRangeLabel(signal), Modifier.weight(1f),
                    fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp, color = Color(palette.secondary))
                Text(signalCompactTrend(signal, maLabel), Modifier.weight(1f), fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp,
                    color = Color(palette.secondary), textAlign = if (signal.kind == GuailiSignalKind.Conflict) TextAlign.Start else TextAlign.Center)
                Text(signalPhaseLabel(signal), Modifier.weight(1f), fontSize = SignalCardStyle.BodyFontSp.sp, lineHeight = 12.sp,
                    color = Color(palette.secondary), textAlign = TextAlign.End)
            }
        }
    }
}

private fun signalTime(value: Long?, full: Boolean = false): String = value?.let {
    DateTimeFormatter.ofPattern(if (full) "MM-dd HH:mm:ss" else "HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
} ?: "待获取"
