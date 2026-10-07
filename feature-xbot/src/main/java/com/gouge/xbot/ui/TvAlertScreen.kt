package com.gouge.xbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gouge.xbot.R
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.resetRequest
import com.gouge.xbot.domain.businessExpireAt
import com.gouge.xbot.domain.BusinessExpiryPresentation
import com.gouge.xbot.domain.BusinessExpiryState
import com.gouge.xbot.domain.businessExpiryPresentation
import com.gouge.xbot.domain.matches
import com.gouge.xbot.domain.tickerId
import com.gouge.xbot.domain.tickerLabel
import com.gouge.xbot.widget.AlertWidgetTarget
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvAlertScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onChooseVisible: () -> Unit,
    onAddAlert: (TvAlertConfigDto) -> Unit,
    onDeleteAlert: (TvAlertConfigDto, TvAlertDto) -> Unit,
    onResetAlert: (TvAlertConfigDto, TvAlertDto) -> Unit,
    modifier: Modifier = Modifier,
    onRefreshCache: (() -> Unit)? = null,
    widgetTarget: AlertWidgetTarget? = null,
) {
    val visibleConfigs = state.alertConfigs.filter { it.id in state.visibleAlertIds }
    val now = rememberAlertTime()
    var pendingDeletion by remember { mutableStateOf<AlertActionTarget?>(null) }
    var pendingReset by remember { mutableStateOf<AlertActionTarget?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(widgetTarget, state.hasLoadedAlerts, visibleConfigs.map { it.id }) {
        if (widgetTarget != null) {
            val index = visibleConfigs.indexOfFirst { it.id == widgetTarget.configId }
            if (index >= 0) listState.scrollToItem(index)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp)) {
            val iconActions = maxWidth / LocalDensity.current.fontScale < 480.dp
            val groupCount = "${state.visibleAlertIds.size}/${state.alertConfigs.size}"
            val fullStatus = "$groupCount 组 · " +
                syncStatusText(state.alertsUpdatedAtMillis, state.isLoadingAlerts, state.alertErrorMessage)
            val compactStatus = syncStatusText(state.alertsUpdatedAtMillis, state.isLoadingAlerts, state.alertErrorMessage, compact = true)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("警报管理", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$groupCount · " + if (iconActions) compactStatus.removePrefix("同步 ") else compactStatus,
                    modifier = Modifier.weight(1f).semantics { contentDescription = fullStatus },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.alertErrorMessage == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (iconActions) {
                        IconButton(onClick = onChooseVisible,
                            enabled = state.hasLoadedAlerts && !state.isLoadingAlerts && !state.isChangingAlerts,
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.FilterList, contentDescription = "显示配置")
                        }
                        IconButton(onClick = onRefresh,
                            enabled = !state.isLoadingAlerts && !state.isChangingAlerts,
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    } else {
                        TextButton(onClick = onChooseVisible,
                            enabled = state.hasLoadedAlerts && !state.isLoadingAlerts && !state.isChangingAlerts) { Text("显示配置") }
                        TextButton(onClick = onRefresh,
                            enabled = !state.isLoadingAlerts && !state.isChangingAlerts) { Text("刷新") }
                        TextButton(onClick = onLogout) { Text("账户") }
                    }
                    if (iconActions || onRefreshCache != null) {
                    Box {
                        IconButton(onClick = { moreMenu = true },
                            enabled = iconActions || (!state.isChangingAlerts && !state.isLoadingAlerts),
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            Text(fullStatus, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (iconActions) {
                                DropdownMenuItem(text = { Text("账户") }, onClick = { moreMenu = false; onLogout() })
                            }
                            if (onRefreshCache != null) {
                                DropdownMenuItem(text = { Text("刷新 TradingView 缓存") },
                                    enabled = state.visibleAlertIds.isNotEmpty() && !state.isChangingAlerts && !state.isLoadingAlerts,
                                    onClick = { moreMenu = false; onRefreshCache() })
                            }
                        }
                    }
                    }
                }
            }
        }
        HorizontalDivider()
        state.alertErrorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.alertActionMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            state.isLoadingAlerts && !state.hasLoadedAlerts -> LoadingAlerts()
            state.alertConfigs.isEmpty() -> EmptyAlerts("暂无警报配置")
            visibleConfigs.isEmpty() -> EmptyAlerts("未选择要显示的警报，请点击右上角“显示”进行选择")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visibleConfigs, key = { it.id }) { config ->
                    val alerts = remember(config, state.tvAlertsByCookieId) {
                        state.tvAlertsByCookieId[config.cookieId].orEmpty().filter(config::matches)
                    }
                    TvAlertConfigCard(
                        config = config,
                        alerts = alerts,
                        now = now.value,
                        targetAlertId = widgetTarget?.takeIf { it.configId == config.id }?.alertId,
                        targetGeneration = widgetTarget?.generation ?: 0,
                        onAddAlert = { onAddAlert(config) },
                        deletingAlert = state.deletingTvAlert,
                        resettingAlert = state.resettingTvAlert,
                        actionsEnabled = !state.isLoadingAlerts && !state.isChangingAlerts,
                        onResetAlert = { alert ->
                            pendingReset = AlertActionTarget(config, alert)
                        },
                        onDeleteAlert = { alert ->
                            pendingDeletion = AlertActionTarget(config, alert)
                        },
                    )
                }
                if (state.isLoadingAlerts) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    pendingReset?.let { target ->
        TvAlertResetConfirmation(
            config = target.config, alert = target.alert,
            enabled = !state.isChangingAlerts && !state.isLoadingAlerts,
            onDismiss = { pendingReset = null },
            onConfirm = {
                pendingReset = null
                onResetAlert(target.config, target.alert)
            },
        )
    }

    pendingDeletion?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("删除警报？") },
            text = {
                Text(
                    "将删除 ${target.alert.tickerLabel()} · " +
                        "${target.alert.resolution.ifBlank { "-" }}。此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAlert(target.config, target.alert)
                        pendingDeletion = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private data class AlertActionTarget(
    val config: TvAlertConfigDto,
    val alert: TvAlertDto,
)

@Composable
private fun rememberAlertTime(): State<Instant> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(initialValue = Instant.now(), lifecycleOwner) {
        // One clock for the page; no polling or background updates are needed.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = Instant.now()
                delay(1_000)
            }
        }
    }
}

@Composable
private fun businessExpiredColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFE8B04B) else Color(0xFF925500)

@Composable
private fun LoadingAlerts() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text("正在加载警报")
    }
}

@Composable
private fun EmptyAlerts(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("alert-empty"))
    }
}

@Composable
private fun TvAlertConfigCard(
    config: TvAlertConfigDto,
    alerts: List<TvAlertDto>,
    now: Instant,
    onAddAlert: () -> Unit,
    deletingAlert: TvAlertDeletionKey?,
    resettingAlert: TvAlertDeletionKey?,
    actionsEnabled: Boolean,
    onResetAlert: (TvAlertDto) -> Unit,
    onDeleteAlert: (TvAlertDto) -> Unit,
    targetAlertId: Long? = null,
    targetGeneration: Long = 0,
) {
    val activeCount = alerts.count { it.active }
    val inactiveCount = alerts.size - activeCount
    val expiresAtByAlertId = remember(config, alerts) {
        alerts.associate { it.alertId to businessExpireAt(config, it) }
    }
    val expiredCount = expiresAtByAlertId.values.count { it != null && it <= now }
    var expanded by rememberSaveable(config.id) { mutableStateOf(false) }
    LaunchedEffect(targetAlertId, targetGeneration) { if (targetAlertId != null) expanded = true }
    val visibleAlerts = if (expanded) alerts else alerts.take(CollapsedAlertCount)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.title.ifBlank { "未命名警报" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = when {
                                alerts.isEmpty() -> "暂无警报"
                                inactiveCount > 0 -> "启用 $activeCount · 停用 $inactiveCount"
                                else -> "启用 $activeCount"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (expiredCount > 0) {
                            Text(
                                text = "业务已过期 $expiredCount 条",
                                style = MaterialTheme.typography.labelMedium,
                                color = businessExpiredColor(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    val detail = listOf(config.periods, config.tickerIds)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    onClick = onAddAlert,
                    enabled = actionsEnabled,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("添加")
                }
            }
            if (alerts.isNotEmpty()) {
                HorizontalDivider()
                visibleAlerts.forEachIndexed { index, alert ->
                    TvAlertRow(
                        alert = alert,
                        isTarget = alert.alertId == targetAlertId,
                        targetGeneration = targetGeneration,
                        businessExpiry = businessExpiryPresentation(expiresAtByAlertId[alert.alertId], now),
                        isDeleting = deletingAlert?.let {
                            it.cookieId == config.cookieId && it.alertId == alert.alertId
                        } == true,
                        isResetting = resettingAlert?.let {
                            it.cookieId == config.cookieId && it.alertId == alert.alertId
                        } == true,
                        resetEnabled = actionsEnabled && runCatching { alert.resetRequest(config) }.isSuccess,
                        onReset = { onResetAlert(alert) },
                        deleteEnabled = actionsEnabled,
                        onDelete = { onDeleteAlert(alert) },
                    )
                    if (index < visibleAlerts.lastIndex || alerts.size > CollapsedAlertCount) {
                        HorizontalDivider()
                    }
                }
                if (alerts.size > CollapsedAlertCount) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (expanded) {
                                "收起"
                            } else {
                                "展开其余 ${alerts.size - CollapsedAlertCount} 条"
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val CollapsedAlertCount = 3

@Composable
private fun TvAlertRow(
    alert: TvAlertDto,
    businessExpiry: BusinessExpiryPresentation,
    isDeleting: Boolean,
    isResetting: Boolean,
    resetEnabled: Boolean,
    onReset: () -> Unit,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
    isTarget: Boolean = false,
    targetGeneration: Long = 0,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isTarget, targetGeneration) {
        if (isTarget) { withFrameNanos { }; bringIntoView.bringIntoView() }
    }
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = alert.tickerLabel(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = alert.resolution.ifBlank { "-" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = if (alert.active) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (alert.active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = if (alert.active) "启用" else "停用",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onReset,
                enabled = resetEnabled,
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(if (isResetting) "设置中" else "再设", maxLines = 1)
            }
            TextButton(
                onClick = onDelete,
                enabled = deleteEnabled,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    text = if (isDeleting) "删除中" else "删除",
                    color = if (deleteEnabled || isDeleting) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
        BusinessExpiryDetails(
            expiry = businessExpiry,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun BusinessExpiryDetails(
    expiry: BusinessExpiryPresentation,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (expiry.state) {
        BusinessExpiryState.Valid -> MaterialTheme.colorScheme.onSurface
        BusinessExpiryState.Expired -> businessExpiredColor()
        BusinessExpiryState.Unconfigured -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expiry.state == BusinessExpiryState.Expired) {
            Icon(
                painter = painterResource(R.drawable.xbot_ic_business_expiry_clock),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = statusColor,
            )
        }
        Text(
            text = buildAnnotatedString {
                append(expiry.statusText.substringBefore(" · "))
                expiry.compactExpiresAtText?.let {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                        append(" · $it")
                    }
                    append(" · ${expiry.statusText.substringAfter(" · ")}")
                }
            },
            modifier = Modifier.semantics {
                // Keep the year and UTC offset available to screen readers.
                contentDescription = expiry.expiresAtText?.let {
                    "${expiry.statusText} · 业务过期：$it"
                } ?: expiry.statusText
            },
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            fontWeight = if (expiry.state == BusinessExpiryState.Expired) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}
