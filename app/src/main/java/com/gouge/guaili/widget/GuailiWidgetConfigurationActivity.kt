package com.gouge.guaili.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.glance.appwidget.GlanceAppWidgetManager
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
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            GuailiWidget().update(applicationContext, glanceId)
            GuailiWidgetScheduler.refreshNow(applicationContext)
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
}

@Composable
private fun WidgetConfigurationScreen(
    settings: GuailiSettings,
    initialConfig: WidgetConfig,
    onSave: (WidgetConfig) -> Unit,
) {
    var selectedSymbols by remember { mutableStateOf(initialConfig.symbols) }
    var selectedIntervals by remember { mutableStateOf(initialConfig.intervals) }

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
            text = "配置乖离速览",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "选择最多 3 个品种和 4 个周期。小尺寸组件只显示第一个品种。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

            LazyColumn(modifier = Modifier.weight(1f)) {
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

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave(WidgetConfig(selectedSymbols, selectedIntervals))
                },
                enabled = selectedSymbols.isNotEmpty() && selectedIntervals.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存小组件")
            }
        }
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
