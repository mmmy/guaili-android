package com.gouge.guaili.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*

internal val PullbackAlertWarning = Color(0xFFEFBB76)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PullbackAlertSummary(alerts: List<PriceAlertDto>, primary: Long?, now: Long, stale: Boolean,
    pending: Map<Long, PendingPriceAlert>, onOpen: (PriceAlertDto) -> Unit, onMore: () -> Unit,
    onExtend: (PriceAlertDto) -> Unit, modifier: Modifier = Modifier) {
    val alert = primaryPullbackAlert(alerts, primary)
    // Empty alert columns stay blank. Request failures are reported once by
    // the page; actual retained alerts still carry their individual stale state.
    if (alert == null) return
    val mutation = pending[alert.id]
    val status = mutation?.let { if (it.operation.purpose.startsWith("extension"))
        when (it.phase) { "uncertain" -> "延期待核对"; "failed" -> "延期未完成"; else -> "保存中" } else it.label() } ?: alert.pullbackStatus(now, stale)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
            onClickLabel = "打开警报 K 线", onClick = { onOpen(alert) })
            .semantics { contentDescription = "${alert.symbol} ${alertIntentLabel(alert.intent)} ${pullbackPeriodLabel(alert.interval)}，打开对应 K 线并定位警报线" }
            .testTag("pullback-alert-${alert.id}"), verticalArrangement = Arrangement.Center) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(alertIntentLabel(alert.intent), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pullbackPeriodLabel(alert.interval), fontSize = 14.sp)
                Text("· $status", fontSize = 11.sp,
                    color = if (status == "监测") MaterialTheme.colorScheme.onSurfaceVariant else PullbackAlertWarning)
            }
            Text(if (alert.geometry.kind == "horizontal_segment") "@${alertNumber(alert.geometry.first.price)}" else "斜线",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alert.canExtend(now) || mutation?.operation?.purpose == "extension") {
                val available = mutation == null || mutation.phase in listOf("failed", "uncertain")
                TextButton(onClick = { if (available) onExtend(alert) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("pullback-extend-${alert.id}")
                        .semantics { if (!available) disabled() },
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(when (mutation?.phase) { "uncertain" -> "核对"; "failed" -> "重试"; null -> "延期"; else -> "保存中" }, fontSize = 12.sp,
                        color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (alerts.size > 1) TextButton(onMore, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("pullback-alert-more-${alert.symbol}-${priceAlertGroup(alert.intent)}"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("+${alerts.size - 1}", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullbackAlertGroupSheet(symbol: String, group: String, alerts: List<PriceAlertDto>, primary: Long?, now: Long,
    stale: Boolean, pending: Map<Long, PendingPriceAlert>, onDismiss: () -> Unit, onOpen: (PriceAlertDto) -> Unit,
    onPrimary: (Long) -> Unit, onExtend: (PriceAlertDto) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 20.dp).testTag("pullback-alert-group")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(symbol, style = MaterialTheme.typography.titleLarge)
                    Text("${when (group) { "Long" -> "多头"; "Short" -> "空头"; else -> "未分类" }}价格警报 · ${alerts.size} 条",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭其他警报") }
            }
            if (stale) Text("警报待更新，显示上次结果", style = MaterialTheme.typography.bodySmall, color = PullbackAlertWarning)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("pullback-alert-group-list"), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(alerts, key = { it.id }) { alert ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PullbackAlertSummary(listOf(alert), alert.id, now, stale, pending, onOpen, {}, onExtend, Modifier.weight(1f))
                            if (group != "Unclassified") {
                                if (primaryPullbackAlert(alerts, primary)?.id == alert.id) Text("主警报", Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else TextButton({ onPrimary(alert.id) }, Modifier.heightIn(min = 48.dp).testTag("pullback-primary-${alert.id}")) { Text("设为主") }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
            Text("点击条目打开对应 K 线并定位警报线", Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullbackExtensionSheet(preview: PriceAlertExtensionPreview, pending: PendingPriceAlert?,
    onDismiss: () -> Unit, onSettings: () -> Unit, onConfirm: () -> Unit, onReload: () -> Unit, onRecover: () -> Unit) {
    val alert = preview.alert
    val busy = preview.loading || pending?.phase in listOf("saving", "retrying")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = 24.dp).testTag("pullback-extension-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延期警报", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭延期预览") }
            }
            Text("${alert.symbol} · ${alertIntentLabel(alert.intent)} ${pullbackPeriodLabel(alert.interval)} · ${if (alert.geometry.kind == "horizontal_segment") "@${alertNumber(alert.geometry.first.price)}" else "斜线"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("延期 K 线数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${preview.bars} 根", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onSettings, enabled = !busy && pending == null, modifier = Modifier.testTag("pullback-extension-settings")) { Text("修改全局配置") }
            }
            HorizontalDivider()
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            preview.plan?.let { plan ->
                listOf("起点" to (alert.geometry.first.timeMs to plan.geometry.first.timeMs), "终点" to (alert.geometry.second.timeMs to plan.geometry.second.timeMs)).forEach { (label, times) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text("原 ${alertDate(times.first)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("新 ${alertDate(times.second)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Text(if (plan.waitsForStart) "延期后等待起点" else "延期后重新建立监测基线", color = PullbackAlertWarning,
                    style = MaterialTheme.typography.bodyMedium)
                Text("最新 ${pullbackPeriodLabel(alert.interval)} K 线开盘：${alertDate(plan.latestOpen)}", Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("原时长、价格和警报有效期保留", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            alert.expiresAt?.let { Text("有效期仍至 ${alertDate(it)}", style = MaterialTheme.typography.bodySmall, color = PullbackAlertWarning) }
            (pending?.error ?: preview.error)?.let { Text(it, Modifier.testTag("pullback-extension-error"), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                Button(onClick = when { pending?.phase == "uncertain" -> onRecover; pending?.phase == "failed" || preview.error != null -> onReload; else -> onConfirm },
                    enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pullback-extension-confirm")) {
                    Text(when { busy -> "处理中"; pending?.phase == "uncertain" -> "核对结果"; pending?.phase == "failed" || preview.error != null -> "刷新预览"; else -> "确认延期" })
                }
            }
        }
    }
}
