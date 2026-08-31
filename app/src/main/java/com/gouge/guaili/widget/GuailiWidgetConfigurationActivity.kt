package com.gouge.guaili.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.theme.GuailiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GuailiWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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
        lifecycleScope.launch {
            WidgetConfigStore(applicationContext).save(appWidgetId, config)
            GuailiWidget().updateAll(applicationContext)
            GuailiWidgetScheduler.refreshNow(applicationContext)
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
    var singleSymbolColumns by remember { mutableStateOf(initialConfig.singleSymbolColumns) }

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
                    "监控最多 5 个品种，按优先级显示回撤风险、均线压缩和级别冲突。"
                WidgetMode.Matrix ->
                    "选择最多 5 个品种和 4 个周期；组件根据尺寸显示前几个品种。"
                WidgetMode.SingleSymbol ->
                    "选择 1 个品种和每行列数，按全局设置的顺序查看全部周期。"
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
                            onClick = { mode = candidate },
                            label = { Text(candidate.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            if (mode == WidgetMode.SingleSymbol) {
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
            } else {
                item {
                    SectionTitle("品种（${selectedSymbols.size}/${WidgetConfigStore.MaxSymbols}）")
                }
                items(settings.symbols, key = { "symbol-$it" }) { symbol ->
                    SelectionRow(
                        text = symbol,
                        selected = symbol in selectedSymbols,
                        enabled = symbol in selectedSymbols || selectedSymbols.size < WidgetConfigStore.MaxSymbols,
                        onToggle = {
                            selectedSymbols = toggleSelection(
                                current = selectedSymbols,
                                value = symbol,
                                limit = WidgetConfigStore.MaxSymbols,
                            )
                        },
                    )
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
                        ),
                    )
                },
                enabled = when (mode) {
                    WidgetMode.Signals -> selectedSymbols.isNotEmpty()
                    WidgetMode.Matrix -> selectedSymbols.isNotEmpty() && selectedIntervals.isNotEmpty()
                    WidgetMode.SingleSymbol -> selectedSingleSymbol != null
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
): WidgetConfig = WidgetConfig(
    symbols = if (mode == WidgetMode.SingleSymbol) {
        listOfNotNull(selectedSingleSymbol)
    } else {
        selectedSymbols
    },
    intervals = selectedIntervals,
    mode = mode,
    singleSymbolColumns = singleSymbolColumns,
)

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
