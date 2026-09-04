package com.gouge.guaili.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
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
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.isGuailiSnapshotStale
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiBackgroundArgb
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiSignalDetector
import com.gouge.guaili.domain.GuailiSignalDirection
import com.gouge.guaili.domain.GuailiSignalKind
import com.gouge.guaili.domain.GuailiSignalRun
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
        val settings = SettingsStore(context).settings.first()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).read(appWidgetId, settings)
        val snapshot = GuailiSnapshotStore(context).read()

        provideContent {
            val refreshStatus = currentState<Preferences>().widgetRefreshStatus()
            GuailiWidgetContent(
                snapshot = snapshot,
                config = config,
                settings = settings,
                refreshStatus = refreshStatus,
                appWidgetId = appWidgetId,
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
) {
    if (config.mode == WidgetMode.DecisionReminders) {
        DecisionReminderWidgetContent(
            reminders = config.reminders,
            appWidgetId = appWidgetId,
        )
        return
    }
    val size = LocalSize.current
    val signals = if (snapshot == null || config.mode != WidgetMode.Signals) {
        emptyList()
    } else {
        GuailiSignalDetector.detect(
            table = snapshot.table,
            selectedSymbols = config.symbols,
            enabledKinds = config.enabledSignalKinds,
        )
    }

    val symbolCount = when {
        size.height < 130.dp -> 1
        size.height < 170.dp -> 2
        else -> 3
    }
    val symbols = config.symbols.take(symbolCount)
    val intervalCount = when {
        size.width < 220.dp -> 2
        size.width < 300.dp -> 3
        else -> WidgetConfigStore.MaxIntervals
    }
    val intervals = config.intervals.take(intervalCount)
    val cellWidth = if (intervals.isEmpty()) {
        40.dp
    } else {
        (size.width - 20.dp - SymbolWidth) / intervals.size
    }

    val useGroupedStyle = config.mode == WidgetMode.SingleSymbol
    val contentPadding = if (useGroupedStyle) 0.dp else 10.dp
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
            .padding(contentPadding),
    ) {
        WidgetHeader(
            snapshot = snapshot,
            appWidgetId = appWidgetId,
            title = when (config.mode) {
                WidgetMode.Signals -> "乖离信号"
                WidgetMode.Matrix -> "乖离矩阵"
                WidgetMode.SingleSymbol -> singleSymbolTitle(config.symbols.firstOrNull().orEmpty())
                WidgetMode.DecisionReminders -> "决策提醒"
            },
            darkStyle = useGroupedStyle,
            darkHeaderHeight = singleSymbolDimensions.symbolHeaderHeight,
            refreshStatus = refreshStatus,
        )
        Spacer(modifier = GlanceModifier.height(if (useGroupedStyle) 2.dp else 6.dp))
        when {
            snapshot == null || config.symbols.isEmpty() -> EmptyWidgetContent()
            config.mode == WidgetMode.Signals -> {
                val signalCount = when {
                    size.height < 130.dp -> 1
                    size.height < 170.dp -> 2
                    else -> 3
                }
                if (signals.isEmpty()) {
                    NoSignalContent(
                        monitoredSymbols = config.symbols.size,
                        hasEnabledSignalKinds = config.enabledSignalKinds.isNotEmpty(),
                    )
                } else {
                    signals.take(signalCount).forEach { signal ->
                        SignalRow(signal)
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
                MatrixHeader(intervals, cellWidth)
                symbols.forEach { symbol ->
                    MatrixRow(
                        symbol = symbol,
                        intervals = intervals,
                        cellWidth = cellWidth,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecisionReminderWidgetContent(
    reminders: List<DecisionReminder>,
    appWidgetId: Int,
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
        Spacer(modifier = GlanceModifier.height(6.dp))
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
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (expired) ReminderDueBackground else SignalBackground)
            .padding(horizontal = 7.dp, vertical = 7.dp)
            .clickable(actionStartActivity(klineIntent(reminder.symbol, reminder.interval))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displaySymbol(reminder.symbol),
            style = TextStyle(
                color = PrimaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(54.dp),
            maxLines = 1,
        )
        Text(
            text = displayInterval(reminder.interval),
            style = TextStyle(color = SecondaryText, fontSize = 10.sp),
            modifier = GlanceModifier.width(40.dp),
            maxLines = 1,
        )
        Text(
            text = "${reminder.direction.glyph}${reminder.direction.label}",
            style = TextStyle(
                color = if (reminder.direction == DecisionDirection.Long) {
                    ReminderLongText
                } else {
                    ReminderShortText
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(48.dp),
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
                textAlign = TextAlign.End,
            ),
            modifier = GlanceModifier.defaultWeight(),
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
) {
    val stale = snapshot != null && isGuailiSnapshotStale(snapshot.updatedAt)
    val feedbackText = refreshFeedbackText(refreshStatus)
    val headerModifier = if (darkStyle && darkHeaderHeight != null) {
        GlanceModifier
            .fillMaxWidth()
            .height(darkHeaderHeight)
            .background(GroupedHeaderBackground)
            .padding(horizontal = 10.dp)
    } else {
        GlanceModifier.fillMaxWidth()
    }
    Row(
        modifier = headerModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = if (darkStyle) GroupedPrimaryText else PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(mainActivityIntent())),
            maxLines = 1,
        )
        Text(
            text = feedbackText ?: when {
                snapshot == null -> "暂无数据"
                stale -> "已过期 ${formatTime(snapshot.updatedAt)}"
                else -> formatTime(snapshot.updatedAt)
            },
            style = TextStyle(
                color = when {
                    refreshStatus.phase == WidgetRefreshPhase.Refreshing -> AccentText
                    refreshStatus.phase == WidgetRefreshPhase.Success -> RefreshSuccessText
                    refreshStatus.phase == WidgetRefreshPhase.Failure -> WarningText
                    stale -> WarningText
                    darkStyle -> GroupedSecondaryText
                    else -> SecondaryText
                },
                fontSize = 10.sp,
            ),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
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
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = if (refreshStatus.phase == WidgetRefreshPhase.Refreshing) "…" else "↻",
            style = TextStyle(
                color = AccentText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .padding(horizontal = 4.dp)
                .clickable(actionRunCallback<RefreshWidgetAction>()),
        )
    }
}

internal fun refreshFeedbackText(status: WidgetRefreshStatus): String? = when (status.phase) {
    WidgetRefreshPhase.Idle -> null
    WidgetRefreshPhase.Refreshing -> "刷新中…"
    WidgetRefreshPhase.Success -> "已刷新 ${formatTime(status.changedAt)}"
    WidgetRefreshPhase.Failure -> "刷新失败"
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
                text = cell?.value?.toString() ?: "-",
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
private fun NoSignalContent(monitoredSymbols: Int, hasEnabledSignalKinds: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hasEnabledSignalKinds) "暂无高优先级信号" else "未启用信号类型",
            style = TextStyle(
                color = PrimaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = if (hasEnabledSignalKinds) {
                "正在监控 $monitoredSymbols 个品种"
            } else {
                "点击编辑开启信号"
            },
            style = TextStyle(color = SecondaryText, fontSize = 10.sp),
        )
    }
}

@Composable
private fun SignalRow(signal: GuailiSignal) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(SignalBackground)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .clickable(actionStartActivity(klineIntent(signal.symbol, signal.anchorInterval))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displaySymbol(signal.symbol),
            style = TextStyle(
                color = PrimaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(50.dp),
            maxLines = 1,
        )
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = signalTitle(signal),
                style = TextStyle(
                    color = signalTitleColor(signal),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = signalSummary(signal),
                style = TextStyle(color = SecondaryText, fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }
}

private fun signalTitle(signal: GuailiSignal): String = when (signal.kind) {
    GuailiSignalKind.Conflict -> "级别冲突"
    GuailiSignalKind.Compression -> "均线压缩"
    GuailiSignalKind.Extreme -> when (signal.primaryRun.direction) {
        GuailiSignalDirection.Positive -> if (signal.isStrong) "强回调风险 ↓" else "回调风险 ↓"
        GuailiSignalDirection.Negative -> if (signal.isStrong) "强反弹风险 ↑" else "反弹风险 ↑"
        GuailiSignalDirection.Neutral -> "极端风险"
    }
}

private fun signalSummary(signal: GuailiSignal): String = when (signal.kind) {
    GuailiSignalKind.Conflict -> "观察 · " + signal.runs.joinToString(" · ") { run ->
        "${runRange(run)}${directionShortName(run.direction)}"
    }
    GuailiSignalKind.Extreme ->
        signalEvidencePrefix(signal) +
            "${runRange(signal.primaryRun)} · ${signal.primaryRun.levelCount}级${directionShortName(signal.primaryRun.direction)}"
    GuailiSignalKind.Compression ->
        signalEvidencePrefix(signal) +
            "${runRange(signal.primaryRun)} · ${signal.primaryRun.levelCount}级接近EMA20"
}

private fun signalEvidencePrefix(signal: GuailiSignal): String =
    if (signal.isEvidenceBacked) "" else "观察 · "

private fun runRange(run: GuailiSignalRun): String =
    "${displayInterval(run.startInterval)}–${displayInterval(run.endInterval)}"

private fun directionShortName(direction: GuailiSignalDirection): String = when (direction) {
    GuailiSignalDirection.Positive -> "正极端"
    GuailiSignalDirection.Negative -> "负极端"
    GuailiSignalDirection.Neutral -> "近零"
}

private fun signalTitleColor(signal: GuailiSignal): ColorProvider = when (signal.kind) {
    GuailiSignalKind.Compression -> AccentText
    GuailiSignalKind.Extreme,
    GuailiSignalKind.Conflict,
    -> WarningText
}

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
                color = PrimaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
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
    val value = cell?.value
    val suffix = if (cell?.isClosed == false) "·" else ""
    Text(
        text = value?.let { "$it$suffix" } ?: "--",
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

private fun formatTime(epochMillis: Long): String = TimeFormatter.format(Instant.ofEpochMilli(epochMillis))

private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private const val MaxWeightedCellsPerRow = 5
private val SymbolWidth = 54.dp
private fun dayNightColor(day: Long, night: Long): ColorProvider =
    DayNightColorProvider(Color(day), Color(night))

private val WidgetBackground = dayNightColor(0xFFF9FAFB, 0xFF17191D)
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
private val SignalBackground = dayNightColor(0xFFF1F3F5, 0xFF24272D)
private val PrimaryText = dayNightColor(0xFF17191D, 0xFFF3F4F6)
private val SecondaryText = dayNightColor(0xFF62666D, 0xFFB7BBC3)
private val AccentText = dayNightColor(0xFF315EFB, 0xFF9DB2FF)
private val WarningText = dayNightColor(0xFFB45309, 0xFFFBBF24)
private val RefreshSuccessText = dayNightColor(0xFF06722D, 0xFF69F0AE)
private val ReminderLongText = dayNightColor(0xFF06722D, 0xFF69F0AE)
private val ReminderShortText = dayNightColor(0xFFB0003A, 0xFFFF8A80)
private val ReminderDueBackground = dayNightColor(0xFFFFF1D6, 0xFF4A3513)
