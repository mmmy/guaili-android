package com.gouge.guaili.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Publish the same monotonic deadline that controls the actual next request. */
internal suspend fun runKlineRefreshSchedule(
    seconds: Int,
    elapsedTime: () -> Long,
    scheduled: (Long) -> Unit,
    refresh: () -> Unit,
) {
    val period = seconds.coerceAtLeast(1).toLong() * 1_000L
    while (currentCoroutineContext().isActive) {
        scheduled(elapsedTime() + period)
        delay(period)
        refresh()
    }
}

/** Only this small canvas updates; the chart is not recomposed by the countdown. */
@Composable
internal fun KlineRefreshIndicator(seconds: Int, deadline: Long, foreground: Boolean, modifier: Modifier = Modifier) {
    var now by remember(deadline, foreground) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline, foreground) {
        if (!foreground || deadline == 0L) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            val remaining = deadline - now
            if (remaining <= 0L) break
            delay(minOf(200L, remaining))
        }
    }
    val period = seconds.coerceAtLeast(1).toLong() * 1_000L
    val fraction = if (foreground && deadline != 0L) ((deadline - now).toFloat() / period).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .2f)
    val arc = MaterialTheme.colorScheme.primary.copy(alpha = .85f)
    Canvas(modifier.testTag("kline-refresh-indicator").semantics {
        contentDescription = if (foreground) "自动刷新倒计时" else "自动刷新已暂停"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val width = 1.4.dp.toPx()
        val stroke = Stroke(width = width, cap = StrokeCap.Round)
        drawCircle(track, radius = (size.minDimension - width) / 2f, style = stroke)
        if (fraction > 0f) drawArc(arc, startAngle = -90f, sweepAngle = 360f * fraction,
            useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(width / 2f, width / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width), style = stroke)
    }
}
