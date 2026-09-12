package com.gouge.guaili.widget

import android.app.Activity
import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.theme.GuailiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import android.widget.Toast
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.parseGuailiTime
import com.gouge.guaili.data.isGuailiSnapshotStale

class GuailiWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var deliveryStatus by mutableStateOf(ReminderDeliveryStatus(false, false))
    private var saving by mutableStateOf(false)
    private var saveError by mutableStateOf<String?>(null)
    private var notificationPermissionDeclined = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionDeclined = !granted
        deliveryStatus = reminderDeliveryStatus(this)
    }
    private val exactAlarmAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        deliveryStatus = reminderDeliveryStatus(this)
    }

    override fun onResume() {
        super.onResume()
        deliveryStatus = reminderDeliveryStatus(this)
        DecisionReminderScheduler.rescheduleAll(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionDeclined = savedInstanceState?.getBoolean("notification_declined") ?: false
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        lifecycleScope.launch {
            val settings = SettingsStore(applicationContext).settings.first()
            val config = WidgetConfigStore(applicationContext).read(appWidgetId, settings)
            setContent {
                GuailiTheme {
                    WidgetConfigurationScreen(
                        settings = settings,
                        initialConfig = config,
                        onSave = ::saveAndFinish,
                        onDiscard = ::finish,
                        deliveryStatus = deliveryStatus,
                        onNotifications = ::requestNotifications,
                        onExactAlarms = ::requestExactAlarms,
                        saving = saving,
                        saveError = saveError,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_declined", notificationPermissionDeclined)
        super.onSaveInstanceState(outState)
    }

    private fun saveAndFinish(config: WidgetConfig, baseline: WidgetConfig) {
        if (!saving) persistAndFinish(config, baseline)
    }

    private fun requestNotifications() {
        if (!notificationPermissionDeclined && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            exactAlarmAccessLauncher.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requestIntent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            )
            runCatching { exactAlarmAccessLauncher.launch(requestIntent) }
                .onFailure {
                    saveError = "无法打开定时权限设置，当前提醒可能延迟"
                }
        }
    }

    private fun persistAndFinish(config: WidgetConfig, baseline: WidgetConfig) {
        saving = true
        saveError = null
        lifecycleScope.launch {
            try {
            DecisionReminderScheduler.saveConfiguration(applicationContext, appWidgetId, config, baseline)
            if (config.mode == WidgetMode.DecisionReminders) {
                DecisionReminderScheduler.rescheduleAllNow(applicationContext)
            }
            GuailiWidget().updateAll(applicationContext)
            if (config.mode != WidgetMode.DecisionReminders) {
                GuailiWidgetScheduler.refreshNow(applicationContext)
            }
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, result)
            if (config.mode == WidgetMode.DecisionReminders) {
                Toast.makeText(applicationContext, "已保存 · ${reminderDeliveryStatus(applicationContext).label}", Toast.LENGTH_LONG).show()
            }
            finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveError = "保存或调度失败，请重试：${error.message.orEmpty()}"
            } finally {
                saving = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigurationScreen(
    settings: GuailiSettings,
    initialConfig: WidgetConfig,
    onSave: (WidgetConfig, WidgetConfig) -> Unit,
    onDiscard: () -> Unit,
    deliveryStatus: ReminderDeliveryStatus,
    onNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    saving: Boolean,
    saveError: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSymbols by rememberSaveable(stateSaver = configurationSaver<List<String>>()) { mutableStateOf(initialConfig.symbols) }
    var selectedSingleSymbol by rememberSaveable {
        mutableStateOf(
            initialConfig.symbols.firstOrNull()
                ?: settings.symbols.firstOrNull(),
        )
    }
    var selectedIntervals by rememberSaveable(stateSaver = configurationSaver<List<String>>()) { mutableStateOf(initialConfig.intervals) }
    var mode by rememberSaveable { mutableStateOf(initialConfig.mode) }
    var enabledSignalKinds by rememberSaveable(stateSaver = configurationSaver<Set<GuailiSignalKind>>()) { mutableStateOf(initialConfig.enabledSignalKinds) }
    var singleSymbolColumns by rememberSaveable { mutableStateOf(initialConfig.singleSymbolColumns) }
    var reminders by rememberSaveable(stateSaver = configurationSaver<List<DecisionReminder>>()) { mutableStateOf(initialConfig.reminders) }
    var editingReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    var reminderSymbol by rememberSaveable {
        mutableStateOf(initialConfig.reminders.firstOrNull()?.symbol ?: settings.symbols.firstOrNull())
    }
    var reminderInterval by rememberSaveable {
        mutableStateOf(
            initialConfig.reminders.firstOrNull()?.interval
                ?: settings.intervals.firstOrNull { it == "15" }
                ?: settings.intervals.firstOrNull(),
        )
    }
    var reminderDirection by rememberSaveable { mutableStateOf(DecisionDirection.Long) }
    var reminderTargetAt by rememberSaveable {
        mutableLongStateOf(decisionReminderAfter(15L).toEpochMilli())
    }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val baseline by rememberSaveable(stateSaver = configurationSaver<WidgetConfig>()) { mutableStateOf(initialConfig) }
    val draft = buildWidgetConfig(mode, selectedSymbols, selectedSingleSymbol, selectedIntervals, singleSymbolColumns, reminders, enabledSignalKinds)
    val dirty = normalizeWidgetConfig(draft) != normalizeWidgetConfig(baseline) || editorDirty
    BackHandler(enabled = dirty || saving) { if (!saving) exitDialog = true }
    if (exitDialog) {
        AlertDialog(
            onDismissRequest = { exitDialog = false },
            title = { Text("有未保存修改") },
            text = { Text("返回继续编辑，或放弃本次修改。") },
            confirmButton = { TextButton(onClick = { exitDialog = false }) { Text("继续编辑") } },
            dismissButton = { TextButton(onClick = onDiscard) { Text("放弃修改") } },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
        Text(
            text = "配置乖离小组件",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when (mode) {
                WidgetMode.Signals ->
                    "监控最多 10 个品种，可分别启用回撤风险、均线压缩和级别冲突。"
                WidgetMode.Matrix ->
                    "选择最多 5 个品种和 4 个周期；上下滑动查看全部品种。"
                WidgetMode.SingleSymbol ->
                    "选择 1 个品种和每行列数，按全局设置的顺序查看全部周期。"
                WidgetMode.DecisionReminders ->
                    "手动设置品种、级别、方向和时间；每条提醒在组件中占一行。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

            LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                SectionTitle("显示模式")
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    WidgetMode.entries.forEach { candidate ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = {
                                mode = candidate
                                if (candidate == WidgetMode.Matrix) {
                                    selectedSymbols = normalizeWidgetSymbols(
                                        selectedSymbols,
                                        candidate,
                                    )
                                }
                            },
                            label = { Text(candidate.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            when (mode) {
            WidgetMode.SingleSymbol -> {
                item {
                    SectionTitle("品种（单选）")
                }
                items((listOfNotNull(selectedSingleSymbol) + settings.symbols).distinct(), key = { "single-symbol-$it" }) { symbol ->
                    SingleSelectionRow(
                        text = symbol + if (symbol !in settings.symbols) "（已移除，请重新选择）" else "",
                        selected = symbol == selectedSingleSymbol,
                        onSelect = { selectedSingleSymbol = symbol },
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SectionTitle("每行列数")
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        WidgetColumnCount.entries.forEach { candidate ->
                            FilterChip(
                                selected = singleSymbolColumns == candidate,
                                onClick = { singleSymbolColumns = candidate },
                                label = { Text(candidate.label) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
            WidgetMode.DecisionReminders -> {
                item {
                    Text(deliveryStatus.label, color = MaterialTheme.colorScheme.primary)
                    Row {
                        TextButton(onClick = onNotifications) { Text("通知设置") }
                        TextButton(onClick = onExactAlarms) { Text("定时权限") }
                    }
                    Text("到期未处理的提醒会保留；恢复通知后补发一次。", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    SectionTitle("提醒（${reminders.size}/${WidgetConfigStore.MaxReminders}）")
                }
                items(
                    items = sortDecisionReminders(reminders),
                    key = DecisionReminder::id,
                ) { reminder ->
                    DecisionReminderConfigRow(
                        reminder = reminder,
                        selected = reminder.id == editingReminderId,
                        onEdit = {
                            editingReminderId = reminder.id
                            reminderSymbol = reminder.symbol
                            reminderInterval = reminder.interval
                            reminderDirection = reminder.direction
                            reminderTargetAt = reminder.targetAtEpochMillis
                            editorDirty = false
                        },
                        onDelete = {
                            reminders = reminders.filterNot { it.id == reminder.id }
                            if (editingReminderId == reminder.id) editingReminderId = null
                            editorDirty = false
                        },
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SectionTitle(if (editingReminderId == null) "新增提醒" else "修改提醒")
                    Text("品种", style = MaterialTheme.typography.labelLarge)
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        settings.symbols.forEach { symbol ->
                            FilterChip(
                                selected = reminderSymbol == symbol,
                                onClick = { reminderSymbol = symbol; editorDirty = true },
                                label = { Text(symbol) },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    Text(
                        "级别",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        settings.intervals.forEach { interval ->
                            FilterChip(
                                selected = reminderInterval == interval,
                                onClick = { reminderInterval = interval; editorDirty = true },
                                label = { Text(formatReminderInterval(interval)) },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    Text(
                        "方向",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        DecisionDirection.entries.forEach { direction ->
                            FilterChip(
                                selected = reminderDirection == direction,
                                onClick = { reminderDirection = direction; editorDirty = true },
                                label = { Text("${direction.glyph} ${direction.label}") },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    Text(
                        "从现在起（精确增加所选时长）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        DecisionReminderPresets.forEach { preset ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    reminderTargetAt = decisionReminderAfter(preset.minutes)
                                        .toEpochMilli()
                                    editorDirty = true
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    TextButton(onClick = {
                        val current = Instant.ofEpochMilli(reminderTargetAt).atZone(ZoneId.systemDefault())
                        DatePickerDialog(context, { _, year, month, day ->
                            TimePickerDialog(context, { _, hour, minute ->
                                reminderTargetAt = java.time.LocalDateTime.of(year, month + 1, day, hour, minute)
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                editorDirty = true
                            }, current.hour, current.minute, true).show()
                        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                    }) { Text("选择日期和时间") }
                    TextButton(onClick = {
                        scope.launch {
                            val snapshot = GuailiSnapshotStore(context).read()
                            val cell = snapshot?.table?.cells?.get(reminderSymbol)?.get(reminderInterval)
                            val target = parseGuailiTime(cell?.closeTime, snapshot?.timezone)
                            if (snapshot == null || isGuailiSnapshotStale(snapshot.updatedAt) || cell?.isClosed != false || target == null || target <= System.currentTimeMillis()) {
                                formError = "没有可用的当前 K 线收线时间，请先刷新行情或手动选择时间"
                            } else {
                                reminderTargetAt = target + 1L
                                editorDirty = true
                                formError = null
                            }
                        }
                    }) { Text("当前周期下一次收线") }
                    Text(
                        "提醒时间：${formatReminderTarget(reminderTargetAt)} · ${formatDecisionCountdown(reminderTargetAt)}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (reminderTargetAt <= System.currentTimeMillis()) {
                                    formError = "提醒时间已过，请重新选择未来时间"
                                    return@Button
                                }
                                val id = editingReminderId ?: UUID.randomUUID().toString()
                                val reminder = DecisionReminder(
                                    id = id,
                                    symbol = requireNotNull(reminderSymbol),
                                    interval = requireNotNull(reminderInterval),
                                    direction = reminderDirection,
                                    targetAtEpochMillis = reminderTargetAt,
                                )
                                reminders = (reminders.filterNot { it.id == id } + reminder)
                                    .take(WidgetConfigStore.MaxReminders)
                                editingReminderId = null
                                reminderTargetAt = decisionReminderAfter(15L).toEpochMilli()
                                editorDirty = false
                                formError = null
                            },
                            enabled = reminderSymbol != null &&
                                reminderInterval != null &&
                                (editingReminderId != null || reminders.size < WidgetConfigStore.MaxReminders),
                        ) {
                            Text(if (editingReminderId == null) "加入待保存列表" else "更新待保存列表")
                        }
                        if (editingReminderId != null || editorDirty) {
                            TextButton(onClick = { editingReminderId = null; editorDirty = false }) {
                                Text("取消修改")
                            }
                        }
                    }
                }
            }
            else -> {
                if (mode == WidgetMode.Signals) {
                    item {
                        SectionTitle("启用的信号")
                        GuailiSignalKind.entries.forEach { kind ->
                            SignalKindSwitchRow(
                                text = signalKindLabel(kind),
                                enabled = kind in enabledSignalKinds,
                                onEnabledChange = { enabled ->
                                    enabledSignalKinds = if (enabled) {
                                        enabledSignalKinds + kind
                                    } else {
                                        enabledSignalKinds - kind
                                    }
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                item {
                    SectionTitle(
                        "品种（${selectedSymbols.size}/${WidgetConfigStore.maxSymbols(mode)}）",
                    )
                }
                items((selectedSymbols + settings.symbols).distinct(), key = { "symbol-$it" }) { symbol ->
                    val symbolLimit = WidgetConfigStore.maxSymbols(mode)
                    SelectionRow(
                        text = symbol + if (symbol !in settings.symbols) "（已移除，取消选择）" else "",
                        selected = symbol in selectedSymbols,
                        enabled = symbol in selectedSymbols || selectedSymbols.size < symbolLimit,
                        onToggle = {
                            selectedSymbols = toggleSelection(
                                current = selectedSymbols,
                                value = symbol,
                                limit = symbolLimit,
                            )
                        },
                    )
                    if (symbol in selectedSymbols) {
                        SelectionOrderControls(symbol, selectedSymbols) { selectedSymbols = it }
                    }
                }
            }
            }
            if (mode == WidgetMode.Matrix) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SectionTitle("周期（${selectedIntervals.size}/${WidgetConfigStore.MaxIntervals}）")
                }
                items((selectedIntervals + settings.intervals).distinct(), key = { "interval-$it" }) { interval ->
                    SelectionRow(
                        text = interval + if (interval !in settings.intervals) "（已移除，取消选择）" else "",
                        selected = interval in selectedIntervals,
                        enabled = interval in selectedIntervals || selectedIntervals.size < WidgetConfigStore.MaxIntervals,
                        onToggle = {
                            selectedIntervals = toggleSelection(
                                current = selectedIntervals,
                                value = interval,
                                limit = WidgetConfigStore.MaxIntervals,
                            )
                        },
                    )
                    if (interval in selectedIntervals) {
                        SelectionOrderControls(interval, selectedIntervals) { selectedIntervals = it }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(12.dp))
            if (dirty) Text("有未保存修改", color = MaterialTheme.colorScheme.primary)
            if (editorDirty) Text("请先将提醒加入待保存列表", style = MaterialTheme.typography.bodySmall)
            (saveError ?: formError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    onSave(
                        buildWidgetConfig(
                            mode = mode,
                            selectedSymbols = selectedSymbols,
                            selectedSingleSymbol = selectedSingleSymbol,
                            selectedIntervals = selectedIntervals,
                            singleSymbolColumns = singleSymbolColumns,
                            reminders = reminders,
                            enabledSignalKinds = enabledSignalKinds,
                        ),
                        baseline,
                    )
                },
                enabled = !saving && !(mode == WidgetMode.DecisionReminders && editorDirty) && when (mode) {
                    WidgetMode.Signals -> selectedSymbols.isNotEmpty()
                    WidgetMode.Matrix -> selectedSymbols.isNotEmpty() && selectedIntervals.isNotEmpty()
                    WidgetMode.SingleSymbol -> selectedSingleSymbol != null
                    WidgetMode.DecisionReminders -> true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saving) "保存中…" else "保存小组件")
            }
        }
    }
}

private inline fun <reified T : Any> configurationSaver(): Saver<T, String> = Saver(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<T>(it) },
)

@Composable
private fun SelectionOrderControls(value: String, values: List<String>, onChange: (List<String>) -> Unit) {
    val index = values.indexOf(value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("第 ${index + 1} 位", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = index > 0, onClick = { onChange(moveWidgetSelection(values, index, -1)) }) { Text("上移") }
        TextButton(enabled = index < values.lastIndex, onClick = { onChange(moveWidgetSelection(values, index, 1)) }) { Text("下移") }
    }
}

internal fun moveWidgetSelection(values: List<String>, index: Int, offset: Int): List<String> {
    if (index !in values.indices || index + offset !in values.indices) return values
    return values.toMutableList().apply { add(index + offset, removeAt(index)) }
}

internal fun buildWidgetConfig(
    mode: WidgetMode,
    selectedSymbols: List<String>,
    selectedSingleSymbol: String?,
    selectedIntervals: List<String>,
    singleSymbolColumns: WidgetColumnCount = WidgetColumnCount.Auto,
    reminders: List<DecisionReminder> = emptyList(),
    enabledSignalKinds: Set<GuailiSignalKind> = DefaultWidgetSignalKinds,
): WidgetConfig = WidgetConfig(
    symbols = if (mode == WidgetMode.SingleSymbol) {
        listOfNotNull(selectedSingleSymbol)
    } else {
        selectedSymbols
    },
    intervals = selectedIntervals,
    mode = mode,
    singleSymbolColumns = singleSymbolColumns,
    reminders = reminders,
    enabledSignalKinds = enabledSignalKinds,
)

@Composable
private fun DecisionReminderConfigRow(
    reminder: DecisionReminder,
    selected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = reminder.symbol,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(formatReminderInterval(reminder.interval), modifier = Modifier.width(48.dp))
        Text(
            "${reminder.direction.glyph}${reminder.direction.label}",
            color = if (reminder.direction == DecisionDirection.Long) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.width(58.dp),
        )
        Text(formatDecisionCountdown(reminder.targetAtEpochMillis), modifier = Modifier.width(72.dp))
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

private fun formatReminderInterval(interval: String): String =
    if (interval.all(Char::isDigit)) "${interval}m" else interval

private fun formatReminderTarget(epochMillis: Long): String =
    ReminderTargetFormatter.format(Instant.ofEpochMilli(epochMillis))

private val ReminderTargetFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

@Composable
private fun SingleSelectionRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
        )
        Text(text = text)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun SignalKindSwitchRow(
    text: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(vertical = 4.dp),
    ) {
        Text(text = text, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

private fun signalKindLabel(kind: GuailiSignalKind): String = when (kind) {
    GuailiSignalKind.Extreme -> "回撤 / 反弹风险"
    GuailiSignalKind.Compression -> "均线压缩"
    GuailiSignalKind.Conflict -> "级别冲突"
}

@Composable
private fun SelectionRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
        Text(
            text = text,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

internal fun toggleSelection(
    current: List<String>,
    value: String,
    limit: Int,
): List<String> = when {
    value in current -> current - value
    current.size < limit -> current + value
    else -> current
}
