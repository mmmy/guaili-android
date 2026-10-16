package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.currentState
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.ServerSignalsSnapshot
import com.gouge.guaili.data.ServerSignalsSnapshotStore
import com.gouge.guaili.data.ServerSignalsFailure
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiBackgroundArgb
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.signals.SignalCardStyle
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.data.assessGuailiTime
import com.gouge.guaili.data.readGuailiDeviceTime
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.GroupedLayoutDimensions
import com.gouge.guaili.ui.formatInterval
import com.gouge.guaili.ui.groupedLayoutDimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GuailiWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(250.dp, 140.dp),
            DpSize(320.dp, 180.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val configuration = WidgetConfigStore(context).observe(appWidgetId, SettingsStore(context).settings)
        val initialConfiguration = configuration.first()
        val snapshotStore = GuailiSnapshotStore(context)
        val initialMode = initialConfiguration.config.mode
        val initialSnapshot = if (initialMode.usesLegacyMarketSnapshot()) {
            snapshotStore.read()
        } else null
        val serverSnapshotStore = ServerSignalsSnapshotStore(context)
        val initialServerSnapshot = if (initialMode == WidgetMode.SignalsV2) serverSnapshotStore.read() else null
        val initialServerFailure = if (initialMode == WidgetMode.SignalsV2) serverSnapshotStore.readFailure() else null
        val deliveryStatus = reminderDeliveryStatus(context)
        val pullbackFrames = pullbackWidgetFrames(context, configuration)
        val initialPullback = if (initialMode == WidgetMode.PullbackObservation) pullbackFrames.first() else null

        provideContent {
            // updateAll does not restart provideGlance while its session is active.
            // Observe saves so refresh feedback and signals use the same latest data.
            val currentConfiguration by configuration.collectAsState(initialConfiguration)
            val mode = currentConfiguration.config.mode
            val snapshot = if (mode.usesLegacyMarketSnapshot()) {
                val value by snapshotStore.snapshots.collectAsState(initialSnapshot)
                value
            } else null
            val serverSnapshot = if (mode == WidgetMode.SignalsV2) {
                val value by serverSnapshotStore.snapshots.collectAsState(initialServerSnapshot)
                value
            } else null
            val serverFailure = if (mode == WidgetMode.SignalsV2) {
                val value by serverSnapshotStore.failures.collectAsState(initialServerFailure)
                value
            } else null
            val refreshStatus = currentState<Preferences>().widgetRefreshStatus()
            val pullbackFrame = if (mode == WidgetMode.PullbackObservation) {
                val value by pullbackFrames.collectAsState(initialPullback)
                value
            } else null
            GuailiWidgetContent(
                snapshot = snapshot,
                config = currentConfiguration.config,
                settings = currentConfiguration.settings,
                refreshStatus = refreshStatus,
                appWidgetId = appWidgetId,
                deliveryStatus = deliveryStatus,
                serverSnapshot = serverSnapshot,
                serverFailure = serverFailure,
                pullbackFrame = pullbackFrame,
            )
        }
    }
}

class GuailiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GuailiWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GuailiWidgetScheduler.schedulePeriodic(context)
        GuailiWidgetScheduler.refreshNow(context)
        DecisionReminderScheduler.schedulePeriodicWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        GuailiWidgetScheduler.cancelPeriodic(context)
        DecisionReminderScheduler.cancelPeriodicWidgetUpdates(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settings = SettingsStore(context).settings.first()
            val store = WidgetConfigStore(context)
            appWidgetIds.forEach { appWidgetId ->
                val config = store.read(appWidgetId, settings)
                DecisionReminderScheduler.replace(
                    context = context,
                    appWidgetId = appWidgetId,
                    previous = config.reminders,
                    current = emptyList(),
                )
                store.delete(appWidgetId)
            }
            DecisionReminderScheduler.schedulePeriodicWidgetUpdates(context)
        }
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        setWidgetRefreshStatus(context, glanceId, WidgetRefreshPhase.Refreshing)
        GuailiWidget().update(context, glanceId)
        GuailiWidgetScheduler.refreshNow(context, showFeedback = true)
    }
}

@Composable
private fun GuailiWidgetContent(
    snapshot: GuailiSnapshot?,
    config: WidgetConfig,
    settings: GuailiSettings,
    refreshStatus: WidgetRefreshStatus,
    appWidgetId: Int,
    deliveryStatus: ReminderDeliveryStatus,
    serverSnapshot: ServerSignalsSnapshot? = null,
    serverFailure: ServerSignalsFailure? = null,
    pullbackFrame: PullbackWidgetFrame? = null,
) {
    if (config.mode == WidgetMode.PullbackObservation) {
        PullbackObservationWidgetContent(pullbackFrame, config, settings, refreshStatus, appWidgetId)
        return
    }
    if (config.mode == WidgetMode.SignalsV2) {
        ServerSignalsV2WidgetContent(serverSnapshot, serverFailure, config, settings, refreshStatus, appWidgetId)
        return
    }
    if (config.mode == WidgetMode.DecisionReminders) {
        DecisionReminderWidgetContent(
            reminders = config.reminders,
            appWidgetId = appWidgetId,
            deliveryStatus = deliveryStatus,
        )
        return
    }
    val size = LocalSize.current
    val deviceTime = readGuailiDeviceTime(LocalContext.current)
    val time = snapshot?.let { assessGuailiTime(it, deviceTime) }
    val now = time?.nowMillis ?: deviceTime.wallMillis
    val dataStatus = snapshot?.let {
        widgetDataStatus(it, config.symbols, now,
            if (config.mode == WidgetMode.Matrix) config.intervals else it.table.intervals, time,
            dynamicSignals = config.mode == WidgetMode.Signals)
    }
    val configIssue = widgetConfigurationIssue(config, settings.symbols, settings.intervals)
    val signals = if (snapshot == null || config.mode != WidgetMode.Signals || dataStatus?.snapshotStale == true ||
        dataStatus?.timeUncertain == true || configIssue != null) {
        emptyList()
    } else {
        widgetSignals(snapshot, config, now)
    }

    val symbols = config.symbols
    val intervals = config.intervals
    val cellWidth = if (intervals.isEmpty()) {
        40.dp
    } else {
        (size.width - 20.dp - SymbolWidth) / intervals.size
    }

    val useGroupedStyle = config.mode == WidgetMode.SingleSymbol
    val contentPadding = when {
        useGroupedStyle -> 0.dp
        config.mode == WidgetMode.Signals -> signalWidgetOuterPadding(size.width.value,
            LocalContext.current.resources.configuration.fontScale).dp
        else -> 10.dp
    }
    val contentWidth = size.width - contentPadding * 2
    val singleSymbolDimensions = groupedLayoutDimensions(
        widthDp = contentWidth.value.toInt(),
        size = settings.groupLayoutSize,
        density = settings.tableDensity,
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(if (useGroupedStyle) GroupedBackground else WidgetBackground)
            .padding(horizontal = contentPadding, vertical = if (config.mode == WidgetMode.Signals) 10.dp else contentPadding),
    ) {
        WidgetHeader(
            snapshot = snapshot,
            appWidgetId = appWidgetId,
            title = when (config.mode) {
                WidgetMode.Signals -> "乖离信号"
                WidgetMode.SignalsV2 -> "乖离信号 v2"
                WidgetMode.PullbackObservation -> "回调观察"
                WidgetMode.Matrix -> "乖离矩阵"
                WidgetMode.SingleSymbol -> singleSymbolTitle(config.symbols.firstOrNull().orEmpty())
                WidgetMode.DecisionReminders -> "决策提醒"
            },
            darkStyle = useGroupedStyle,
            darkHeaderHeight = singleSymbolDimensions.symbolHeaderHeight,
            refreshStatus = refreshStatus,
            dataStatus = dataStatus,
            signalCount = if (config.mode == WidgetMode.Signals) signals.size else null,
        )
        Spacer(modifier = GlanceModifier.height(if (useGroupedStyle) 2.dp else if (config.mode == WidgetMode.Signals) 3.dp else 6.dp))
        when {
            configIssue != null -> Text(
                text = "$configIssue；点击编辑重新选择",
                style = TextStyle(color = WarningText, fontSize = 11.sp),
                modifier = GlanceModifier.clickable(actionStartActivity(widgetConfigurationIntent(appWidgetId))),
            )
            snapshot == null || config.symbols.isEmpty() -> EmptyWidgetContent()
            config.mode == WidgetMode.Signals -> {
                if (signals.isEmpty()) {
                    NoSignalContent(
                        monitoredSymbols = config.symbols.size,
                        hasEnabledSignalKinds = config.enabledSignalKinds.isNotEmpty(),
                        dataStatus = dataStatus,
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                        items(signals) { signal ->
                            SignalRow(signal, movingAverageLabel(snapshot))
                        }
                    }
                }
            }
            config.mode == WidgetMode.SingleSymbol -> {
                val allIntervals = snapshot.table.intervals
                if (allIntervals.isEmpty()) {
                    EmptyWidgetContent()
                } else {
                    SingleSymbolPeriodList(
                        symbol = config.symbols.first(),
                        intervals = allIntervals,
                        snapshot = snapshot,
                        dimensions = singleSymbolDimensions,
                        columns = config.singleSymbolColumns.count
                            ?: singleSymbolDimensions.columns,
                        modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                    )
                }
            }
            intervals.isEmpty() -> EmptyWidgetContent()
            else -> {
                val intervalGroups = matrixIntervalGroups(intervals, size.width.value.toInt())
                LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    if (intervalGroups.size == 1) item { MatrixHeader(intervals, cellWidth) }
                    items(symbols) { symbol ->
                        Column {
                            intervalGroups.forEach { group ->
                                val groupCellWidth = (size.width - 20.dp - SymbolWidth) / group.size
                                if (intervalGroups.size > 1) MatrixHeader(group, groupCellWidth)
                                MatrixRow(
                                    symbol = symbol,
                                    intervals = group,
                                    cellWidth = groupCellWidth,
                                    snapshot = snapshot,
                                )
                            }
                        }
                    }
                    item { Text("${symbols.size}个品种 · 上下滑动", style = TextStyle(color = SecondaryText, fontSize = 9.sp)) }
                }
            }
        }
    }
}

@Composable
private fun ServerSignalsV2WidgetContent(
    snapshot: ServerSignalsSnapshot?, failure: ServerSignalsFailure?, config: WidgetConfig,
    settings: GuailiSettings, refreshStatus: WidgetRefreshStatus, appWidgetId: Int,
) {
    val state = serverSignalsWidgetState(snapshot, failure, config, settings.baseUrl,
        readGuailiDeviceTime(LocalContext.current))
    val size = LocalSize.current
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val compactHeader = compactSignalWidgetHeader(size.width.value, fontScale)
    val narrow = size.width < 250.dp || compactHeader
    val wide = size.width >= 320.dp && !compactHeader
    val feedback = refreshFeedbackText(refreshStatus, snapshotUpdatedAt = snapshot?.updatedAt)
    val time = state.fetchedAt?.let {
        DateTimeFormatter.ofPattern(if (wide) "HH:mm:ss" else "HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    } ?: "待刷新"
    val refreshLabel = when {
        feedback == "刷新中…" -> "刷新中"
        feedback?.contains("失败") == true -> "失败"
        feedback?.contains("超时") == true -> "超时"
        else -> time
    }
    Column(modifier = GlanceModifier.fillMaxSize().background(WidgetBackground)
        .padding(horizontal = signalWidgetOuterPadding(size.width.value, fontScale).dp, vertical = 10.dp)) {
        SignalWidgetHeader(null, appWidgetId, refreshStatus, null, state.signals.size,
            version = "v2", refreshLabelOverride = refreshLabel,
            statusLabelOverride = serverWidgetStatusLabel(state, narrow),
            warningOverride = state.status != "ready")
        Spacer(modifier = GlanceModifier.height(3.dp))
        if (state.signals.isEmpty()) {
            Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth().padding(vertical = 6.dp)) {
                Text(state.message, style = TextStyle(color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 3)
                Spacer(modifier = GlanceModifier.height(5.dp))
                Text(state.detail, style = TextStyle(color = SecondaryText, fontSize = 10.sp), maxLines = 2)
                state.warning?.let { Text(it, style = TextStyle(color = WarningText, fontSize = 9.sp), maxLines = 2) }
            }
        } else {
            val response = requireNotNull(snapshot).response
            val presentations = state.signals.mapNotNull { serverWidgetPresentation(it, response, state.nowMillis) }
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(presentations) { signal -> SignalRow(signal, serverMovingAverageLabel(response)) }
            }
        }
    }
}

@Composable
private fun DecisionReminderWidgetContent(
    reminders: List<DecisionReminder>,
    appWidgetId: Int,
    deliveryStatus: ReminderDeliveryStatus,
) {
    val now = System.currentTimeMillis()
    val ordered = sortDecisionReminders(reminders, now)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .padding(10.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "决策提醒",
                style = TextStyle(
                    color = PrimaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
            )
            Text(
                text = "${ordered.size} 条",
                style = TextStyle(color = SecondaryText, fontSize = 10.sp),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = "编辑",
                style = TextStyle(
                    color = AccentText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clickable(actionStartActivity(widgetConfigurationIntent(appWidgetId))),
            )
        }
        Text(
            deliveryStatus.label,
            style = TextStyle(color = if (deliveryStatus.notificationsEnabled && deliveryStatus.exactAlarms) SecondaryText else WarningText, fontSize = 9.sp),
            modifier = GlanceModifier.clickable(actionStartActivity(widgetConfigurationIntent(appWidgetId))),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (ordered.isEmpty()) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "点击编辑添加提醒",
                    style = TextStyle(color = SecondaryText, fontSize = 12.sp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(ordered) { reminder ->
                    DecisionReminderWidgetRow(reminder = reminder, nowEpochMillis = now)
                }
            }
        }
    }
}

@Composable
private fun DecisionReminderWidgetRow(
    reminder: DecisionReminder,
    nowEpochMillis: Long,
) {
    val expired = reminder.targetAtEpochMillis <= nowEpochMillis
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (expired) ReminderDueBackground else SignalBackground)
            .padding(horizontal = 7.dp, vertical = 7.dp)
            .clickable(actionStartActivity(klineIntent(reminder.symbol, reminder.interval))),
    ) {
        Text(
            text = "${displaySymbol(reminder.symbol)} · ${displayInterval(reminder.interval)} · ${reminder.direction.glyph}${reminder.direction.label}",
            style = TextStyle(
                color = PrimaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
            maxLines = 1,
        )
        Text(
            text = formatDecisionReminderDisplay(
                reminder.targetAtEpochMillis,
                nowEpochMillis,
            ),
            style = TextStyle(
                color = if (expired) WarningText else PrimaryText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
            maxLines = 1,
        )
    }
}

@Composable
private fun WidgetHeader(
    snapshot: GuailiSnapshot?,
    appWidgetId: Int,
    title: String,
    darkStyle: Boolean = false,
    darkHeaderHeight: androidx.compose.ui.unit.Dp? = null,
    refreshStatus: WidgetRefreshStatus = WidgetRefreshStatus(),
    dataStatus: WidgetDataStatus? = null,
    signalCount: Int? = null,
) {
    if (signalCount != null) {
        SignalWidgetHeader(snapshot, appWidgetId, refreshStatus, dataStatus, signalCount)
        return
    }
    val feedbackText = refreshFeedbackText(refreshStatus, snapshotUpdatedAt = snapshot?.updatedAt)
    val refreshing = refreshStatus.phase == WidgetRefreshPhase.Refreshing && feedbackText == "刷新中…"
    val headerModifier = if (darkStyle && darkHeaderHeight != null) {
        GlanceModifier
            .fillMaxWidth()
            .height(darkHeaderHeight)
            .background(GroupedHeaderBackground)
            .padding(horizontal = 10.dp)
    } else {
        GlanceModifier.fillMaxWidth()
    }
    Column {
    Row(
        modifier = headerModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = if (darkStyle) GroupedSecondaryText else SecondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(mainActivityIntent())),
            maxLines = 1,
        )
        Text(
            text = "编辑",
            style = TextStyle(
                color = SecondaryText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
            ),
            modifier = GlanceModifier
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .clickable(actionStartActivity(widgetConfigurationIntent(appWidgetId))),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = if (refreshing) "刷新中…" else "↻ 刷新",
            style = TextStyle(
                color = SecondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
            ),
            modifier = GlanceModifier
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .then(if (refreshing) GlanceModifier else GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>())),
        )
    }
    Text(
        text = widgetRefreshSummary(refreshStatus, snapshot?.updatedAt, dataStatus?.latestClosedAt,
            fetchedDisplayAt = dataStatus?.fetchedAtMillis ?: snapshot?.updatedAt),
        style = TextStyle(color = when {
            refreshing -> AccentText
            feedbackText != null && refreshStatus.phase == WidgetRefreshPhase.Failure -> WarningText
            feedbackText != null && refreshStatus.phase == WidgetRefreshPhase.Success -> RefreshSuccessText
            feedbackText == "刷新超时，重试" -> WarningText
            darkStyle -> GroupedSecondaryText
            else -> SecondaryText
        }, fontSize = 10.sp),
        maxLines = 1,
    )
    dataStatus?.warning?.let { warning ->
        Text(warning, style = TextStyle(color = WarningText, fontSize = 9.sp), maxLines = 2)
    }
    dataStatus?.clockCorrection?.let { correction ->
        Text(correction, style = TextStyle(color = if (darkStyle) GroupedSecondaryText else SecondaryText,
            fontSize = 9.sp), maxLines = 1)
    }
    }
}

@Composable
internal fun SignalWidgetHeader(snapshot: GuailiSnapshot?, appWidgetId: Int, refreshStatus: WidgetRefreshStatus,
    dataStatus: WidgetDataStatus?, signalCount: Int, version: String? = null,
    refreshLabelOverride: String? = null, statusLabelOverride: String? = null,
    warningOverride: Boolean? = null) {
    val width = LocalSize.current.width
    val compact = compactSignalWidgetHeader(width.value, LocalContext.current.resources.configuration.fontScale)
    val narrow = width < 250.dp
    val wide = width >= 320.dp
    val feedback = refreshFeedbackText(refreshStatus, snapshotUpdatedAt = snapshot?.updatedAt)
    val refreshing = feedback == "刷新中…"
    val fullTitle = if (version == null) "乖离信号 · ${signalCount}条" else "乖离信号 $version · ${signalCount}条"
    val title = if (compact) {
        if (version == null) "${signalCount}条" else "$version·$signalCount"
    } else if (version == null) {
        when { wide -> fullTitle; narrow -> "信号·${signalCount}条"; else -> "信号 · ${signalCount}条" }
    } else {
        when { wide -> fullTitle; narrow -> "信号$version·${signalCount}条"; else -> "信号 $version · ${signalCount}条" }
    }
    val fullRefreshLabel = refreshLabelOverride ?: singleLineRefreshLabel(refreshStatus, snapshot, dataStatus, wide, narrow)
    val refreshLabel = if (compact && fullRefreshLabel == "刷新中") "刷新" else fullRefreshLabel
    val statusLabel = statusLabelOverride ?: singleLineStatusLabel(dataStatus, narrow || compact)
    val fullStatusLabel = statusLabelOverride ?: widgetStatusLabel(dataStatus)
    val statusPadding = if (compact) 1.dp else if (narrow) 3.dp else 4.dp
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = TextStyle(color = PrimaryText, fontSize = if (wide) SignalCardStyle.HeaderFontSp.sp else if (narrow) 11.sp else 12.sp,
                fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight().padding(end = if (compact) 2.dp else 4.dp)
                .semantics { contentDescription = fullTitle }
                .clickable(actionStartActivity(mainActivityIntent())), maxLines = 1,
        )
        Text(
            text = refreshLabel,
            style = TextStyle(color = if (feedback?.contains("失败") == true || feedback?.contains("超时") == true)
                WarningText else if (feedback == "刷新成功") RefreshSuccessText else SecondaryText, fontSize = 9.sp),
            modifier = GlanceModifier.padding(horizontal = statusPadding, vertical = 3.dp)
                .semantics { contentDescription = feedback ?: "获取时间 $fullRefreshLabel" }
                .clickable(actionStartActivity(widgetStatusIntent(appWidgetId))), maxLines = 1,
        )
        Text(
            text = statusLabel,
            style = TextStyle(color = if (warningOverride ?: (dataStatus?.incomplete == true)) WarningText else AccentText, fontSize = 9.sp),
            modifier = GlanceModifier.padding(horizontal = statusPadding, vertical = 3.dp)
                .semantics { contentDescription = fullStatusLabel }
                .clickable(actionStartActivity(widgetStatusIntent(appWidgetId))), maxLines = 1,
        )
        Text("编辑", style = TextStyle(color = SecondaryText, fontSize = if (narrow) 9.sp else 10.sp),
            modifier = GlanceModifier.padding(horizontal = 2.dp, vertical = 3.dp)
                .clickable(actionStartActivity(widgetConfigurationIntent(appWidgetId))), maxLines = 1)
        Text(if (refreshing) "⟳" else if (narrow || compact) "↻" else "↻刷新",
            style = TextStyle(color = SecondaryText, fontSize = 11.sp),
            modifier = GlanceModifier.padding(horizontal = if (compact) 2.dp else 4.dp, vertical = 3.dp)
                .semantics { contentDescription = if (refreshing) "正在刷新" else "刷新信号" }
                .then(if (refreshing) GlanceModifier else GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>())),
            maxLines = 1)
    }
}

internal fun signalWidgetOuterPadding(widthDp: Float, fontScale: Float): Int =
    if (widthDp < 220f && fontScale > 1.2f) 2 else 10

internal fun compactSignalWidgetHeader(widthDp: Float, fontScale: Float): Boolean =
    fontScale > 1.2f && (widthDp - 2 * signalWidgetOuterPadding(widthDp, fontScale)) / fontScale < 240f

private fun widgetStatusIntent(appWidgetId: Int): Intent =
    Intent().setClassName("com.gouge.guaili", GuailiWidgetStatusActivity::class.java.name)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

internal fun refreshFeedbackText(
    status: WidgetRefreshStatus,
    nowMillis: Long = System.currentTimeMillis(),
    snapshotUpdatedAt: Long? = null,
): String? {
    val age = nowMillis - status.changedAt
    if (status.phase != WidgetRefreshPhase.Refreshing && (snapshotUpdatedAt ?: 0L) > status.changedAt) return null
    if (age < 0) return null
    return when (status.phase) {
    WidgetRefreshPhase.Idle -> null
    WidgetRefreshPhase.Refreshing -> if (age in 0..60_000L) "刷新中…" else "刷新超时，重试"
    WidgetRefreshPhase.Success -> "刷新成功"
    WidgetRefreshPhase.Failure -> if (status.message?.contains("保存") == true) "保存失败，请重试" else "刷新失败，请重试"
    }
}

internal fun widgetRefreshSummary(
    status: WidgetRefreshStatus,
    snapshotUpdatedAt: Long?,
    latestClosedAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    fetchedDisplayAt: Long? = snapshotUpdatedAt,
): String {
    val feedback = refreshFeedbackText(status, nowMillis, snapshotUpdatedAt)
    val fetched = fetchedDisplayAt?.let {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }
    return when {
        feedback == "刷新成功" -> "$feedback ${fetched ?: ""} · 收线 ${latestClosedAt?.let(::formatTime) ?: "未知"}"
        feedback != null -> feedback + (fetched?.let { " · 上次获取 $it" } ?: "")
        fetched != null -> "获取 $fetched · 收线 ${latestClosedAt?.let(::formatTime) ?: "未知"}"
        else -> "暂无数据，点击刷新"
    }
}

@Composable
private fun SingleSymbolPeriodList(
    symbol: String,
    intervals: List<String>,
    snapshot: GuailiSnapshot,
    dimensions: GroupedLayoutDimensions,
    columns: Int,
    modifier: GlanceModifier,
) {
    val rows = singleSymbolRows(intervals, columns)
    LazyColumn(modifier = modifier) {
        items(rows) { rowIntervals ->
            SingleSymbolGridRow(
                symbol = symbol,
                intervals = rowIntervals,
                cells = snapshot.table.cells[symbol].orEmpty(),
                dimensions = dimensions,
                columns = columns,
            )
        }
    }
}

@Composable
private fun SingleSymbolGridRow(
    symbol: String,
    intervals: List<String>,
    cells: Map<String, GuailiCell>,
    dimensions: GroupedLayoutDimensions,
    columns: Int,
) {
    val spacing = if (columns >= 8) 0.dp else dimensions.columnSpacing
    val cellsPerSlot = singleSymbolCellsPerSlot(columns)
    val slotCount = singleSymbolSlotCount(columns)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = dimensions.rowPadding),
        verticalAlignment = Alignment.Top,
    ) {
        repeat(slotCount) { slotIndex ->
            if (slotIndex > 0) Spacer(modifier = GlanceModifier.width(spacing))
            if (cellsPerSlot == 1) {
                SingleSymbolGridCellOrSpacer(
                    symbol = symbol,
                    interval = intervals.getOrNull(slotIndex),
                    cells = cells,
                    dimensions = dimensions,
                    modifier = GlanceModifier.defaultWeight(),
                )
            } else {
                Row(modifier = GlanceModifier.defaultWeight()) {
                    repeat(cellsPerSlot) { innerIndex ->
                        if (innerIndex > 0) Spacer(modifier = GlanceModifier.width(spacing))
                        val intervalIndex = slotIndex * cellsPerSlot + innerIndex
                        SingleSymbolGridCellOrSpacer(
                            symbol = symbol,
                            interval = intervals.getOrNull(intervalIndex),
                            cells = cells,
                            dimensions = dimensions,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleSymbolGridCellOrSpacer(
    symbol: String,
    interval: String?,
    cells: Map<String, GuailiCell>,
    dimensions: GroupedLayoutDimensions,
    modifier: GlanceModifier,
) {
    if (interval == null) {
        Spacer(modifier = modifier)
    } else {
        SingleSymbolGridCell(
            symbol = symbol,
            interval = interval,
            cell = cells[interval],
            dimensions = dimensions,
            modifier = modifier,
        )
    }
}

@Composable
private fun SingleSymbolGridCell(
    symbol: String,
    interval: String,
    cell: GuailiCell?,
    dimensions: GroupedLayoutDimensions,
    modifier: GlanceModifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(dimensions.periodHeaderHeight)
                .background(GroupedHeaderBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatInterval(interval),
                style = TextStyle(
                    color = groupedTrendTextColor(cell),
                    fontSize = dimensions.periodFontSize,
                    fontWeight = if (cell?.longTrend == true || cell?.shortTrend == true) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Medium
                    },
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(dimensions.table.cellHeight)
                .background(groupedCellBackground(cell))
                .clickable(actionStartActivity(klineIntent(symbol, interval))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = widgetCellValue(cell),
                style = TextStyle(
                    color = if (cell?.rankFilter == false) GroupedFilteredText else GroupedValueText,
                    fontSize = dimensions.table.valueFontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

internal fun singleSymbolRows(intervals: List<String>, columns: Int): List<List<String>> =
    intervals.chunked(columns.coerceAtLeast(1))

internal fun singleSymbolCellsPerSlot(columns: Int): Int =
    if (columns > MaxWeightedCellsPerRow) 2 else 1

internal fun singleSymbolSlotCount(columns: Int): Int {
    val safeColumns = columns.coerceAtLeast(1)
    val cellsPerSlot = singleSymbolCellsPerSlot(safeColumns)
    return (safeColumns + cellsPerSlot - 1) / cellsPerSlot
}

internal fun singleSymbolTitle(symbol: String): String {
    val quote = listOf("USDT", "USDC", "USD", "BTC", "ETH").firstOrNull { suffix ->
        symbol.length > suffix.length && symbol.endsWith(suffix, ignoreCase = true)
    }
    return quote?.let { "${symbol.dropLast(it.length)} / $it" } ?: symbol
}

private fun groupedCellBackground(cell: GuailiCell?): ColorProvider =
    ColorProvider(Color(guailiBackgroundArgb(cell?.value)))

private fun groupedTrendTextColor(cell: GuailiCell?): ColorProvider = when {
    cell?.longTrend == true && cell.shortTrend == true -> GroupedConflictTrendText
    cell?.longTrend == true -> GroupedLongTrendText
    cell?.shortTrend == true -> GroupedShortTrendText
    else -> GroupedNeutralTrendText
}

@Composable
private fun NoSignalContent(monitoredSymbols: Int, hasEnabledSignalKinds: Boolean, dataStatus: WidgetDataStatus?) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                !hasEnabledSignalKinds -> "未启用信号类型"
                dataStatus?.timeUncertain == true -> "时间暂不可确认"
                (dataStatus?.future ?: 0) > 0 -> "行情时间异常"
                dataStatus?.incomplete == true -> "数据不足，无法完整判断"
                else -> "暂无符合条件的信号"
            },
            style = TextStyle(
                color = PrimaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = if (hasEnabledSignalKinds) {
                "${monitoredSymbols}个品种快照 · ${dataStatus?.description.orEmpty()}"
            } else {
                "点击编辑开启信号"
            },
            style = TextStyle(color = SecondaryText, fontSize = 10.sp),
        )
    }
}

@Composable
internal fun SignalRow(signal: GuailiSignal, maLabel: String) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val size = LocalSize.current
    val padding = if (size.width < 220.dp && context.resources.configuration.fontScale > 1.2f) 2 else SignalCardStyle.HorizontalPaddingDp
    val conflict = signal.kind == GuailiSignalKind.Conflict
    val symbol = displaySymbol(signal.symbol)
    val ranges = remember(signal) { com.gouge.guaili.signals.signalCompactRanges(signal) }
    val metas = remember(signal) { if (conflict) signal.runs.mapIndexed { index, run -> signalRunMetaLabel(run, index == 0) }
        else listOf(com.gouge.guaili.signals.signalCompactLabel(signal)) }
    val plan = remember(signal, size.width, metrics.density, context.resources.configuration.fontScale) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun width(text: String, sp: Int, bold: Boolean = false): Float {
            paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), metrics)
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            return paint.measureText(text)
        }
        val outerPadding = signalWidgetOuterPadding(size.width.value, context.resources.configuration.fontScale)
        com.gouge.guaili.signals.signalCompactLayout((size.width.value - 2 * outerPadding - 2 * padding) * metrics.density,
            width(symbol, SignalCardStyle.TitleFontSp), ranges.map { width(it, SignalCardStyle.RangeFontSp, true) },
            metas.map { width(it, SignalCardStyle.DescriptionFontSp) }, 8 * metrics.density)
    }
    val symbolStyle = TextStyle(color = SecondaryText, fontSize = SignalCardStyle.TitleFontSp.sp)
    val rangeStyle = TextStyle(color = signalTitleColor(signal), fontSize = SignalCardStyle.RangeFontSp.sp, fontWeight = FontWeight.Bold)
    val metaStyle = TextStyle(color = SecondaryText, fontSize = SignalCardStyle.DescriptionFontSp.sp)
    val description = remember(signal, maLabel) { "${signal.symbol} · ${signalTitle(signal)} · ${signalSummary(signal, maLabel)} · ${signalCompactTrend(signal, maLabel)} · ${signalPhaseLabel(signal)}" }
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Column(modifier = GlanceModifier.fillMaxWidth().background(SignalBackground)
            .semantics { contentDescription = description }
            .padding(horizontal = padding.dp, vertical = SignalCardStyle.VerticalPaddingDp.dp)
            .clickable(actionStartActivity(klineIntent(signal.symbol, signal.anchorInterval)))) {
            if (conflict) {
                ranges.forEachIndexed { index, range ->
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val symbolWidth = (plan.symbolWidth / metrics.density).dp
                        if (index == 0) Text(symbol, style = symbolStyle, modifier = GlanceModifier.width(symbolWidth), maxLines = 1)
                        else Spacer(GlanceModifier.width(symbolWidth))
                        if (plan.symbolWidth > 0) Spacer(GlanceModifier.width(8.dp))
                        Text(range, style = rangeStyle, maxLines = 1)
                        if (plan.showMeta) {
                            Spacer(GlanceModifier.width(8.dp))
                            Text(metas[index], style = metaStyle, maxLines = 1)
                        }
                    }
                }
            } else if (plan.inline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(symbol, style = symbolStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(ranges.single(), style = rangeStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(metas.single(), style = metaStyle, maxLines = 1)
                }
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(symbol, style = symbolStyle, modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                    Text(metas.single(), style = metaStyle, maxLines = 1)
                }
                Text(ranges.single(), style = rangeStyle, maxLines = 1)
            }
        }
        Spacer(modifier = GlanceModifier.height(SignalCardStyle.CardSpacingDp.dp))
    }
}

private fun signalTitleColor(signal: GuailiSignal): ColorProvider =
    dayNightColor(SignalCardStyle.Light.titleColor(signal), SignalCardStyle.Dark.titleColor(signal))

@Composable
private fun EmptyWidgetContent() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "点击刷新获取最新指标",
            style = TextStyle(color = SecondaryText, fontSize = 12.sp),
        )
    }
}

@Composable
private fun MatrixHeader(intervals: List<String>, cellWidth: androidx.compose.ui.unit.Dp) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Spacer(modifier = GlanceModifier.width(SymbolWidth))
        intervals.forEach { interval ->
            Text(
                text = displayInterval(interval),
                style = TextStyle(
                    color = SecondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier.width(cellWidth).padding(vertical = 2.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MatrixRow(
    symbol: String,
    intervals: List<String>,
    cellWidth: androidx.compose.ui.unit.Dp,
    snapshot: GuailiSnapshot,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displaySymbol(symbol),
            style = TextStyle(
                color = SecondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.width(SymbolWidth).padding(end = 4.dp),
            maxLines = 1,
        )
        intervals.forEach { interval ->
            MatrixCell(
                cell = snapshot.table.cells[symbol]?.get(interval),
                symbol = symbol,
                interval = interval,
                cellWidth = cellWidth,
            )
        }
    }
}

@Composable
private fun MatrixCell(
    cell: GuailiCell?,
    symbol: String,
    interval: String,
    cellWidth: androidx.compose.ui.unit.Dp,
) {
    Text(
        text = widgetCellValue(cell),
        style = TextStyle(
            color = cellTextColor(cell),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        modifier = GlanceModifier
            .width(cellWidth)
            .padding(1.dp)
            .background(cellBackground(cell))
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .clickable(actionStartActivity(klineIntent(symbol, interval))),
        maxLines = 1,
    )
}

private fun klineIntent(symbol: String, interval: String): Intent = Intent(
    Intent.ACTION_VIEW,
    Uri.parse("guaili://kline/$symbol/$interval"),
).apply {
    setClassName("com.gouge.guaili", "com.gouge.guaili.MainActivity")
    putExtra(MainActivity.ExtraWidgetSymbol, symbol)
    putExtra(MainActivity.ExtraWidgetInterval, interval)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

private fun mainActivityIntent(): Intent = Intent().apply {
    setClassName("com.gouge.guaili", "com.gouge.guaili.MainActivity")
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

private fun widgetConfigurationIntent(appWidgetId: Int): Intent = Intent(
    AppWidgetManager.ACTION_APPWIDGET_CONFIGURE,
    Uri.parse("guaili://widget/configure/$appWidgetId"),
).apply {
    setClassName(
        "com.gouge.guaili",
        "com.gouge.guaili.widget.GuailiWidgetConfigurationActivity",
    )
    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

private fun cellBackground(cell: GuailiCell?): ColorProvider = when {
    cell?.rankFilter == false -> dayNightColor(0xFFE5E7EB, 0xFF374151)
    (cell?.value ?: 0) > 0 -> dayNightColor(0xFFDDF4E4, 0xFF144D26)
    (cell?.value ?: 0) < 0 -> dayNightColor(0xFFFBE1E8, 0xFF6E1537)
    else -> dayNightColor(0xFFF1F3F5, 0xFF30343B)
}

private fun cellTextColor(cell: GuailiCell?): ColorProvider = when {
    cell?.rankFilter == false -> dayNightColor(0xFF6B7280, 0xFFD1D5DB)
    (cell?.value ?: 0) > 0 -> dayNightColor(0xFF06722D, 0xFFB7F7C8)
    (cell?.value ?: 0) < 0 -> dayNightColor(0xFFB0003A, 0xFFFFC2D4)
    else -> PrimaryText
}

private fun displaySymbol(symbol: String): String = symbol
    .removeSuffix("USDT")
    .ifEmpty { symbol }

private fun displayInterval(interval: String): String = when {
    interval.all(Char::isDigit) -> "${interval}m"
    else -> interval
}

private fun formatTime(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val value = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val pattern = if (value.toLocalDate() == java.time.LocalDate.now(zone)) "HH:mm" else "MM-dd HH:mm"
    return DateTimeFormatter.ofPattern(pattern).format(value)
}

private const val MaxWeightedCellsPerRow = 5
private val SymbolWidth = 54.dp
private fun dayNightColor(day: Long, night: Long): ColorProvider =
    DayNightColorProvider(Color(day), Color(night))

private val WidgetBackground = dayNightColor(SignalCardStyle.Light.background, SignalCardStyle.Dark.background)
private val GroupedBackground = ColorProvider(Color(0xFF11161C))
private val GroupedHeaderBackground = ColorProvider(Color(0xFF202832))
private val GroupedPrimaryText = ColorProvider(Color(0xFFE5E7EB))
private val GroupedSecondaryText = ColorProvider(Color(0xFF9CA3AF))
private val GroupedValueText = ColorProvider(Color.White)
private val GroupedFilteredText = ColorProvider(Color(0xFF9CA3AF))
private val GroupedLongTrendText = ColorProvider(Color(0xFF69F0AE))
private val GroupedShortTrendText = ColorProvider(Color(0xFFFF8A80))
private val GroupedConflictTrendText = ColorProvider(Color(0xFFFFD740))
private val GroupedNeutralTrendText = ColorProvider(Color(0xFFD1D5DB))
private val SignalBackground = dayNightColor(SignalCardStyle.Light.card, SignalCardStyle.Dark.card)
private val SignalPositiveText = dayNightColor(SignalCardStyle.Light.positive, SignalCardStyle.Dark.positive)
private val SignalNegativeText = dayNightColor(SignalCardStyle.Light.negative, SignalCardStyle.Dark.negative)
private val SignalBadgeBackground = dayNightColor(SignalCardStyle.Light.badge, SignalCardStyle.Dark.badge)
private val PrimaryText = dayNightColor(SignalCardStyle.Light.primary, SignalCardStyle.Dark.primary)
private val SecondaryText = dayNightColor(SignalCardStyle.Light.secondary, SignalCardStyle.Dark.secondary)
private val AccentText = dayNightColor(SignalCardStyle.Light.accent, SignalCardStyle.Dark.accent)
private val WarningText = dayNightColor(SignalCardStyle.Light.warning, SignalCardStyle.Dark.warning)
private val RefreshSuccessText = dayNightColor(0xFF06722D, 0xFF69F0AE)
private val ReminderLongText = dayNightColor(0xFF06722D, 0xFF69F0AE)
private val ReminderShortText = dayNightColor(0xFFB0003A, 0xFFFF8A80)
private val ReminderDueBackground = dayNightColor(0xFFFFF1D6, 0xFF4A3513)
