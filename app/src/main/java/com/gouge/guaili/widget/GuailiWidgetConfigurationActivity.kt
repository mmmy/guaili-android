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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class GuailiWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var configAwaitingNotificationPermission: WidgetConfig? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        configAwaitingNotificationPermission?.let(::requestExactAlarmAccessOrPersist)
        configAwaitingNotificationPermission = null
    }
    private var configAwaitingExactAlarmAccess: WidgetConfig? = null
    private val exactAlarmAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        configAwaitingExactAlarmAccess?.let(::persistAndFinish)
        configAwaitingExactAlarmAccess = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    )
                }
            }
        }
    }

    private fun saveAndFinish(config: WidgetConfig) {
        if (config.mode == WidgetMode.DecisionReminders &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            configAwaitingNotificationPermission = config
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestExactAlarmAccessOrPersist(config)
    }

    private fun requestExactAlarmAccessOrPersist(config: WidgetConfig) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (config.mode == WidgetMode.DecisionReminders &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            configAwaitingExactAlarmAccess = config
            val requestIntent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            )
            runCatching { exactAlarmAccessLauncher.launch(requestIntent) }
                .onFailure {
                    configAwaitingExactAlarmAccess = null
                    persistAndFinish(config)
                }
            return
        }
        persistAndFinish(config)
    }

    private fun persistAndFinish(config: WidgetConfig) {
        lifecycleScope.launch {
            val store = WidgetConfigStore(applicationContext)
            val settings = SettingsStore(applicationContext).settings.first()
            val previous = store.read(appWidgetId, settings)
            store.save(appWidgetId, config)
            DecisionReminderScheduler.replace(
                context = applicationContext,
                appWidgetId = appWidgetId,
                previous = previous.reminders,
                current = if (config.mode == WidgetMode.DecisionReminders) {
                    config.reminders
                } else {
                    emptyList()
                },
            )
            GuailiWidget().updateAll(applicationContext)
            if (config.mode != WidgetMode.DecisionReminders) {
                GuailiWidgetScheduler.refreshNow(applicationContext)
            }
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigurationScreen(
    settings: GuailiSettings,
    initialConfig: WidgetConfig,
    onSave: (WidgetConfig) -> Unit,
) {
    var selectedSymbols by remember { mutableStateOf(initialConfig.symbols) }
    var selectedSingleSymbol by remember {
        mutableStateOf(
            initialConfig.symbols.firstOrNull(settings.symbols::contains)
                ?: settings.symbols.firstOrNull(),
        )
    }
    var selectedIntervals by remember { mutableStateOf(initialConfig.intervals) }
    var mode by remember { mutableStateOf(initialConfig.mode) }
    var enabledSignalKinds by remember { mutableStateOf(initialConfig.enabledSignalKinds) }
    var singleSymbolColumns by remember { mutableStateOf(initialConfig.singleSymbolColumns) }
    var reminders by remember { mutableStateOf(initialConfig.reminders) }
    var editingReminderId by remember { mutableStateOf<String?>(null) }
    var reminderSymbol by remember {
        mutableStateOf(initialConfig.reminders.firstOrNull()?.symbol ?: settings.symbols.firstOrNull())
    }
    var reminderInterval by remember {
        mutableStateOf(
            initialConfig.reminders.firstOrNull()?.interval
                ?: settings.intervals.firstOrNull { it == "15" }
                ?: settings.intervals.firstOrNull(),
        )
    }
    var reminderDirection by remember { mutableStateOf(DecisionDirection.Long) }
    var reminderTargetAt by remember {
        mutableLongStateOf(alignedDecisionReminderTime(15L).toEpochMilli())
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
                    "选择最多 5 个品种和 4 个周期；组件根据尺寸显示前几个品种。"
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
                items(settings.symbols, key = { "single-symbol-$it" }) { symbol ->
                    SingleSelectionRow(
                        text = symbol,
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
                        },
                        onDelete = {
                            reminders = reminders.filterNot { it.id == reminder.id }
                            if (editingReminderId == reminder.id) editingReminderId = null
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
                                onClick = { reminderSymbol = symbol },
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
                                onClick = { reminderInterval = interval },
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
                                onClick = { reminderDirection = direction },
                                label = { Text("${direction.glyph} ${direction.label}") },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    Text(
                        "倒计时（先加时长，再向未来取整）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        DecisionReminderPresets.forEach { preset ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    reminderTargetAt = alignedDecisionReminderTime(preset.minutes)
                                        .toEpochMilli()
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                    }
                    Text(
                        "提醒时间：${formatReminderTarget(reminderTargetAt)} · ${formatDecisionCountdown(reminderTargetAt)}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
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
                                reminderTargetAt = alignedDecisionReminderTime(15L).toEpochMilli()
                            },
                            enabled = reminderSymbol != null &&
                                reminderInterval != null &&
                                (editingReminderId != null || reminders.size < WidgetConfigStore.MaxReminders),
                        ) {
                            Text(if (editingReminderId == null) "添加提醒" else "保存修改")
                        }
                        if (editingReminderId != null) {
                            TextButton(onClick = { editingReminderId = null }) {
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
                items(settings.symbols, key = { "symbol-$it" }) { symbol ->
                    val symbolLimit = WidgetConfigStore.maxSymbols(mode)
                    SelectionRow(
                        text = symbol,
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
                }
            }
            }
            if (mode == WidgetMode.Matrix) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SectionTitle("周期（${selectedIntervals.size}/${WidgetConfigStore.MaxIntervals}）")
                }
                items(settings.intervals, key = { "interval-$it" }) { interval ->
                    SelectionRow(
                        text = interval,
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
                }
            }
        }

            Spacer(modifier = Modifier.height(12.dp))
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
                    )
                },
                enabled = when (mode) {
                    WidgetMode.Signals -> selectedSymbols.isNotEmpty()
                    WidgetMode.Matrix -> selectedSymbols.isNotEmpty() && selectedIntervals.isNotEmpty()
                    WidgetMode.SingleSymbol -> selectedSingleSymbol != null
                    WidgetMode.DecisionReminders -> reminders.isNotEmpty()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存小组件")
            }
        }
    }
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
