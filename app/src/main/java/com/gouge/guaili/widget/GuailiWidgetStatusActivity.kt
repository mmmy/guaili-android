package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.assessGuailiTime
import com.gouge.guaili.data.readGuailiDeviceTime
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.theme.GuailiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class GuailiWidgetStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val snapshots = GuailiSnapshotStore(context).snapshots
        val configuration = WidgetConfigStore(context).observe(id, SettingsStore(context).settings)
        setContent {
            val snapshot by snapshots.collectAsState(initial = null)
            val configured by configuration.collectAsState(initial = null)
            var tick by remember { mutableLongStateOf(0) }
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) { delay(1_000); tick++ }
                }
            }
            val device = remember(tick, snapshot) { readGuailiDeviceTime(context) }
            val time = snapshot?.let { assessGuailiTime(it, device) }
            val status = snapshot?.let {
                val config = configured?.config
                widgetDataStatus(it, config?.symbols ?: it.table.symbols,
                    intervals = if (config?.mode == WidgetMode.Matrix) config.intervals else it.table.intervals,
                    time = time)
            }
            fun format(value: Long?) = value?.let {
                DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
            } ?: "未知"
            GuailiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("小组件状态", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { finish() }) { Text("返回") }
                        }
                        HorizontalDivider()
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("行情与数据", style = MaterialTheme.typography.titleMedium)
                            Text(status?.warning ?: status?.description ?: "暂无数据，请刷新",
                                color = if (status?.incomplete == true) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface)
                            status?.let {
                                Text("${it.ready}个周期可判断 · ${it.filtered}个周期被过滤")
                                if (it.unavailable > 0) Text("${it.unavailable}个品种周期组合尚无返回数据")
                                it.staleIntervalsBySymbol.forEach { (symbol, intervals) ->
                                    Text("${symbol.removeSuffix("USDT")} 过期：${intervals.joinToString("、")}")
                                }
                            }
                            HorizontalDivider()
                            Text("时间校准", style = MaterialTheme.typography.titleMedium)
                            Text(time?.unavailableReason ?: time?.correctionMessage ?: "已按服务器时间校准")
                            Text("行情判断时间：${format(time?.nowMillis)}")
                            Text("设备显示时间：${format(device.wallMillis)}")
                            Text("数据获取时间：${format(time?.fetchedAtMillis ?: snapshot?.updatedAt)}")
                            Text("最新收线时间：${format(status?.latestClosedAt)}")
                            HorizontalDivider()
                            Text("简写说明", style = MaterialTheme.typography.titleMedium)
                            Text("信号均为规则观察。级别数表示覆盖的周期数。↑／↓表示最大周期均线的方向；“未定”表示尚未满足连续方向条件，“未知”表示趋势信息不足。")
                            Text("“变化”与“原”表示此前区间的变化记录。“向上／向下离开”描述原近均线区间最短两个周期的收线变化。")
                        }
                        Button(onClick = { GuailiWidgetScheduler.refreshNow(context, showFeedback = true) },
                            modifier = Modifier.fillMaxWidth()) { Text("刷新行情") }
                    }
                }
            }
        }
    }
}
