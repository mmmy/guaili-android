package com.gouge.xbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gouge.xbot.data.SignalViewDto
import com.gouge.xbot.data.TvAlertSymbolStore
import com.gouge.xbot.domain.DirectionState
import com.gouge.xbot.domain.SignalCommentItem
import com.gouge.xbot.domain.SignalCommentType
import com.gouge.xbot.domain.directionState
import com.gouge.xbot.domain.formatExpiry
import com.gouge.xbot.domain.levelText
import com.gouge.xbot.domain.parseSignalComment
import com.gouge.xbot.widget.SignalIconMapping
import com.gouge.xbot.widget.SignalIconMappingStore
import com.gouge.xbot.widget.SignalWidgetRenderer
import com.gouge.xbot.widget.AlertWidgetTarget

enum class XbotPage { Signals, Alerts }

/** Embeddable feature content; navigation and theme belong to the host. */
@Composable
fun XbotContent(
    viewModel: MainViewModel,
    page: XbotPage,
    widgetTarget: AlertWidgetTarget? = null,
    resumeGeneration: Int = 0,
    onOpenAccount: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val mappingStore = remember { SignalIconMappingStore(context) }
    val alertSymbolStore = remember { TvAlertSymbolStore(context) }
    var iconMappings by remember { mutableStateOf(mappingStore.getAll()) }
    var showIconMappings by remember { mutableStateOf(false) }
    var showAlertVisibility by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        if (!state.isAuthenticated) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .testTag(if (page == XbotPage.Signals) "xbot-signals-locked" else "xbot-alerts-locked"),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (page == XbotPage.Signals) "管理交易信号" else "管理 TradingView 警报",
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("登录 XBot 账户后，可以查看和修改" +
                    if (page == XbotPage.Signals) "信号设置。" else "警报配置。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.errorMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenAccount, modifier = Modifier.testTag("xbot-open-account")) {
                    Text("登录账户")
                }
            }
        } else when (page) {
            XbotPage.Signals -> SignalListScreen(
                state = state, onRefresh = viewModel::refresh, onLogout = onOpenAccount,
                onEditSignal = viewModel::openSignalSettings,
                onManageIcons = { showIconMappings = true },
            )
            XbotPage.Alerts -> TvAlertScreen(
                state = state, onRefresh = { viewModel.loadAlerts(force = true) },
                onLogout = onOpenAccount, onChooseVisible = { showAlertVisibility = true },
                onAddAlert = viewModel::openAlertSetup, onDeleteAlert = viewModel::deleteTvAlert,
                onResetAlert = viewModel::resetTvAlert,
                onRefreshCache = viewModel::refreshTradingViewCache, widgetTarget = widgetTarget,
            )
        }
    }
    LaunchedEffect(state.isAuthenticated, page, resumeGeneration) {
        if (state.isAuthenticated && page == XbotPage.Alerts) viewModel.loadAlerts(force = resumeGeneration > 0)
    }
    if (!state.isAuthenticated) return
    state.editingSignal?.let { signal ->
        SignalSettingsSheet(signal = signal, isSaving = state.isSavingSettings,
            errorMessage = state.settingsErrorMessage, onDismiss = viewModel::dismissSignalSettings,
            onSave = viewModel::saveSignalSettings)
    }
    if (showIconMappings) SignalIconMappingSheet(
        mappings = iconMappings, onMappingsChange = { mappings ->
            mappingStore.save(mappings)
            iconMappings = mappings
            SignalWidgetRenderer.renderAll(context)
        }, onDismiss = { showIconMappings = false },
    )
    if (showAlertVisibility) AlertVisibilitySheet(
        configs = state.alertConfigs, selectedIds = state.visibleAlertIds,
        onSave = viewModel::saveVisibleAlertIds, onDismiss = { showAlertVisibility = false },
    )
    state.quickAlertConfig?.let { config ->
        TvAlertSetupSheet(config = config, initialTicker = alertSymbolStore.getLastTicker(),
            isSaving = state.isCreatingAlert, errorMessage = state.alertSetupErrorMessage,
            onSave = { ticker, periods ->
                alertSymbolStore.saveLastTicker(ticker)
                viewModel.createTvAlert(ticker, periods)
            }, onDismiss = viewModel::dismissAlertSetup)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalListScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onEditSignal: (SignalViewDto) -> Unit,
    onManageIcons: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Text("信号设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                text = syncStatusText(state.signalsUpdatedAtMillis, state.isLoading, state.errorMessage),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onManageIcons) {
                Text("图标")
            }
            TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                Text("刷新")
            }
            TextButton(onClick = onLogout, enabled = !state.isLoading) {
                Text("账户")
            }
            }
        }
        HorizontalDivider()
        state.errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.isLoading && state.signals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.signals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无信号设置", modifier = Modifier.testTag("signal-empty"))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.signals, key = { it.id }) { signal ->
                    SignalCard(signal, onEdit = { onEditSignal(signal) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalCard(
    signal: SignalViewDto,
    onEdit: () -> Unit,
) {
    val direction = directionState(signal.longOn, signal.shortOn)
    val expiry = formatExpiry(signal.expireAt)
    val comments = parseSignalComment(signal.comment)
    val directionColor = when (direction) {
        DirectionState.LongOnly -> MaterialTheme.colorScheme.primary
        DirectionState.ShortOnly -> MaterialTheme.colorScheme.error
        DirectionState.Both -> MaterialTheme.colorScheme.tertiary
        DirectionState.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = signal.symbol.ifBlank { "-" },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (signal.name.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = signal.name,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    text = direction.label,
                    color = directionColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onEdit) {
                    Text("设置")
                }
            }
            if (comments.isNotEmpty()) {
                CompactSignalComments(comments)
                Spacer(Modifier.height(2.dp))
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "级别  ${signal.levelText()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = expiry.text,
                    color = when {
                        expiry.isExpired -> MaterialTheme.colorScheme.onSurfaceVariant
                        expiry.isExpiringSoon -> colorResource(com.gouge.xbot.R.color.xbot_signal_orange)
                        else -> colorResource(com.gouge.xbot.R.color.xbot_signal_cyan)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CompactSignalComments(comments: List<SignalCommentItem>) {
    val plainComment = comments.singleOrNull { it.type == null }
    if (plainComment != null) {
        SignalCommentRow(plainComment, maxLines = 2)
        return
    }

    comments.chunked(2).take(2).forEachIndexed { rowIndex, rowComments ->
        if (rowIndex > 0) Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowComments.forEach { item ->
                SignalCommentRow(
                    item = item,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            if (rowComments.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SignalCommentRow(
    item: SignalCommentItem,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val type = item.type
    if (type == null) {
        Text(
            text = item.text,
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val (containerColor, contentColor) = when (type) {
        SignalCommentType.OpenLong -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        SignalCommentType.OpenShort -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        SignalCommentType.CloseLong -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        SignalCommentType.CloseShort -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = type.label,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (item.text.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.text,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
