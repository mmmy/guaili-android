package com.gouge.guaili.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.xbot.domain.businessExpiryPresentation
import com.gouge.xbot.data.resetRequest
import com.gouge.xbot.ui.MainUiState
import com.gouge.xbot.ui.TvAlertResetConfirmation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TvAlertColor = Color(0xFFB6D7FF)
internal val TvAlertMutedColor = Color(0xFFCBD5E1)

internal fun tvAlertColor(summary: PeriodTvAlerts) = when {
    summary.expiredCount > 0 -> AlertTriggeredColor
    summary.stoppedCount == summary.total -> TvAlertMutedColor
    else -> TvAlertColor
}

@Composable
internal fun TvAlertSummary(symbol: String, alerts: SymbolTvAlerts?, onClick: () -> Unit) {
    if (alerts == null) return
    val summary = alerts.summary
    Text("TV ${summary.total}", Modifier.testTag("market-tv-alerts-$symbol")
        .clickable(role = Role.Button, onClickLabel = "查看${symbol}的TV警报", onClick = onClick)
        .semantics { contentDescription = "$symbol，${summary.description}" }.padding(horizontal = 6.dp, vertical = 6.dp),
        color = tvAlertColor(summary), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
}

internal fun marketTvSyncLabel(state: MainUiState): String = when {
    !state.isAuthenticated -> "TV 未登录"
    state.isLoadingAlerts -> "TV 同步中…"
    state.alertErrorMessage != null -> "TV 同步失败 · 显示上次结果"
    !state.hasLoadedAlerts -> "TV 尚未同步"
    state.visibleAlertIds.isEmpty() -> "TV 未选择配置"
    else -> "TV · 所选 ${state.visibleAlertIds.size} 个配置 · " +
        if (state.alertsUpdatedAtMillis > 0) "同步 " + DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.alertsUpdatedAtMillis)) else "尚未同步"
}

@Composable
internal fun MarketTvSyncBar(state: MainUiState, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("market-tv-sync")
        .clickable(role = Role.Button, onClickLabel = "前往TV警报管理", onClick = onManage)
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(marketTvSyncLabel(state), Modifier.weight(1f), fontSize = 11.sp,
            color = if (state.alertErrorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("管理", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketTvAlertSheet(
    symbol: String, ticker: String, state: MainUiState, now: Instant,
    onDismiss: () -> Unit, onRefresh: () -> Unit, onManage: (MarketTvAlertEntry?) -> Unit,
    onReset: (MarketTvAlertEntry) -> Unit, onSaveTicker: (String?) -> String?,
    interval: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("market-tv-alert-list")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$symbol${interval?.let { " · ${formatInterval(it)}" }.orEmpty()} · TV 警报", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            MarketTvAlertsSection(symbol, interval, ticker, state, now, onRefresh, onManage, onReset, onSaveTicker)
        }
    }
}

/** Shared by cell details and the symbol list, using the same MainViewModel as TV management. */
@Composable
internal fun MarketTvAlertsSection(
    symbol: String, interval: String?, ticker: String, state: MainUiState, now: Instant,
    onRefresh: () -> Unit, onManage: (MarketTvAlertEntry?) -> Unit,
    onReset: (MarketTvAlertEntry) -> Unit, onSaveTicker: (String?) -> String?,
) {
    val allEntries = remember(state.isAuthenticated, state.alertConfigs, state.visibleAlertIds, state.tvAlertsByCookieId, ticker) {
        marketTvEntries(state, ticker)
    }
    val entries = allEntries.filter { interval == null || it.interval == canonicalTvInterval(interval) }.sortedWith(
        compareByDescending<MarketTvAlertEntry> { it.expired(now) }.thenBy { it.alert.active }
            .thenBy { it.interval }.thenBy { it.config.title }.thenBy { it.alert.alertId })
    val actionsEnabled = state.isAuthenticated && state.hasLoadedAlerts && state.alertErrorMessage == null &&
        !state.isChangingAlerts && !state.isLoadingAlerts
    var pendingReset by remember(symbol, interval, ticker, state.serverUrl, state.isAuthenticated) { mutableStateOf<MarketTvAlertEntry?>(null) }
    var editingTicker by remember(symbol) { mutableStateOf(false) }
    var tickerInput by remember(ticker) { mutableStateOf(ticker) }
    var mappingError by remember { mutableStateOf<String?>(null) }
    var expanded by remember(symbol, interval, ticker) { mutableStateOf(false) }
    LaunchedEffect(allEntries) {
        if (pendingReset != null && pendingReset !in allEntries) pendingReset = null
    }

    Column(Modifier.fillMaxWidth().testTag("cell-tv-alerts")) {
        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TV 警报${interval?.let { " · ${formatInterval(it)}" }.orEmpty()}", Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRefresh, enabled = state.isAuthenticated && !state.isLoadingAlerts && !state.isChangingAlerts) {
                Icon(Icons.Outlined.Refresh, "刷新TV警报")
            }
            TextButton(onClick = { onManage(null) }) { Text(if (state.isAuthenticated) "管理" else "登录") }
        }
        Text(marketTvSyncLabel(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.alertErrorMessage?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ticker, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { editingTicker = !editingTicker; tickerInput = ticker; mappingError = null },
                enabled = !state.isChangingAlerts, modifier = Modifier.testTag("market-tv-edit-ticker")) { Text("品种映射") }
        }
        if (editingTicker) {
            OutlinedTextField(tickerInput, { tickerInput = it; mappingError = null }, label = { Text("完整 TV 品种代码") },
                singleLine = true, isError = mappingError != null, modifier = Modifier.fillMaxWidth().testTag("market-tv-ticker-input"),
                supportingText = { Text(mappingError ?: "按完整代码匹配交易所与现货／合约；默认使用 BINANCE 永续合约。") })
            Row {
                TextButton(onClick = { mappingError = onSaveTicker(tickerInput); if (mappingError == null) editingTicker = false }) { Text("保存映射") }
                TextButton(onClick = { mappingError = onSaveTicker(null); if (mappingError == null) editingTicker = false }) { Text("恢复默认") }
                TextButton(onClick = { editingTicker = false }) { Text("取消") }
            }
        }
        if (entries.isNotEmpty()) {
            Text(summarizeTvEntries(entries, now).description, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
        } else {
            val message = when {
                !state.isAuthenticated -> "登录 XBot 账户后可查看 TV 警报。"
                state.isLoadingAlerts || !state.hasLoadedAlerts -> "正在等待 TV 警报数据，可刷新重试。"
                state.alertErrorMessage != null -> "暂时无法确认该品种的警报，请刷新重试。"
                state.visibleAlertIds.isEmpty() -> "请在 TV 管理页选择要显示的配置。"
                else -> "所选配置中暂无${if (interval == null) "该品种" else "该周期"}的 TV 警报；可检查品种映射。"
            }
            Text(message, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        state.alertActionMessage?.let {
            Text(it, Modifier.testTag("market-tv-action-status").padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        val visibleEntries = if (interval != null && !expanded) entries.take(3) else entries
        visibleEntries.forEach { entry -> key(entry.key) {
            var more by remember { mutableStateOf(false) }
            val resetting = state.resettingTvAlert == entry.key
            val canReset = actionsEnabled && runCatching { entry.alert.resetRequest(entry.config) }.isSuccess
            Column(Modifier.fillMaxWidth().testTag("market-tv-entry-${entry.config.cookieId}-${entry.alert.alertId}")
                .padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.config.title.ifBlank { entry.alert.name }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        resetting -> Text("重设中…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        entry.expired(now) -> TextButton(onClick = { pendingReset = entry }, enabled = canReset,
                            modifier = Modifier.testTag("market-tv-reset-${entry.config.cookieId}-${entry.alert.alertId}")) { Text("重设") }
                        else -> Box {
                            IconButton(onClick = { more = true }, enabled = canReset) { Icon(Icons.Outlined.MoreVert, "${entry.alert.name}更多操作") }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("重设") }, onClick = { more = false; pendingReset = entry })
                            }
                        }
                    }
                }
                Text(entry.alert.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatInterval(entry.alert.resolution.ifBlank { "周期未知" })} · TV ${if (entry.alert.active) "启用" else "停用"}",
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                val expiry = businessExpiryPresentation(entry.expiresAt, now)
                val expiryStatus = if (entry.expiresAt == null && entry.config.startTimeParamIndex != null &&
                    entry.config.validBarsParamIndex != null) "业务有效期无法计算，请检查配置和创建时间" else expiry.statusText
                Text(expiryStatus, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (entry.expired(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                expiry.expiresAtText?.let { Text("业务到期 $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onManage(entry) }) { Text("前往 TV 管理") }
                HorizontalDivider()
            }
        } }
        if (interval != null && entries.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起警报" else "展开其余 ${entries.size - 3} 条")
        }
    }
    pendingReset?.let { target ->
        TvAlertResetConfirmation(target.config, target.alert, actionsEnabled && target in allEntries,
            onDismiss = { pendingReset = null }, onConfirm = {
                if (actionsEnabled && target in allEntries) { pendingReset = null; onReset(target) }
            }, actionLabel = "重设")
    }
}
