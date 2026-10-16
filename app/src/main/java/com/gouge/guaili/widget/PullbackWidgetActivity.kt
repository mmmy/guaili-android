package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.*
import com.gouge.guaili.settings.*
import com.gouge.guaili.ui.PullbackEvidenceSheet
import com.gouge.guaili.ui.PullbackParametersSheet
import com.gouge.guaili.ui.PullbackSelection
import com.gouge.guaili.ui.theme.GuailiTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Activity-owned evidence is immutable; broadcasts and new snapshots never reopen it. */
class PullbackWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val evidence = intent.getStringExtra("frozen_evidence")?.let {
            runCatching { PullbackWidgetJson.decodeFromString<PullbackWidgetEvidence>(it).also { value ->
                require(value.evidence.isNotEmpty() && value.evidence.size <= 64 && value.rules.error() == null)
                value.candidate()
            } }.getOrNull()
        }
        val settingsStore = SettingsStore(applicationContext)
        val preferences = MarketSignalPreferencesStore(applicationContext)
        val configurations = WidgetConfigStore(applicationContext).observe(id, settingsStore.settings)
        val frames = pullbackWidgetFrames(applicationContext, configurations)
        setContent {
            val frame by frames.collectAsState(initial = null)
            val currentPrefs by preferences.preferences.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            var parameters by rememberSaveable { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var saving by remember { mutableStateOf(false) }
            GuailiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("回调观察小组件", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton({ finish() }) { Text("关闭") }
                        }
                        frame?.let { value ->
                            val sampled = value.snapshot?.response?.evaluatedAt
                            Text("采样 ${sampled?.let { WidgetSampleTime.format(Instant.ofEpochMilli(it)) } ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            val selected = value.projection.rows.filter { it.symbol in value.config.symbols }
                            Text("采样快照 · 多 ${selected.count { it.long?.highlighted == true }} · 空 ${selected.count { it.short?.highlighted == true }}",
                                style = MaterialTheme.typography.bodySmall)
                            if (!value.projection.ready) Text(value.projection.message, color = MaterialTheme.colorScheme.error)
                            value.failure?.takeIf { normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(value.settings.baseUrl) }
                                ?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                            value.alerts.error?.let { Text("警报待更新：$it", color = MaterialTheme.colorScheme.error) }
                        }
                        Text("结果表示采样时的目标与候选；后台更新可能延迟。", Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton({ parameters = true }, Modifier.heightIn(min = 48.dp), enabled = currentPrefs != null) {
                            Text("回调参数 · ${currentPrefs?.pullback?.rules?.levels ?: "—"}级")
                        }
                        TextButton({ startActivity(pullbackWidgetConfigurationIntent(this@PullbackWidgetActivity, id)) },
                            Modifier.heightIn(min = 48.dp)) { Text("编辑关注品种与候选筛选") }
                        TextButton({ GuailiWidgetScheduler.refreshNow(applicationContext, showFeedback = true, appWidgetId = id) },
                            Modifier.heightIn(min = 48.dp)) { Text("刷新采样") }
                        TextButton({ startActivity(pullbackWidgetAppIntent(this@PullbackWidgetActivity)); finish() },
                            Modifier.heightIn(min = 48.dp)) { Text("进入回调观察页") }
                        frame?.let { value ->
                            Text("来源：${value.settings.baseUrl}\n后台约 15 分钟更新，支持手动刷新。",
                                Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (evidence != null) {
                        val selection = remember { PullbackSelection(evidence.symbol, evidence.candidate(), evidence.rules,
                            evidence.sampledAt, evidence.average) }
                        PullbackEvidenceSheet(selection, live = false, onDismiss = { finish() }, onKline = {
                            startActivity(pullbackWidgetKlineIntent(this@PullbackWidgetActivity, evidence.symbol,
                                selection.candidate.target.interval, evidence.baseUrl))
                        })
                    }
                    val loaded = currentPrefs
                    if (parameters && loaded != null) PullbackParametersSheet(loaded.pullback.rules, loaded.pullback.extensionBars,
                        onDismiss = { if (!saving) parameters = false }, externalError = error, saving = saving, onSave = { rules, extension ->
                            scope.launch {
                                if (saving) return@launch
                                saving = true
                                try {
                                    preferences.update { it.copy(pullback = it.pullback.copy(rules = rules, extensionBars = extension)) }
                                    parameters = false
                                    error = null
                                    updatePullbackWidgets(applicationContext)
                                } catch (exception: kotlinx.coroutines.CancellationException) { throw exception }
                                catch (_: Exception) { error = "参数未保存，请重试" }
                                finally { saving = false }
                            }
                        })
                }
            }
        }
    }
}

private val WidgetSampleTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun pullbackWidgetAppIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .putExtra("widget_market_view", "Pullback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

internal fun pullbackWidgetKlineIntent(context: Context, symbol: String, interval: String, baseUrl: String,
    alertId: Long? = null): Intent = pullbackWidgetAppIntent(context)
    .setData(Uri.parse("guaili://pullback/kline/$symbol/$interval/${alertId ?: "level"}/${baseUrl.hashCode()}"))
    .putExtra(MainActivity.ExtraWidgetSymbol, symbol).putExtra(MainActivity.ExtraWidgetInterval, interval)
    .putExtra("widget_market_source", baseUrl).apply { if (alertId != null) putExtra("widget_price_alert", alertId) }
