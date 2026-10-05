package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gouge.xbot.ui.theme.XbotTheme

class AlertWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val store = AlertWidgetStore(this)
        setContent {
            XbotTheme {
                AlertWidgetSettingsScreen(store.settings(id), onCancel = { finish() }) { settings ->
                    store.saveSettings(id, settings)
                    AlertWidgetRenderer.render(this, id)
                    AlertWidgetScheduler.schedulePeriodic(this)
                    AlertWidgetScheduler.enqueueImmediate(this)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

@Composable
fun AlertWidgetSettingsScreen(initial: AlertWidgetSettings, onCancel: () -> Unit, onSave: (AlertWidgetSettings) -> Unit) {
    var settings by remember(initial) { mutableStateOf(initial) }
    Surface {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("警报小组件设置", style = MaterialTheme.typography.headlineSmall)
            Text("数据跟随首页「显示」中已勾选的警报配置。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("展示方式", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.mode == AlertWidgetMode.Time,
                    onClick = { settings = settings.copy(mode = AlertWidgetMode.Time) }, label = { Text("按时间") })
                FilterChip(selected = settings.mode == AlertWidgetMode.Grouped,
                    onClick = { settings = settings.copy(mode = AlertWidgetMode.Grouped) }, label = { Text("按组聚合") })
            }
            Text(if (settings.mode == AlertWidgetMode.Grouped) "组内时间排序" else "时间排序", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.order == AlertWidgetOrder.Earliest,
                    onClick = { settings = settings.copy(order = AlertWidgetOrder.Earliest) }, label = { Text("最早优先") })
                FilterChip(selected = settings.order == AlertWidgetOrder.Latest,
                    onClick = { settings = settings.copy(order = AlertWidgetOrder.Latest) }, label = { Text("最晚优先") })
            }
            Text("行高", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.density == AlertWidgetDensity.Normal,
                    onClick = { settings = settings.copy(density = AlertWidgetDensity.Normal) }, label = { Text("正常") })
                FilterChip(selected = settings.density == AlertWidgetDensity.Compact,
                    onClick = { settings = settings.copy(density = AlertWidgetDensity.Compact) }, label = { Text("紧凑") })
            }
            Text("每条警报最多两行，剩余时间按天、小时或不足1小时显示。分组顺序跟随首页，未配置过期时间的警报置底。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onSave(settings) }, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}
