package com.gouge.guaili.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.gouge.guaili.data.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val AlertDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val PayloadJson = Json { prettyPrint = true }
internal fun alertDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(AlertDateFormat)
internal fun alertParseDate(text: String): Long? = runCatching { LocalDateTime.parse(text.trim(), AlertDateFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
internal fun alertNumber(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
internal fun alertDirection(value: String) = when (value) { "cross_up" -> "上穿"; "cross_down" -> "下穿"; else -> "穿过" }
internal fun alertColor(value: String): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color(0xFF22AB94))
internal fun editorMonitoringStatus(original: PriceAlertDto?, enabled: Boolean): String? = when {
    !enabled -> "disabled"
    original == null || original.status in listOf("disabled", "expired") -> "active"
    else -> null
}

@Serializable
internal data class PriceAlertEditorSession(val symbol: String, val interval: String, val geometry: PriceAlertGeometry, val existing: PriceAlertDto? = null)

@Composable
internal fun PriceAlertSelectionBar(
    alert: PriceAlertDto, pending: PendingPriceAlert?, canUndo: Boolean,
    onEdit: () -> Unit, onToggle: () -> Unit, onHistory: () -> Unit, onUndo: () -> Unit,
    onDelete: () -> Unit, onClose: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable(alert.id) { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().testTag("price-alert-selection"), tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alert.name.ifBlank { "价格警报" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(pending?.label() ?: alert.stateLabel(), style = MaterialTheme.typography.labelMedium,
                    color = if (pending?.phase == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text("${alert.symbol} · 绑定 ${formatInterval(alert.interval)} · ${alertDirection(alert.direction)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertIcon("编辑警报", Icons.Outlined.Edit, pending == null, onEdit)
                AlertIcon(if (alert.status == "active") "暂停警报" else "重新启用警报", if (alert.status == "active") Icons.Outlined.Pause else Icons.Outlined.PlayArrow, pending == null, onToggle)
                AlertIcon("触发记录", Icons.Outlined.History, true, onHistory)
                AlertIcon("撤销移动", Icons.AutoMirrored.Outlined.Undo, pending == null && canUndo, onUndo)
                AlertIcon("删除警报", Icons.Outlined.Delete, pending == null, { confirmDelete = true })
                AlertIcon("取消选择", Icons.Outlined.Close, true, onClose)
            }
            if (pending != null && pending.phase != "saving") {
                Text(pending.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (pending.phase != "retrying") TextButton(onClick = onRetry) { Text(if (pending.phase == "uncertain") "核对保存结果" else "重试我的修改") }
                    if (pending.phase in listOf("failed", "retrying")) TextButton(onClick = onDiscard) { Text(if (pending.phase == "retrying") "取消重试" else "放弃修改") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这条警报？") },
        text = { Text("线段和监测将被删除，服务器保留历史触发记录。") },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") } })
}

@Composable
private fun AlertIcon(label: String, image: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(image, contentDescription = label, modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceAlertEditorSheet(
    session: PriceAlertEditorSession, preset: PriceAlertPreset, market: PriceAlertMarket?, pending: PendingPriceAlert?,
    onDismiss: () -> Unit, onSave: (JsonObject, PriceAlertPreset?) -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit,
) {
    val original = session.existing
    val geometry = session.geometry
    val stored = pending?.operation?.body?.takeIf { original == null }
    fun storedText(key: String): String? = (stored?.get(key) as? JsonPrimitive)?.contentOrNull
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(original?.name ?: storedText("name") ?: preset.name) }
    var direction by rememberSaveable { mutableStateOf(original?.direction ?: storedText("direction") ?: preset.direction) }
    var enabled by rememberSaveable { mutableStateOf(storedText("status")?.let { it == "active" } ?: (original?.status != "disabled" && original?.status != "expired")) }
    var expires by rememberSaveable { mutableStateOf((original?.expiresAt ?: storedText("expiresAt")?.toLongOrNull())?.let(::alertDate).orEmpty()) }
    var firstPrice by rememberSaveable { mutableStateOf(alertNumber(geometry.first.price)) }
    var secondPrice by rememberSaveable { mutableStateOf(alertNumber(geometry.second.price)) }
    var firstTime by rememberSaveable { mutableStateOf(alertDate(geometry.first.timeMs)) }
    var secondTime by rememberSaveable { mutableStateOf(alertDate(geometry.second.timeMs)) }
    var extend by rememberSaveable { mutableStateOf(geometry.extend) }
    var label by rememberSaveable { mutableStateOf(original?.label ?: storedText("label") ?: preset.label) }
    var color by rememberSaveable { mutableStateOf(original?.color ?: storedText("color") ?: preset.color) }
    var lineWidth by rememberSaveable { mutableFloatStateOf(original?.lineWidth ?: storedText("lineWidth")?.toFloatOrNull() ?: preset.lineWidth) }
    var webhook by rememberSaveable { mutableStateOf(original?.webhookUrl ?: storedText("webhookUrl") ?: preset.webhookUrl) }
    var template by rememberSaveable { mutableStateOf(original?.messageTemplate ?: storedText("messageTemplate") ?: preset.messageTemplate) }
    var tvSymbol by rememberSaveable { mutableStateOf(original?.tvSymbol ?: storedText("tvSymbol") ?: market?.tvSymbol ?: "${session.symbol}.P") }
    var saveDefault by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val locked = pending != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).widthIn(max = 640.dp).imePadding().testTag("price-alert-editor")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (original == null) "设置价格警报" else "编辑价格警报", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭警报编辑") }
            }
            Text("${session.symbol} · 绑定 ${formatInterval(session.interval)}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("条件", "消息", "通知").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        OutlinedTextField(name, { name = it }, label = { Text("警报名称") }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth())
                        Text("价格 ${alertDirection(direction)} ${if (geometry.kind == "horizontal_segment") "水平线段" else "趋势线"}", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("cross_any", "cross_up", "cross_down").forEach { value -> FilterChip(direction == value, { direction = value }, enabled = !locked, label = { Text(alertDirection(value)) }) }
                        }
                        Text("仅一次 · 移动已触发线段后可再次布防", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用监测", style = MaterialTheme.typography.titleSmall)
                                Text(if (enabled) "保存后由服务器监测" else "保存为暂停线段", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(enabled, { enabled = it }, enabled = !locked, modifier = Modifier.semantics { contentDescription = "启用价格警报监测" })
                        }
                        OutlinedTextField(expires, { expires = it }, label = { Text("到期时间（留空为无限制）") }, supportingText = { Text("设备时间：yyyy-MM-dd HH:mm:ss") },
                            enabled = !locked, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                                IconButton(enabled = !locked, onClick = {
                                    val start = (alertParseDate(expires)?.let(Instant::ofEpochMilli) ?: Instant.now()).atZone(ZoneId.systemDefault())
                                    DatePickerDialog(context, { _, year, month, day ->
                                        TimePickerDialog(context, { _, hour, minute -> expires = LocalDateTime.of(year, month + 1, day, hour, minute).format(AlertDateFormat) }, start.hour, start.minute, true).show()
                                    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                                }) { Icon(Icons.Outlined.CalendarMonth, "选择到期时间") }
                            })
                        HorizontalDivider()
                        Text("线段坐标", style = MaterialTheme.typography.titleSmall)
                        AlertCoordinate("起点价格", firstPrice, { firstPrice = it }, !locked, numeric = true)
                        AlertCoordinate("起点时间", firstTime, { firstTime = it }, !locked)
                        if (geometry.kind == "trend_segment") AlertCoordinate("终点价格", secondPrice, { secondPrice = it }, !locked, numeric = true)
                        AlertCoordinate("终点时间", secondTime, { secondTime = it }, !locked)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("none" to "不延伸", "right" to "向右延伸", "both" to "双向延伸").forEach { (value, text) -> FilterChip(extend == value, { extend = value }, enabled = !locked, label = { Text(text) }) }
                        }
                        OutlinedTextField(label, { label = it }, label = { Text("线段文字") }, enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("#22AB94" to "绿", "#EF5350" to "红", "#60A5FA" to "蓝", "#F59E0B" to "橙").forEach { (value, text) ->
                                FilterChip(color.equals(value, true), { color = value }, enabled = !locked, label = { Text(text, color = alertColor(value)) })
                            }
                        }
                        Text("线宽 ${lineWidth.toInt()}", style = MaterialTheme.typography.bodySmall)
                        Slider(lineWidth, { lineWidth = it }, valueRange = 1f..6f, steps = 4, enabled = !locked)
                        Text(market?.step()?.let { "最小价位 ${it.stripTrailingZeros().toPlainString()}" } ?: "品种精度暂不可用，可以保存暂停线段", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        OutlinedTextField(tvSymbol, { tvSymbol = it }, label = { Text("TV 品种代码") }, enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(template, { template = it }, label = { Text("JSON 消息模板") }, enabled = !locked, minLines = 9, modifier = Modifier.fillMaxWidth().testTag("price-alert-template"))
                        Text("支持 {{ticker}}、{{close}}、{{interval}}、{{linePrice}}、{{direction}}。BUY / SELL 与描述文字由模板决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !locked, onClick = { template = PriceAlertTemplate }) { Text("恢复默认消息") }
                    }
                    2 -> {
                        Text("Webhook 通知", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(webhook, { webhook = it }, label = { Text("Webhook URL") }, enabled = !locked, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("price-alert-webhook"))
                        Text("触发后，服务器将消息中的 JSON 发送到此地址。关闭手机应用后仍继续监测。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) { saveDefault = !saveDefault }) {
                    Checkbox(saveDefault, { saveDefault = it }, enabled = !locked)
                    Text("保存为下次绘图的默认预设", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                (formError ?: pending?.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (pending != null) Text(pending.label(), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
                    if (pending != null && pending.phase !in listOf("saving", "retrying")) {
                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(if (pending.phase == "uncertain") "核对结果" else "重试保存") }
                    } else Button(enabled = !locked, modifier = Modifier.weight(1f).testTag("price-alert-save"), onClick = {
                        val p1 = firstPrice.toDoubleOrNull(); val p2 = if (geometry.kind == "horizontal_segment") p1 else secondPrice.toDoubleOrNull()
                        val t1 = if (firstTime == alertDate(geometry.first.timeMs)) geometry.first.timeMs else alertParseDate(firstTime)
                        val t2 = if (secondTime == alertDate(geometry.second.timeMs)) geometry.second.timeMs else alertParseDate(secondTime)
                        val expiry = if (expires.isBlank()) null else if (expires == original?.expiresAt?.let(::alertDate)) original.expiresAt else alertParseDate(expires)
                        val g = if (p1 != null && p2 != null && t1 != null && t2 != null) PriceAlertGeometry(geometry.kind, PriceAlertPoint(t1, p1), PriceAlertPoint(t2, p2), extend).normalized() else null
                        formError = when {
                            g?.valid() != true -> "请输入有效价格和不同的端点时间"
                            expires.isNotBlank() && expiry == null -> "到期时间格式无效"
                            enabled && expiry != null && expiry <= System.currentTimeMillis() -> "请设置未来的到期时间，或留空"
                            enabled && market?.step() == null -> "品种精度暂不可用，请刷新后再启用"
                            (enabled || webhook.isNotBlank()) && !(webhook.startsWith("http://") || webhook.startsWith("https://")) -> "请输入 http:// 或 https:// 开头的通知地址"
                            enabled || template.isNotBlank() -> validateAlertTemplate(template)
                            else -> null
                        }
                        if (formError == null && g != null) {
                            val fields = buildJsonObject {
                                if (original == null) { put("symbol", session.symbol); put("interval", session.interval); put("frequency", "once") }
                                put("tvSymbol", tvSymbol.trim()); put("name", name.trim()); put("direction", direction)
                                if (original == null || g != original.geometry) put("geometry", PriceAlertRepository.json.encodeToJsonElement(g))
                                put("expiresAt", expiry?.let(::JsonPrimitive) ?: JsonNull)
                                editorMonitoringStatus(original, enabled)?.let { put("status", it) }
                                put("webhookUrl", webhook.trim()); put("messageTemplate", template); put("label", label); put("color", color); put("lineWidth", lineWidth)
                            }
                            onSave(fields, if (saveDefault) PriceAlertPreset(name, direction, webhook.trim(), template, label, color, lineWidth, extend) else null)
                        }
                    }) { Text(if (pending?.phase == "retrying") "读取中" else if (locked) "保存中" else if (enabled) "保存警报" else "保存暂停线段") }
                }
                if (pending?.phase in listOf("failed", "retrying")) TextButton(onClick = onDiscard) { Text(if (pending?.phase == "retrying") "取消重试，继续编辑" else "放弃本次保存，继续编辑") }
            }
        }
    }
}

@Composable
private fun AlertCoordinate(label: String, value: String, change: (String) -> Unit, enabled: Boolean, numeric: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceAlertListSheet(alerts: List<PriceAlertDto>, pending: Map<Long, PendingPriceAlert>, error: String?, onDismiss: () -> Unit, onSelect: (Long) -> Unit, onRefresh: () -> Unit,
    legacy: List<AlertDto> = emptyList(), onLegacySelect: (Long) -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("价格警报 · ${alerts.size + legacy.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新价格警报") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭警报列表") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (alerts.isEmpty() && legacy.isEmpty()) Text("暂无价格线警报。返回图表，用“水平线”或“趋势线”开始绘图。", Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.weight(1f).testTag("price-alert-list")) {
                items(alerts, key = { it.id }) { alert ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(alert.id) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(alert.name.ifBlank { "价格警报" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text(pending[alert.id]?.label() ?: alert.stateLabel(), style = MaterialTheme.typography.labelMedium) }
                        Text("${alertDirection(alert.direction)} · ${formatInterval(alert.interval)} · ${if (alert.geometry.kind == "horizontal_segment") "水平线" else "趋势线"}", style = MaterialTheme.typography.bodyMedium)
                        Text("${alertNumber(alert.geometry.first.price)} → ${alertNumber(alert.geometry.second.price)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                if (legacy.isNotEmpty()) item { Text("旧版固定价位", Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
                items(legacy, key = { "legacy-${it.id}" }) { alert ->
                    Column(Modifier.fillMaxWidth().clickable { onLegacySelect(alert.id) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${alertNumber(alert.price)} · ${alertDirection(alert.direction)}", style = MaterialTheme.typography.titleMedium)
                        val status = when {
                            alert.status == "triggered" -> "已触发"
                            alert.expiresAt != null && alert.expiresAt <= System.currentTimeMillis() -> "已过期"
                            alert.status == "active" -> "监测中"
                            else -> "手动暂停"
                        }
                        Text("绑定 ${formatInterval(alert.interval)} · $status", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegacyPriceAlertSheet(alert: AlertDto, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onUpdate: (AlertPatchDto) -> Unit, onDelete: () -> Unit) {
    var price by rememberSaveable(alert.id) { mutableStateOf(alertNumber(alert.price)) }
    var direction by rememberSaveable(alert.id) { mutableStateOf(alert.direction) }
    var webhook by rememberSaveable(alert.id) { mutableStateOf(alert.webhookUrl) }
    var message by rememberSaveable(alert.id) { mutableStateOf(alert.messageTemplate) }
    var confirmDelete by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("旧版固定价位警报", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭旧版警报") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${alert.symbol} · ${formatInterval(alert.interval)}", style = MaterialTheme.typography.bodyMedium)
                AlertCoordinate("价格", price, { price = it }, !busy, numeric = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cross_any", "cross_up", "cross_down").forEach { value -> FilterChip(direction == value, { direction = value }, enabled = !busy, label = { Text(alertDirection(value)) }) }
                }
                Text(alert.expiresAt?.let { "到期 ${alertDate(it)}" } ?: "无限期", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(webhook, { webhook = it }, label = { Text("Webhook URL") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text("JSON 消息模板") }, enabled = !busy, minLines = 5, modifier = Modifier.fillMaxWidth())
                (formError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { onUpdate(AlertPatchDto(status = if (alert.status == "active") "disabled" else "active")) }) { Text(if (alert.status == "active") "暂停" else "重新启用") }
                Button(enabled = !busy, onClick = {
                    val value = price.toDoubleOrNull()
                    formError = if (value == null || !value.isFinite() || value <= 0) "请输入有效价格" else validateAlertTemplate(message)
                    if (formError == null) onUpdate(AlertPatchDto(price = value, direction = direction, webhookUrl = webhook.trim(), messageTemplate = message))
                }) { Text(if (busy) "保存中" else "保存修改") }
                TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("删除") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除旧版警报？") },
        text = { Text("旧版接口会同时删除该警报的历史记录。") }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceAlertHistorySheet(alert: PriceAlertDto, history: PriceAlertHistory, onDismiss: () -> Unit, onRefresh: () -> Unit, onOlder: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.events.firstOrNull()?.id) { listState.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("触发记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !history.busy) { Icon(Icons.Outlined.Refresh, "刷新触发记录") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭触发记录") }
            }
            Text(alert.name, style = MaterialTheme.typography.bodyMedium)
            if (history.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            history.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (history.events.isEmpty() && !history.busy) item { Text("暂无触发记录", Modifier.padding(vertical = 24.dp)) }
                items(history.events, key = { it.id }) { event ->
                    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${alertDirection(event.direction)} · ${alertDate(event.triggeredAt)}", style = MaterialTheme.typography.titleSmall)
                        Text("触发价 ${alertNumber(event.triggerPrice)} · 线价 ${alertNumber(event.linePrice)}", style = MaterialTheme.typography.bodyMedium)
                        Text("第 ${event.armGeneration} 次布防 · ${when (event.deliveryStatus) { "success" -> "接收成功"; "pending" -> "等待投递"; "cancelled" -> "投递已取消"; else -> "投递失败" }}", style = MaterialTheme.typography.bodySmall)
                        event.deliveryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起实际消息" else "查看实际消息") }
                        if (expanded) Text(PayloadJson.encodeToString(JsonElement.serializer(), event.payload), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
                if (history.more) item { TextButton(enabled = !history.busy, onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text("查看更早记录") } }
            }
        }
    }
}
