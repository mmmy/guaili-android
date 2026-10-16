package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.*
import androidx.glance.semantics.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.*
import com.gouge.guaili.ui.pullbackPeriodLabel
import com.gouge.guaili.ui.alertNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class PullbackWidgetFrame(val config: WidgetConfig, val settings: GuailiSettings, val preferences: MarketSignalPreferences,
    val snapshot: ServerSignalsSnapshot?, val failure: ServerSignalsFailure?, val projection: PullbackProjection,
    val alerts: PullbackWidgetAlertSnapshot, val evidence: Map<String, String>)

private val pullbackProjector = PullbackWidgetProjector()
internal val PullbackWidgetJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalCoroutinesApi::class)
internal fun pullbackWidgetFrames(context: Context, configurations: Flow<WidgetConfigurationState>): Flow<PullbackWidgetFrame?> =
    configurations.flatMapLatest { configuration ->
        if (configuration.config.mode != WidgetMode.PullbackObservation) flowOf(null)
        else {
            val signals = ServerSignalsSnapshotStore(context)
            combine(MarketSignalPreferencesStore(context).preferences, signals.snapshots, signals.failures,
                PullbackWidgetAlertStore(context, configuration.settings.baseUrl).snapshots) { preferences, snapshot, failure, alerts ->
                val scoped = snapshot?.takeIf { it.belongsTo(configuration.settings.baseUrl) }
                val projection = pullbackProjector.project(scoped, preferences.pullback.rules)
                val selected = configuration.config.symbols.toHashSet()
                val frozen = buildMap {
                    if (scoped != null) projection.rows.filter { it.symbol in selected }.forEach { row ->
                        listOfNotNull(row.long, row.short).forEach { candidate ->
                            put("${row.symbol}|${candidate.direction.name}", PullbackWidgetJson.encodeToString(
                                freezePullbackWidgetEvidence(row.symbol, candidate, preferences.pullback.rules, scoped)))
                        }
                    }
                }
                PullbackWidgetFrame(configuration.config, configuration.settings, preferences, scoped, failure, projection, alerts, frozen)
            }.flowOn(Dispatchers.Default)
        }
    }

@Composable
internal fun PullbackObservationWidgetContent(frame: PullbackWidgetFrame?, config: WidgetConfig,
    settings: GuailiSettings, refresh: WidgetRefreshStatus, appWidgetId: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val scoped = frame?.takeIf { normalizeServerSignalsBaseUrl(it.settings.baseUrl) == normalizeServerSignalsBaseUrl(settings.baseUrl) }
    val device = readGuailiDeviceTime(context)
    val state = pullbackWidgetState(scoped?.snapshot, scoped?.failure, scoped?.projection ?: PullbackProjection(),
        config, settings.baseUrl, device)
    val rules = scoped?.preferences?.pullback?.rules ?: PullbackRules()
    val scale = context.resources.configuration.fontScale
    val columns = remember(size.width, context.resources.displayMetrics.density, scale) {
        pullbackWidgetDimensions(context, size.width.value)
    }
    val snapshot = scoped?.snapshot
    val assessment = snapshot?.let { assessServerSignalsTime(it, device) }
    val now = assessment?.nowMillis ?: device.wallMillis
    val alertGroups = remember { PullbackWidgetAlertGroups() }
    val alerts = alertGroups.groups(scoped?.alerts?.alerts.orEmpty(), now)
    val rows = remember(state.rows, config.pullbackOnlyCandidates) {
        state.rows.filter { !config.pullbackOnlyCandidates || it.hasCandidate }
    }
    Column(GlanceModifier.fillMaxSize().background(PullbackWidgetBackground)
        .padding(horizontal = columns.outer.dp, vertical = 4.dp)) {
        PullbackWidgetHeader(state, rules.levels, config.pullbackOnlyCandidates, refresh, snapshot?.updatedAt,
            snapshot?.response?.evaluatedAt, appWidgetId, columns.wide, scale)
        when {
            scoped == null -> Text("正在读取采样…", style = TextStyle(color = PullbackMuted, fontSize = 12.sp))
            !state.projection.ready -> Text(state.warning ?: state.projection.message,
                style = TextStyle(color = PullbackWarning, fontSize = 12.sp), modifier = GlanceModifier.padding(8.dp))
            rows.isEmpty() -> Text(if (config.symbols.isEmpty()) "点击左上角选择品种" else "暂无回调候选",
                style = TextStyle(color = PullbackMuted, fontSize = 12.sp), modifier = GlanceModifier.padding(8.dp))
            else -> LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(rows, itemId = { stablePullbackRowId(it.symbol) }) { row ->
                    Column(GlanceModifier.fillMaxWidth()) {
                        Text(row.symbol, style = TextStyle(color = PullbackMuted, fontSize = 12.sp),
                            modifier = GlanceModifier.padding(horizontal = columns.inner.dp, vertical = 3.dp), maxLines = 1)
                        PullbackDirection.entries.forEach { direction ->
                            val candidate = row.candidate(direction)
                            if (!config.pullbackOnlyCandidates || candidate?.highlighted == true) {
                                val group = alerts[row.symbol]?.get(direction.name).orEmpty()
                                val alert = primaryPullbackAlert(group, scoped.preferences.pullback.primaryAlerts[
                                    priceAlertPrimaryKey(settings.baseUrl, row.symbol, direction.name)])
                                PullbackWidgetTarget(row.symbol, direction, candidate, scoped.evidence["${row.symbol}|${direction.name}"],
                                    alert, state.historical || scoped.alerts.error != null ||
                                        pullbackWidgetAlertsStale(settings.baseUrl, device), now, columns, appWidgetId, settings.baseUrl)
                            }
                        }
                        row.issue?.let { Text(it, style = TextStyle(color = PullbackWarning, fontSize = 11.sp),
                            modifier = GlanceModifier.padding(horizontal = columns.inner.dp, vertical = 2.dp), maxLines = 2) }
                        Spacer(GlanceModifier.height(6.dp))
                    }
                }
            }
        }
    }
}

internal data class PullbackWidgetDimensions(val outer: Int, val inner: Int, val level: Float,
    val entry: Float, val rowHeight: Float, val alertHeight: Float, val wide: Boolean, val stackAlerts: Boolean)

internal fun pullbackWidgetDimensions(context: Context, width: Float): PullbackWidgetDimensions {
    val metrics = context.resources.displayMetrics
    val scale = context.resources.configuration.fontScale
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 32f, metrics)
    val labels = MarketPeriods.all.map { pullbackPeriodLabel(it.interval) } + "—"
    val level = maxOf(48f, labels.maxOf(paint::measureText) / metrics.density + 2)
    val rowHeight = maxOf(48f, (paint.fontMetrics.bottom - paint.fontMetrics.top) / metrics.density + 4)
    paint.typeface = android.graphics.Typeface.DEFAULT
    paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
    val entry = maxOf(48f, labels.maxOf(paint::measureText) / metrics.density + 2)
    fun lineHeight(sp: Float): Float {
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        val font = paint.fontMetricsInt
        return (font.bottom - font.top) / metrics.density
    }
    // The alert has two lines (and a padded validity chip), independent of the target's font metrics.
    val alertHeight = maxOf(48f, maxOf(lineHeight(14f), lineHeight(12f) + 2, lineHeight(11f) * 2) +
        lineHeight(12f) + 8)
    val outer = if (width < 220 && scale > 1.2f) 2 else if (width >= 250) 12 else 8
    val inner = if (width < 220 && scale > 1.2f) 2 else 8
    val available = width - outer * 2 - inner * 2
    return PullbackWidgetDimensions(outer, inner, level, entry, rowHeight, alertHeight, width >= 250,
        scale > 1.2f || available - level - entry - 16 < 104)
}

@Composable
internal fun PullbackWidgetHeader(state: PullbackWidgetState, levels: Int, onlyCandidates: Boolean,
    refresh: WidgetRefreshStatus, updatedAt: Long?, sampledAt: Long?, appWidgetId: Int, wide: Boolean, scale: Float) {
    val feedback = refreshFeedbackText(refresh, snapshotUpdatedAt = updatedAt)
    val title = when {
        !state.projection.ready -> "待更新"
        feedback?.contains("超时") == true -> "超时"
        state.warning != null || feedback?.contains("失败") == true -> "失败"
        state.historical -> "快照旧"
        LocalSize.current.width < 320.dp && scale > 1.2f -> "${levels}级"
        else -> "回调${levels}级"
    }
    val description = "回调观察，${levels}级，采样 ${sampledAt?.let { PullbackSampleTime.format(java.time.Instant.ofEpochMilli(it)) } ?: "未知"}，多 ${state.longHits} 空 ${state.shortHits}，" +
        (state.warning ?: if (state.historical) "历史快照" else "采样快照") + "；打开参数与更多"
    Row(GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.defaultWeight().height(48.dp).semantics { contentDescription = description }
            .clickable(actionStartActivity(pullbackWidgetActivityIntent(LocalContext.current, appWidgetId))),
            contentAlignment = Alignment.CenterStart) {
            Text(title + if (LocalSize.current.width >= 320.dp && scale <= 1.2f) " 多${state.longHits} 空${state.shortHits}" else "",
                style = TextStyle(color = if (state.warning != null || state.historical) PullbackWarning else PullbackText,
                    fontSize = 12.sp), maxLines = 1)
        }
        Box(GlanceModifier.width(48.dp).height(48.dp)
            .background(if (onlyCandidates) PullbackSelected else ColorProvider(Color.Transparent))
            .semantics { contentDescription = "仅候选，${if (onlyCandidates) "已开启" else "已关闭"}" }
            .clickable(actionRunCallback<TogglePullbackCandidatesAction>()), contentAlignment = Alignment.Center) {
            Text("候选", style = TextStyle(color = if (onlyCandidates) PullbackAccent else PullbackMuted, fontSize = 12.sp))
        }
        if (wide) Box(GlanceModifier.width(48.dp).height(48.dp)
            .clickable(actionStartActivity(pullbackWidgetConfigurationIntent(LocalContext.current, appWidgetId))),
            contentAlignment = Alignment.Center) { Text("编辑", style = TextStyle(color = PullbackAccent, fontSize = 12.sp)) }
        Box(GlanceModifier.width(48.dp).height(48.dp).semantics { contentDescription = feedback ?: "刷新回调观察采样" }
            .clickable(actionRunCallback<RefreshPullbackWidgetAction>()), contentAlignment = Alignment.Center) {
            Text(if (feedback == "刷新中…") "更新" else "刷新", style = TextStyle(color = PullbackAccent, fontSize = 12.sp))
        }
    }
}

@Composable
internal fun PullbackWidgetTarget(symbol: String, direction: PullbackDirection, candidate: PullbackCandidate?,
    frozen: String?, alert: PriceAlertDto?, alertStale: Boolean, now: Long, dimensions: PullbackWidgetDimensions,
    appWidgetId: Int, baseUrl: String) {
    val context = LocalContext.current
    val highlighted = candidate?.highlighted == true
    val targetColor = when {
        candidate == null -> PullbackMuted
        direction == PullbackDirection.Long && highlighted -> PullbackLong
        direction == PullbackDirection.Short && highlighted -> PullbackShort
        direction == PullbackDirection.Long -> PullbackLongWaiting
        else -> PullbackShortWaiting
    }
    Column(GlanceModifier.fillMaxWidth().background(if (highlighted) PullbackCandidateBackground else ColorProvider(Color.Transparent))
        .cornerRadius(12.dp).padding(horizontal = dimensions.inner.dp, vertical = 2.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val targetAction = frozen?.let { actionStartActivity(pullbackWidgetActivityIntent(context, appWidgetId, it)) }
            Box(GlanceModifier.width(dimensions.level.dp).height(dimensions.rowHeight.dp)
                .semantics { contentDescription = "$symbol，${direction.label}，${candidate?.let { "${pullbackPeriodLabel(it.target.interval)}，${if (highlighted) "采样时为候选" else "等待回调"}，查看固定依据" } ?: "无合格目标"}" }
                .let { if (targetAction != null) it.clickable(targetAction) else it }, contentAlignment = Alignment.CenterStart) {
                Text(candidate?.target?.interval?.let(::pullbackPeriodLabel) ?: "—", maxLines = 1,
                    style = TextStyle(color = targetColor, fontSize = (if (highlighted) 32 else 28).sp,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium))
            }
            Spacer(GlanceModifier.width(8.dp))
            val entry = candidate?.entryInterval
            Box(GlanceModifier.width(dimensions.entry.dp).height(dimensions.rowHeight.dp)
                .let { if (entry != null) it.clickable(actionStartActivity(pullbackWidgetKlineIntent(context, symbol, entry, baseUrl))) else it }
                .semantics { contentDescription = "$symbol，${entry?.let { "推荐小级别${pullbackPeriodLabel(it)}，打开K线" } ?: "无更小级别"}" },
                contentAlignment = Alignment.CenterStart) {
                if (candidate != null) Text(entry?.let(::pullbackPeriodLabel) ?: "—",
                    style = TextStyle(color = PullbackMuted, fontSize = 16.sp), maxLines = 1)
            }
            if (dimensions.wide && !dimensions.stackAlerts && alert != null) {
                Spacer(GlanceModifier.width(8.dp))
                PullbackWidgetAlert(alert, now, alertStale, baseUrl, dimensions.alertHeight, GlanceModifier.defaultWeight())
            }
        }
        if (dimensions.wide && dimensions.stackAlerts && alert != null)
            PullbackWidgetAlert(alert, now, alertStale, baseUrl, dimensions.alertHeight, GlanceModifier.fillMaxWidth())
    }
    Spacer(GlanceModifier.height(2.dp))
}

@Composable
private fun PullbackWidgetAlert(alert: PriceAlertDto, now: Long, stale: Boolean, baseUrl: String, alertHeight: Float,
    modifier: GlanceModifier) {
    val display = alert.pullbackDisplay(now, stale)
    Box(modifier.height(alertHeight.dp).clickable(actionStartActivity(
        pullbackWidgetKlineIntent(LocalContext.current, alert.symbol, alert.interval, baseUrl, alert.id)))
        .semantics { contentDescription = "${alert.symbol}，${alertIntentLabel(alert.intent)}，${pullbackPeriodLabel(alert.interval)}，${display.description}，打开警报K线" },
        contentAlignment = Alignment.CenterStart) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(alertIntentLabel(alert.intent), style = TextStyle(color = PullbackMuted, fontSize = 11.sp), maxLines = 1)
            Spacer(GlanceModifier.width(4.dp))
            Text(pullbackPeriodLabel(alert.interval), style = TextStyle(color = PullbackMuted, fontSize = 14.sp), maxLines = 1)
            Spacer(GlanceModifier.width(4.dp))
            val unit = display.unit
            if (unit != null) {
                val (drawable, tint) = when (unit) {
                    AlertValidityUnit.Days -> com.gouge.guaili.R.drawable.pullback_widget_days to PullbackDays
                    AlertValidityUnit.Hours -> com.gouge.guaili.R.drawable.pullback_widget_hours to PullbackHours
                    AlertValidityUnit.Minutes -> com.gouge.guaili.R.drawable.pullback_widget_minutes to PullbackWarning
                }
                Text(display.label, style = TextStyle(color = tint, fontSize = 12.sp), maxLines = 1,
                    modifier = GlanceModifier.background(ImageProvider(drawable)).padding(horizontal = 4.dp, vertical = 1.dp))
            } else Text(display.label, style = TextStyle(color = if (display.label == "监测") PullbackMuted else PullbackWarning,
                fontSize = 11.sp), maxLines = 2)
        }
        Text(if (alert.geometry.kind == "horizontal_segment") "@${alertNumber(alert.geometry.first.price)}" else "斜线",
            style = TextStyle(color = PullbackMuted, fontSize = 12.sp), maxLines = 1)
      }
    }
}

class TogglePullbackCandidatesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        WidgetConfigStore(context).togglePullbackCandidates(id)
        GuailiWidget().update(context, glanceId)
    }
}

class RefreshPullbackWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        setWidgetRefreshStatus(context, glanceId, WidgetRefreshPhase.Refreshing)
        GuailiWidget().update(context, glanceId)
        GuailiWidgetScheduler.refreshNow(context, showFeedback = true, appWidgetId = id)
    }
}

internal fun pullbackWidgetActivityIntent(context: Context, id: Int, evidence: String? = null) =
    Intent(context, PullbackWidgetActivity::class.java).setData(Uri.parse("guaili://pullback/$id/${evidence?.hashCode() ?: "menu"}"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("frozen_evidence", evidence)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun pullbackWidgetConfigurationIntent(context: Context, id: Int) =
    Intent(context, GuailiWidgetConfigurationActivity::class.java).setData(Uri.parse("guaili://pullback/config/$id"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun stablePullbackRowId(symbol: String): Long =
    // Glance reserves the lower part of the negative Long range for its own item IDs.
    symbol.fold(-3750763034362895579L) { hash, char -> (hash xor char.code.toLong()) * 1099511628211L } and Long.MAX_VALUE

private fun colors(light: Long, dark: Long): ColorProvider = DayNightColorProvider(Color(light), Color(dark))
private val PullbackWidgetBackground = colors(0xFFF7F9FB, 0xFF171D24)
private val PullbackText = colors(0xFF242D36, 0xFFE3E8EE)
private val PullbackMuted = colors(0xFF57626D, 0xFFB4BDC8)
private val PullbackAccent = colors(0xFF126B92, 0xFF78CFF2)
private val PullbackSelected = colors(0xFFDCECF4, 0xFF243B48)
private val PullbackCandidateBackground = colors(0xFFE7EDF0, 0xFF252E39)
private val PullbackLong = colors(0xFF196B4B, 0xFFA7F3D0)
private val PullbackShort = colors(0xFFA92C2C, 0xFFFFB4AB)
private val PullbackLongWaiting = colors(0xFF4D705E, 0xFF8FB4A3)
private val PullbackShortWaiting = colors(0xFF875953, 0xFFC69791)
private val PullbackWarning = colors(0xFF8A5A1B, 0xFFDFBD7B)
private val PullbackDays = colors(0xFF406B97, 0xFF94B8DF)
private val PullbackHours = colors(0xFF70518D, 0xFFC2ACDF)
private val PullbackSampleTime = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
