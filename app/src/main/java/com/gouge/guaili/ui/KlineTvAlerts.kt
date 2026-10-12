package com.gouge.guaili.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gouge.guaili.data.MarketTvTickerStore
import com.gouge.xbot.data.resetRequest
import com.gouge.xbot.domain.businessExpiryPresentation
import com.gouge.xbot.domain.tickerId
import com.gouge.xbot.ui.MainViewModel
import com.gouge.xbot.ui.TvAlertDeletionKey
import com.gouge.xbot.ui.TvAlertResetConfirmation
import com.gouge.xbot.ui.TvAlertSetupSheet
import java.time.Instant
import kotlinx.coroutines.delay

/** TV reads are entry/manual refresh driven, independent of the candle refresh schedule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KlineTvAlertsEntry(
    baseUrl: String, symbol: String, interval: String, foreground: Boolean,
    viewModel: MainViewModel, initialFocus: TvAlertDeletionKey?,
    onManage: (MarketTvAlertEntry?) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val tickerStore = remember(baseUrl) { MarketTvTickerStore(context, baseUrl) }
    var mappings by remember(baseUrl) { mutableStateOf(tickerStore.read()) }
    val ticker = mappings[symbol] ?: defaultMarketTvTicker(symbol)
    var showList by rememberSaveable(baseUrl, symbol, interval, state.serverUrl, state.isAuthenticated) { mutableStateOf(false) }
    var allPeriods by rememberSaveable(symbol, interval) { mutableStateOf(false) }
    var chooseConfig by rememberSaveable(symbol, interval, state.serverUrl, state.isAuthenticated) { mutableStateOf(false) }
    var setupConfigId by rememberSaveable(state.serverUrl, state.isAuthenticated) { mutableStateOf<String?>(null) }
    var focusCookie by rememberSaveable { mutableStateOf(initialFocus?.cookieId) }
    var focusId by rememberSaveable { mutableStateOf(initialFocus?.alertId) }
    var focusSymbol by rememberSaveable { mutableStateOf(symbol) }
    var focusInterval by rememberSaveable { mutableStateOf(interval) }
    var pendingReset by remember(symbol, interval, state.serverUrl, state.isAuthenticated) { mutableStateOf<MarketTvAlertEntry?>(null) }
    var pendingDelete by remember(symbol, interval, state.serverUrl, state.isAuthenticated) { mutableStateOf<MarketTvAlertEntry?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    val listState = rememberLazyListState()
    val allEntries = remember(state.isAuthenticated, state.alertConfigs, state.visibleAlertIds, state.tvAlertsByCookieId, ticker) {
        marketTvEntries(state, ticker)
    }
    val periodEntries = remember(allEntries, interval) { allEntries.filter { it.interval == canonicalTvInterval(interval) } }
    val configs = remember(state.alertConfigs, state.visibleAlertIds) {
        state.alertConfigs.filter { it.id in state.visibleAlertIds && it.cookieId.isNotBlank() }
    }
    val canAct = state.isAuthenticated && state.hasLoadedAlerts && !state.isLoadingAlerts &&
        !state.isChangingAlerts && state.alertErrorMessage == null
    val listVisible = showList && !chooseConfig && !(state.quickAlertConfig != null && state.quickAlertConfig?.id == setupConfigId)
    fun closeList() {
        showList = false
        focusCookie = null
        focusId = null
        pendingReset = null
        pendingDelete = null
    }
    LaunchedEffect(foreground, state.isAuthenticated, state.serverUrl, baseUrl) {
        if (foreground) {
            mappings = tickerStore.read()
            viewModel.loadAlerts(force = true)
        }
    }
    LaunchedEffect(symbol, interval) {
        if (symbol != focusSymbol || interval != focusInterval) {
            focusCookie = null
            focusId = null
            focusSymbol = symbol
            focusInterval = interval
        }
    }
    LaunchedEffect(listVisible, lifecycle) {
        if (listVisible) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1_000) }
        }
    }
    TextButton(onClick = { allPeriods = false; showList = true; viewModel.loadAlerts(force = true) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("open-kline-tv-alerts")) {
        Text(if (!state.isAuthenticated) "TV 未登录" else if (!state.hasLoadedAlerts) "TV —" else "TV ${periodEntries.size}")
    }
    if (listVisible) ModalBottomSheet(onDismissRequest = ::closeList,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val entries = if (allPeriods) allEntries else periodEntries
        // Locate once per opening, never let a refresh undo a user's scrolling.
        var located by remember { mutableStateOf(false) }
        LaunchedEffect(entries, focusCookie, focusId) {
            if (!located) {
                val index = entries.indexOfFirst { it.config.cookieId == focusCookie && it.alert.alertId == focusId }
                if (index >= 0) { listState.scrollToItem(index); located = true }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag("kline-tv-list")) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$symbol · ${formatInterval(interval)} · TV", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = ::closeList) { Text("关闭") }
            }
            Text(ticker, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val compact = maxWidth / LocalDensity.current.fontScale < 320.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !allPeriods, onClick = { allPeriods = false },
                        label = { Text("${if (compact) "当前" else "当前周期"} ${periodEntries.size}") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("kline-tv-current")
                            .semantics { contentDescription = "当前周期 ${periodEntries.size}" })
                    FilterChip(selected = allPeriods, onClick = { allPeriods = true },
                        label = { Text("${if (compact) "全部" else "全部周期"} ${allEntries.size}") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("kline-tv-all")
                            .semantics { contentDescription = "全部周期 ${allEntries.size}" })
                    IconButton(onClick = { viewModel.loadAlerts(force = true) },
                        modifier = Modifier.size(48.dp), enabled = !state.isLoadingAlerts && !state.isChangingAlerts) {
                        Icon(Icons.Outlined.Refresh, "刷新TV警报")
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { chooseConfig = true }, enabled = canAct && configs.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("kline-tv-add")) { Text("添加") }
                TextButton(onClick = { closeList(); onManage(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前往 TV 管理") }
            }
            Text(marketTvSyncLabel(state), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = if (state.alertErrorMessage != null)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            state.alertErrorMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            state.alertActionMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("kline-tv-action-status"), style = MaterialTheme.typography.bodySmall) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (entries.isEmpty()) item {
                    Text(when {
                        !state.isAuthenticated -> "登录 XBot 账户后可查看和设置 TV 警报。"
                        state.isLoadingAlerts || !state.hasLoadedAlerts -> "正在等待 TV 警报数据，可刷新重试。"
                        state.alertErrorMessage != null -> "同步失败，请刷新后重试。"
                        configs.isEmpty() -> "请前往 TV 管理选择配置。"
                        else -> "所选配置中暂无${if (allPeriods) "该品种" else "该周期"}的 TV 警报。"
                    }, Modifier.padding(vertical = 12.dp))
                }
                items(entries, key = { "${it.config.cookieId}:${it.alert.alertId}" }) { entry ->
                    var more by remember { mutableStateOf(false) }
                    val focused = entry.config.cookieId == focusCookie && entry.alert.alertId == focusId
                    val deleting = state.deletingTvAlert == entry.key
                    val resetting = state.resettingTvAlert == entry.key
                    Surface(border = if (focused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()
                            .testTag("kline-tv-entry-${entry.config.cookieId}-${entry.alert.alertId}")) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.config.title.ifBlank { "未命名配置" }, Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall)
                                if (focused) Text("当前警报", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Box {
                                    IconButton(onClick = { more = true }, enabled = canAct,
                                        modifier = Modifier.testTag("kline-tv-more-${entry.config.cookieId}-${entry.alert.alertId}")) {
                                        Icon(Icons.Outlined.MoreVert, "${entry.alert.name}更多操作")
                                    }
                                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                        DropdownMenuItem(text = { Text("再设") }, enabled = runCatching { entry.alert.resetRequest(entry.config) }.isSuccess,
                                            onClick = { more = false; pendingReset = entry })
                                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                            onClick = { more = false; pendingDelete = entry })
                                    }
                                }
                            }
                            Text(entry.alert.name, style = MaterialTheme.typography.bodySmall)
                            Text("${formatInterval(entry.alert.resolution)} · TV ${if (entry.alert.active) "启用" else "停用"}",
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                            val expiry = businessExpiryPresentation(entry.expiresAt, now)
                            Text(expiry.statusText, style = MaterialTheme.typography.bodySmall,
                                color = if (entry.expired(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            expiry.expiresAtText?.let { Text("业务到期 $it", style = MaterialTheme.typography.bodySmall) }
                            if (deleting || resetting) Text(if (deleting) "删除中…" else "再设中…",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { closeList(); onManage(entry) }, modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("kline-tv-manage-${entry.config.cookieId}-${entry.alert.alertId}")) { Text("前往 TV 管理") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
    if (chooseConfig) ModalBottomSheet(onDismissRequest = { chooseConfig = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().testTag("kline-tv-configs"),
            contentPadding = PaddingValues(16.dp)) {
            item { Text("选择警报配置", style = MaterialTheme.typography.titleLarge) }
            item { Text("$ticker · ${formatInterval(interval)}", style = MaterialTheme.typography.bodyMedium) }
            items(configs, key = { it.id }) { config ->
                TextButton(onClick = {
                    setupConfigId = config.id
                    chooseConfig = false
                    viewModel.openAlertSetup(config)
                }, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("kline-tv-config-${config.id}")) {
                    Text(config.title.ifBlank { "未命名配置" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item { TextButton(onClick = { chooseConfig = false }) { Text("取消") } }
        }
    }
    state.quickAlertConfig?.takeIf { it.id == setupConfigId }?.let { config ->
        TvAlertSetupSheet(config, ticker, state.isCreatingAlert, state.alertSetupErrorMessage,
            onSave = viewModel::createTvAlert, onDismiss = { viewModel.dismissAlertSetup(); setupConfigId = null },
            initialPeriods = listOf(interval))
    }
    pendingReset?.let { target ->
        TvAlertResetConfirmation(target.config, target.alert, canAct && target in allEntries,
            onDismiss = { pendingReset = null }, onConfirm = {
                if (canAct && target in allEntries) { pendingReset = null; viewModel.resetTvAlert(target.config, target.alert) }
            })
    }
    pendingDelete?.let { target ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除 TV 警报？") },
            text = { Text("${target.config.title}\n${target.alert.name}\n${target.alert.tickerId()} · ${formatInterval(target.alert.resolution)}\n删除后无法撤销。") },
            confirmButton = { TextButton(enabled = canAct && target in allEntries, onClick = {
                if (canAct && target in allEntries) { pendingDelete = null; viewModel.deleteTvAlert(target.config, target.alert) }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}
