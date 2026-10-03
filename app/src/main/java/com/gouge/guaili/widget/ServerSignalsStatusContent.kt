package com.gouge.guaili.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gouge.guaili.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ServerSignalsStatusContent(
    snapshot: ServerSignalsSnapshot?, failure: ServerSignalsFailure?, config: WidgetConfig,
    baseUrl: String, deviceTime: GuailiDeviceTime, onBack: () -> Unit, onRefresh: () -> Unit,
) {
    val state = serverSignalsWidgetState(snapshot, failure, config, baseUrl, deviceTime)
    val time = snapshot?.takeIf { it.belongsTo(baseUrl) }?.let { assessServerSignalsTime(it, deviceTime) }
    fun format(value: Long?) = value?.let {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    } ?: "未知"
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("信号 v2 状态", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回") }
            }
            HorizontalDivider()
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("服务器动态信号", style = MaterialTheme.typography.titleMedium)
                Text(if (state.signals.isNotEmpty()) "${state.signals.size}条可显示的服务器信号" else state.message)
                state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("来源：$baseUrl")
                Text("服务器每${state.evaluationIntervalMs / 1000}秒采样当前动态K，小组件显示最近获取的快照。")
                Text("采样频率不代表行情每次都有更新；行情过期或连接恢复时，相关周期暂不参与信号判断。")
                Text("服务器采样时间：${format(state.sampledAt)}")
                Text("手机获取时间：${format(state.fetchedAt)}")
                Text(time?.unavailableReason ?: time?.correctionMessage ?: "已按服务器时间校准")
                Text("桌面自动更新受系统限制；应用在前台刷新行情时也会更新 v2，可随时点击刷新。")
                snapshot?.takeIf { it.belongsTo(baseUrl) }?.response?.results?.filter { it.symbol in config.symbols }?.forEach { row ->
                    HorizontalDivider()
                    Text(row.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
                    val ready = row.perIntervalQuality.count { it.availability == "ready" }
                    val filtered = row.perIntervalQuality.count { it.availability == "filtered" }
                    Text("${ready}个周期可判断 · ${filtered}个周期被规则过滤")
                    serverSignalQualityLines(row).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新服务器信号") }
        }
    }
}
