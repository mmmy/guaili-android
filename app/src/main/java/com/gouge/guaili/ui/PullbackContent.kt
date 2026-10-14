package com.gouge.guaili.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.PullbackRules
import com.gouge.guaili.data.PriceAlertDto
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.withTimeoutOrNull
import com.gouge.guaili.signals.serverDisplayInterval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow

private data class PullbackSelection(val symbol: String, val candidate: PullbackCandidate,
    val rules: PullbackRules, val sampledAt: Long?, val average: String)
private val PullbackTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault())
private fun signed(value: Int?) = when { value == null -> "—"; value > 0 -> "+$value"; else -> value.toString() }
internal fun pullbackPeriodLabel(interval: String): String {
    val minutes = interval.toLongOrNull()
    return if (minutes != null && minutes > 0 && minutes % 60 == 0L) "${minutes / 60}H" else serverDisplayInterval(interval)
}

@Composable
internal fun PullbackContent(
    state: MarketSignalsUiState,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onOnlyCandidates: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
    onRules: (PullbackRules) -> Unit,
    reminders: Flow<Unit>,
    onOpenKline: (KlineTarget) -> Unit,
    modifier: Modifier = Modifier,
    priceAlerts: MarketPriceAlertsState = MarketPriceAlertsState(),
    priceWriter: PriceAlertsViewModel? = null,
    writeState: PriceAlertsState = PriceAlertsState(),
    serviceKey: String = "",
    onOpenPriceAlert: (PriceAlertDto) -> Unit = {},
    onParameters: ((PullbackRules, Int) -> Unit)? = null,
    onPrimaryAlert: (String, Long) -> Unit = { _, _ -> },
) {
    val prefs = state.preferences.pullback
    val projection = state.pullbacks
    var selection by remember { mutableStateOf<PullbackSelection?>(null) }
    var parameters by rememberSaveable { mutableStateOf(false) }
    var alertGroup by remember(serviceKey) { mutableStateOf<Pair<String, String>?>(null) }
    var resumeExtension by remember(serviceKey) { mutableStateOf<PriceAlertDto?>(null) }
    val groupedAlerts = remember(priceAlerts.alerts, priceAlerts.now) { groupPullbackAlerts(priceAlerts.alerts, priceAlerts.now) }
    val snackbar = remember { SnackbarHostState() }
    fun extend(alert: PriceAlertDto) { selection = null; alertGroup = null; priceWriter?.prepareExtension(alert, prefs.extensionBars) }
    val rows = remember(projection.rows, prefs.onlyCandidates) {
        if (prefs.onlyCandidates) projection.rows.filter { it.hasCandidate } else projection.rows
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, priceWriter) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) priceWriter?.closeExtensionPreview() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); priceWriter?.closeExtensionPreview() }
    }
    LaunchedEffect(rows, alertGroup, groupedAlerts) {
        alertGroup?.let { (symbol, group) ->
            val row = rows.firstOrNull { it.symbol == symbol }
            val hiddenDirection = prefs.onlyCandidates && when (group) {
                "Long" -> row?.candidate(PullbackDirection.Long)?.highlighted != true
                "Short" -> row?.candidate(PullbackDirection.Short)?.highlighted != true
                else -> false
            }
            if (row == null || hiddenDirection || groupedAlerts[symbol]?.get(group).isNullOrEmpty()) alertGroup = null
        }
    }
    val receipt = writeState.extensionReceipt
    val receiptAlert = receipt?.let { item -> writeState.alerts.firstOrNull { it.id == item.alert.id } }
    val receiptPending = receipt?.let { writeState.pending[it.alert.id] }
    LaunchedEffect(receipt?.mutationId, receiptAlert?.revision, receiptAlert?.status, receiptPending?.phase) {
        if (receipt != null) {
            val remaining = receipt.until - android.os.SystemClock.elapsedRealtime()
            if (remaining > 0) withTimeoutOrNull(remaining) {
                val canUndo = receiptPending == null && writeState.extensionUndo?.validFor(receiptAlert,
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()) == true
                val uncertainUndo = receiptPending?.operation?.purpose == "extension_undo" && receiptPending.phase == "uncertain"
                val message = when {
                    receiptPending?.operation?.purpose == "extension_undo" -> receiptPending.error ?: "正在撤销延期"
                    receipt.undone -> "已撤销延期，恢复原时间范围"
                    else -> "${receipt.alert.symbol} 已延期至 ${alertDate(receipt.alert.geometry.second.timeMs)}"
                }
                if (snackbar.showSnackbar(message, actionLabel = if (uncertainUndo) "核对" else if (canUndo) "撤销" else null,
                    withDismissAction = true, duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) {
                    if (uncertainUndo) priceWriter?.retry(receipt.alert.id) else priceWriter?.undoExtension()
                }
            }
        }
    }
    val tone = remember { arrayOfNulls<ToneGenerator>(1) }
    LaunchedEffect(lifecycle, reminders) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { reminders.collect {
                // No service or repeating alarm: one short notification sound per arrival batch.
                runCatching {
                    if (tone[0] == null) tone[0] = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                    tone[0]?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                }
            } } finally { tone[0]?.release(); tone[0] = null }
        }
    }
    DisposableEffect(Unit) { onDispose { tone[0]?.release(); tone[0] = null } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("pullback-page")) {
            PullbackToolbar(
                rules = prefs.rules, onlyCandidates = prefs.onlyCandidates, soundEnabled = prefs.soundEnabled,
                refreshing = state.isRefreshing,
                onParameters = { selection = null; alertGroup = null; priceWriter?.closeExtensionPreview(); parameters = true },
                onOnlyCandidates = { selection = null; alertGroup = null; onOnlyCandidates(it) },
                onSound = onSound, onRefresh = onRefresh,
            )
            val issueCount = projection.rows.count { it.issue != null }
            Text("${rows.size} 品种 · 多 ${projection.longHits} · 空 ${projection.shortHits}" +
                if (issueCount > 0) " · $issueCount 品种待确认" else "",
                modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!projection.ready) Text(projection.message, Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("pullback-status"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            state.refreshError?.let { Text("刷新失败：$it", Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            state.preferenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            priceAlerts.error?.let { Text("警报待更新，显示上次结果", Modifier.testTag("pullback-alert-error"),
                color = PullbackAlertWarning, style = MaterialTheme.typography.bodySmall) }
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rows.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).testTag("pullback-empty"), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (projection.ready) "暂无回调候选" else projection.message, style = MaterialTheme.typography.titleMedium)
                    Text(if (issueCount > 0) "$issueCount 个品种数据待确认，恢复后继续观察" else "可关闭候选筛选查看等待级别，或调整观察参数",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("pullback-list"),
                contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows, key = { it.symbol }) { row ->
                    Column(Modifier.fillMaxWidth().testTag("pullback-symbol-${row.symbol}")) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.symbol, Modifier.weight(1f).padding(vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val unclassified = groupedAlerts[row.symbol]?.get("Unclassified").orEmpty()
                            if (unclassified.isNotEmpty()) TextButton({ selection = null; alertGroup = row.symbol to "Unclassified" },
                                Modifier.heightIn(min = 48.dp).testTag("pullback-unclassified-${row.symbol}")) { Text("未分类 ${unclassified.size}") }
                        }
                        PullbackDirection.entries.forEach { direction ->
                            val candidate = row.candidate(direction)
                            if (!prefs.onlyCandidates || candidate?.highlighted == true) PullbackTarget(
                                row, direction, candidate,
                                selection?.let { it.symbol == row.symbol && it.candidate.direction == direction } == true,
                                onEntryKline = { interval ->
                                    selection = null
                                    onOpenKline(KlineTarget(row.symbol, interval))
                                },
                                alertContent = {
                                    val group = direction.name
                                    PullbackAlertSummary(groupedAlerts[row.symbol]?.get(group).orEmpty(),
                                        prefs.primaryAlerts[priceAlertPrimaryKey(serviceKey, row.symbol, group)], priceAlerts.now,
                                        priceAlerts.error != null, writeState.pending, onOpen = { selection = null; alertGroup = null; onOpenPriceAlert(it) },
                                        onMore = { selection = null; alertGroup = row.symbol to group }, onExtend = ::extend,
                                        modifier = Modifier.fillMaxWidth(), loading = priceAlerts.loading)
                                },
                            ) {
                                if (selection?.let { it.symbol == row.symbol && it.candidate.direction == direction } == true) selection = null
                                else if (candidate != null) {
                                    val indicator = state.snapshot?.response?.indicatorConfig
                                    selection = PullbackSelection(row.symbol, candidate, prefs.rules, projection.sampledAt,
                                        indicator?.let { "${it.maType.uppercase()}${it.maLength}" } ?: "均线参数未知")
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Text("${if (projection.ready) "实时行情" else "等待有效行情"} · ${projection.sampledAt?.let { PullbackTime.format(Instant.ofEpochMilli(it)) } ?: "—"}",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).testTag("pullback-extension-feedback")) { data ->
            Snackbar(data, containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary, dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    selection?.let { selected ->
        PullbackEvidenceSheet(selected, projection.ready, onDismiss = { selection = null }, onKline = {
            selection = null
            onOpenKline(KlineTarget(selected.symbol, selected.candidate.target.interval))
        })
    }
    alertGroup?.let { (symbol, group) ->
        PullbackAlertGroupSheet(symbol, group, groupedAlerts[symbol]?.get(group).orEmpty(),
            prefs.primaryAlerts[priceAlertPrimaryKey(serviceKey, symbol, group)], priceAlerts.now, priceAlerts.error != null,
            writeState.pending, onDismiss = { alertGroup = null }, onOpen = { alertGroup = null; onOpenPriceAlert(it) },
            onPrimary = { id -> onPrimaryAlert(priceAlertPrimaryKey(serviceKey, symbol, group), id) }, onExtend = ::extend)
    }
    writeState.extensionPreview?.let { preview ->
        PullbackExtensionSheet(preview, writeState.pending[preview.alert.id],
            onDismiss = { priceWriter?.closeExtensionPreview() }, onSettings = {
                resumeExtension = preview.alert; priceWriter?.closeExtensionPreview(); parameters = true
            }, onConfirm = { priceWriter?.confirmExtension() },
            onReload = { priceWriter?.prepareExtension(preview.alert, prefs.extensionBars) }, onRecover = { priceWriter?.retry(preview.alert.id) })
    }
    if (parameters) PullbackParametersSheet(prefs.rules, prefs.extensionBars, {
        parameters = false
        resumeExtension?.let { priceWriter?.prepareExtension(it, prefs.extensionBars) }; resumeExtension = null
    }) { rules, bars ->
        if (onParameters != null) onParameters(rules, bars) else onRules(rules)
        parameters = false; selection = null
        resumeExtension?.let { priceWriter?.prepareExtension(it, bars) }; resumeExtension = null
    }
}

/** One row at every width/font scale; full names remain available to accessibility services. */
@Composable
private fun PullbackToolbar(
    rules: PullbackRules, onlyCandidates: Boolean, soundEnabled: Boolean, refreshing: Boolean,
    onParameters: () -> Unit, onOnlyCandidates: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit, onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("pullback-toolbar")) {
        val showParameterIcon = maxWidth / LocalDensity.current.fontScale >= 220.dp
        Row(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onParameters, modifier = Modifier.weight(1.1f).height(48.dp)
                .testTag("pullback-parameters").semantics {
                    contentDescription = "观察参数：连续 ${rules.levels} 级，阈值 ${rules.threshold}，强势背景 ${rules.strongLevels} 级"
                }, contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                if (showParameterIcon) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                Text("${rules.levels}级", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("pullback-toolbar-levels"))
                Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(12.dp))
            }
            PullbackToolbarToggle("候选", "只显示回调候选", onlyCandidates, onOnlyCandidates,
                "pullback-only-candidates", Modifier.weight(1f)) {
                Icon(if (onlyCandidates) Icons.Outlined.Check else Icons.Outlined.FilterList, null, Modifier.size(18.dp))
            }
            PullbackToolbarToggle("声音", "声音提醒", soundEnabled, onSound, "pullback-sound", Modifier.weight(1f)) {
                Icon(if (soundEnabled) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                    null, Modifier.size(18.dp))
            }
            IconButton(onRefresh, enabled = !refreshing, modifier = Modifier.size(48.dp).testTag("pullback-refresh").semantics { contentDescription = "刷新回调行情" }) {
                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), color = colors.onSurfaceVariant, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PullbackToolbarToggle(
    label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit,
    tag: String, modifier: Modifier, icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    IconToggleButton(checked, onChanged, modifier.height(48.dp).testTag(tag).semantics {
        contentDescription = description; role = Role.Switch
    }, shape = RoundedCornerShape(12.dp), colors = IconButtonDefaults.iconToggleButtonColors(
        contentColor = colors.onSurfaceVariant, checkedContainerColor = colors.primary.copy(alpha = 0.16f),
        checkedContentColor = colors.primary,
    )) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon()
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun PullbackTarget(row: PullbackRow, direction: PullbackDirection, candidate: PullbackCandidate?, isSelected: Boolean,
    onEntryKline: (String) -> Unit, alertContent: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlighted = candidate?.highlighted == true
    val directional = if (direction == PullbackDirection.Long) colors.secondary else colors.error
    val periodColor = if (highlighted) directional else lerp(colors.onSurfaceVariant, directional, 0.55f)
    val shape = RoundedCornerShape(12.dp)
    val selectedBorder = if (isSelected) Modifier.border(1.dp, colors.primary, shape) else Modifier
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)
        .background(if (highlighted) colors.surfaceVariant else Color.Transparent, shape).then(selectedBorder)
        .clickable(enabled = candidate != null, role = Role.Button, onClick = onClick)
        .semantics { selected = isSelected }.testTag("pullback-target-${row.symbol}-${direction.name}")
        .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp
        val label: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate?.target?.interval?.let(::pullbackPeriodLabel) ?: "—",
                    style = if (highlighted) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                    color = if (candidate == null) colors.onSurfaceVariant else periodColor,
                    modifier = Modifier.semantics {
                        contentDescription = "${direction.label}，回调级别 ${candidate?.target?.interval?.let(::pullbackPeriodLabel) ?: "无"}"
                    }.testTag("pullback-period-${row.symbol}-${direction.name}"))
                if (candidate != null) {
                    val interval = candidate.entryInterval
                    val label = remember(interval) { interval?.let(::pullbackPeriodLabel) ?: "—" }
                    Box(Modifier.padding(start = 8.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(enabled = interval != null, role = Role.Button, onClickLabel = "打开入场小级别 K 线") {
                            interval?.let(onEntryKline)
                        }.semantics {
                            contentDescription = if (interval == null) "无更小级别" else "${row.symbol} 入场参考 $label，打开 K 线"
                        }.testTag("pullback-entry-${row.symbol}-${direction.name}"), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (stacked) Column {
            label()
            if (candidate == null) Text(row.issue ?: "同向级数不足", style = MaterialTheme.typography.bodySmall, color = colors.error)
            alertContent()
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            label()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                if (candidate == null) Text(row.issue ?: "同向级数不足", style = MaterialTheme.typography.bodySmall, color = colors.error)
                alertContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullbackEvidenceSheet(selection: PullbackSelection, live: Boolean, onDismiss: () -> Unit, onKline: () -> Unit) {
    val candidate = selection.candidate
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("pullback-evidence")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selection.symbol} · ${candidate.direction.label}依据", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onDismiss, Modifier.testTag("pullback-evidence-close")) { Icon(Icons.Outlined.Close, "关闭依据，取消选择") }
            }
            Text(pullbackPeriodLabel(candidate.target.interval), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                color = if (candidate.direction == PullbackDirection.Long) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            Text("${candidate.strength.label} · ${candidate.phase.label} · 乖离 ${signed(candidate.target.value)}", style = MaterialTheme.typography.bodyMedium)
            Text("前 ${selection.rules.levels - 1} 级按 guaili 正负同向；${candidate.strongCount} 级 |乖离值| ≥ ${selection.rules.threshold}。",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidate.evidence.forEachIndexed { index, e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${if (index == candidate.evidence.lastIndex) "目标" else "背景 ${index + 1}"} · ${pullbackPeriodLabel(e.interval)}", style = MaterialTheme.typography.bodyMedium)
                    Text(signed(e.value), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("${selection.average} · 采样 ${selection.sampledAt?.let { PullbackTime.format(Instant.ofEpochMilli(it)) } ?: "—"} · 打开时的行情依据",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!live) Text("当前行情已不可确认，以上保留打开时的依据。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("真实 guaili = 0 才是触及均线；截断显示为 0 只表示接近。强势小幅反向容差 ≤ ${selection.rules.reverseLimit}（|guaili| × 10）。候选用于观察。",
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onKline, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pullback-kline")) {
                Icon(Icons.AutoMirrored.Outlined.ShowChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("查看 ${pullbackPeriodLabel(candidate.target.interval)} K 线")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullbackParametersSheet(rules: PullbackRules, extensionBars: Int, onDismiss: () -> Unit, onSave: (PullbackRules, Int) -> Unit) {
    var levels by rememberSaveable { mutableStateOf(rules.levels.toString()) }
    var threshold by rememberSaveable { mutableStateOf(rules.threshold.toString()) }
    var strong by rememberSaveable { mutableStateOf(rules.strongLevels.toString()) }
    var minimum by rememberSaveable { mutableStateOf(rules.minInterval) }
    var reverse by rememberSaveable { mutableStateOf(rules.reverseLimit.toString()) }
    var entryDivisor by rememberSaveable { mutableStateOf(rules.entryDivisor.toString()) }
    var extension by rememberSaveable { mutableStateOf(extensionBars.toString()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("pullback-parameters-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回调观察参数", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "取消参数修改") }
            }
            Text("从同向段的大级别起点数到第 N 级，不滑向更小级别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PullbackNumberField("连续级数 N（含目标级别）", levels, { levels = it }, "pullback-levels")
            PullbackNumberField("强弱阈值 T（|乖离值| ≥ T）", threshold, { threshold = it }, "pullback-threshold")
            PullbackNumberField("强势所需背景级数 K", strong, { strong = it }, "pullback-strong-levels")
            Text("弱势：背景全部低于 T；强势：背景至少 K 级达到 T；其余为普通。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PullbackNumberField("入场倍数（回调周期 ÷ 倍数）", entryDivisor, { entryDivisor = it }, "pullback-entry-divisor")
            Text("从固定周期中取最接近的更小级别，例如 2D ÷ 20 → 2H。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PullbackNumberField("延期 K 线数", extension, { extension = it; error = null }, "pullback-extension-bars")
            Text("默认 20，支持 1–1000 根；按警报周期计算，适用于全部品种。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ advanced = !advanced }, Modifier.testTag("pullback-advanced")) { Text(if (advanced) "收起高级参数" else "高级参数") }
            if (advanced) {
                OutlinedTextField(minimum, { minimum = it }, label = { Text("最小目标级别（分钟 / S / D / W）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("pullback-minimum"))
                PullbackNumberField("小幅反向容差 R（|guaili| × 10）", reverse, { reverse = it }, "pullback-reverse")
            }
            error?.let { Text(it, Modifier.testTag("pullback-parameter-error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text("取消") }
                Button({
                    val parsed = PullbackRules(levels.toIntOrNull() ?: -1, threshold.toIntOrNull() ?: -1,
                        strong.toIntOrNull() ?: -1, minimum.trim().uppercase(), reverse.toIntOrNull() ?: -1,
                        entryDivisor.toIntOrNull() ?: -1)
                    val bars = extension.toIntOrNull() ?: -1
                    error = parsed.error() ?: if (bars !in 1..1000) "延期 K 线数须为 1–1000" else null
                    if (error == null) onSave(parsed, bars)
                }, Modifier.testTag("pullback-save")) { Text("保存") }
            }
        }
    }
}

@Composable
private fun PullbackNumberField(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).testTag(tag))
}
