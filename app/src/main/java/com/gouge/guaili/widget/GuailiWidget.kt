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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
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
import com.gouge.guaili.MainActivity
import com.gouge.guaili.data.GuailiSnapshot
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.data.isGuailiSnapshotStale
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

class GuailiWidget : GlanceAppWidget() {
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
            GuailiWidgetContent(
                snapshot = snapshot,
                config = config,
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
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        GuailiWidgetScheduler.cancelPeriodic(context)
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        GuailiWidgetScheduler.refreshNow(context)
    }
}

@Composable
private fun GuailiWidgetContent(
    snapshot: GuailiSnapshot?,
    config: WidgetConfig,
    appWidgetId: Int,
) {
    val size = LocalSize.current
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

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .padding(10.dp),
    ) {
        WidgetHeader(snapshot, appWidgetId)
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (snapshot == null || symbols.isEmpty() || intervals.isEmpty()) {
            EmptyWidgetContent()
        } else {
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

@Composable
private fun WidgetHeader(snapshot: GuailiSnapshot?, appWidgetId: Int) {
    val stale = snapshot != null && isGuailiSnapshotStale(snapshot.updatedAt)
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "乖离速览",
            style = TextStyle(
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(mainActivityIntent())),
        )
        Text(
            text = when {
                snapshot == null -> "暂无数据"
                stale -> "已过期 ${formatTime(snapshot.updatedAt)}"
                else -> formatTime(snapshot.updatedAt)
            },
            style = TextStyle(
                color = if (stale) WarningText else SecondaryText,
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
            text = "↻",
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
private val SymbolWidth = 54.dp
private fun dayNightColor(day: Long, night: Long): ColorProvider =
    DayNightColorProvider(Color(day), Color(night))

private val WidgetBackground = dayNightColor(0xFFF9FAFB, 0xFF17191D)
private val PrimaryText = dayNightColor(0xFF17191D, 0xFFF3F4F6)
private val SecondaryText = dayNightColor(0xFF62666D, 0xFFB7BBC3)
private val AccentText = dayNightColor(0xFF315EFB, 0xFF9DB2FF)
private val WarningText = dayNightColor(0xFFB45309, 0xFFFBBF24)
