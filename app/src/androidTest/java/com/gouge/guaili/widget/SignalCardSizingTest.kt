package com.gouge.guaili.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gouge.guaili.domain.*
import com.gouge.guaili.data.GuailiSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalCardSizingTest {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun signalHeaderKeepsOneRowForNormalWarningAndRefreshStates() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        val snapshot = GuailiSnapshot(GuailiTable(emptyList(), emptyList(), emptyMap()), now)
        val status = WidgetDataStatus(28, 0, 0, 0, now, false, 28, fetchedAtMillis = now)
        val cases = listOf(
            status to WidgetRefreshStatus(),
            status.copy(noDataSymbols = (1..10).map { "S$it" }) to WidgetRefreshStatus(),
            status.copy(timeUncertain = true) to WidgetRefreshStatus(),
            status.copy(future = 1) to WidgetRefreshStatus(),
            status.copy(timeUncertain = true) to WidgetRefreshStatus(WidgetRefreshPhase.Refreshing, now),
            status to WidgetRefreshStatus(WidgetRefreshPhase.Failure, now),
        )
        for (width in listOf(180, 250, 320)) {
            for ((index, case) in cases.withIndex()) {
                val remote = GlanceRemoteViews().compose(context, DpSize(width.dp, 110.dp)) {
                    androidx.glance.layout.Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                        SignalWidgetHeader(snapshot, 1, case.second, case.first, 12)
                    }
                }.remoteViews
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val parent = FrameLayout(context)
                    parent.addView(remote.apply(context, parent))
                    val pixels = (width * context.resources.displayMetrics.density).toInt()
                    parent.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    parent.layout(0, 0, pixels, parent.measuredHeight)
                    assertTrue("$width dp header must keep one-row height",
                        parent.measuredHeight <= 32 * context.resources.displayMetrics.density)
                    val fields = mutableListOf<TextView>()
                    fun visit(view: View) {
                        if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) fields += view
                        if (view is ViewGroup) for (child in 0 until view.childCount) visit(view.getChildAt(child))
                    }
                    visit(parent)
                    assertTrue(fields.any { it.text.toString() == "编辑" })
                    assertEquals(5, fields.size)
                    fields.forEach { text ->
                        val layout = requireNotNull(text.layout)
                        assertEquals("$width dp header: ${text.text}", 1, layout.lineCount)
                        assertEquals("$width dp header: truncated ${text.text}", 0, layout.getEllipsisCount(0))
                    }
                    if (index == 0) {
                        val bitmap = Bitmap.createBitmap(pixels, parent.measuredHeight, Bitmap.Config.ARGB_8888)
                        parent.draw(Canvas(bitmap))
                        File(context.getExternalFilesDir(null), "signal-header-$width.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun normalAndConflictCardsKeepEveryFieldAtThreeWidgetWidths() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val normal = GuailiSignal("UNITREEUSDT", GuailiSignalKind.Extreme,
            listOf(GuailiSignalRun(GuailiSignalDirection.Negative, listOf("60", "90", "120", "180", "240", "360", "480"), 12)),
            trend = GuailiSignalTrend.Flat)
        val conflict = normal.copy(kind = GuailiSignalKind.Conflict, runs = listOf(
            GuailiSignalRun(GuailiSignalDirection.Negative, listOf("10S", "15S", "30S", "45S", "1"), 12),
            GuailiSignalRun(GuailiSignalDirection.Positive, listOf("120", "180", "240", "360", "480"), 12)),
            phase = GuailiSignalPhase.DivergenceEasing, trend = GuailiSignalTrend.Up)
        val exit = normal.copy(kind = GuailiSignalKind.Compression, phase = GuailiSignalPhase.UpwardDeparture,
            transitionOnly = true)
        for (width in listOf(180, 250, 320)) {
            for ((index, signal) in listOf(normal, conflict, exit).withIndex()) {
                val remote = GlanceRemoteViews().compose(context, DpSize(width.dp, 240.dp)) {
                    androidx.glance.layout.Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                        SignalRow(signal, "EMA20")
                    }
                }.remoteViews
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val parent = FrameLayout(context)
                    parent.addView(remote.apply(context, parent))
                    val pixels = (width * context.resources.displayMetrics.density).toInt()
                    parent.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    parent.layout(0, 0, pixels, parent.measuredHeight)
                    val fields = mutableListOf<TextView>()
                    fun visit(view: View) {
                        if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) fields += view
                        if (view is ViewGroup) for (child in 0 until view.childCount) visit(view.getChildAt(child))
                    }
                    visit(parent)
                    assertTrue(fields.any { it.text.toString() == "UNITREE" })
                    assertTrue(fields.any { it.text.toString() == signalPhaseLabel(signal) })
                    fields.forEach { text ->
                        val layout = requireNotNull(text.layout)
                        for (line in 0 until layout.lineCount) {
                            assertEquals("$width dp: truncated ${text.text}", 0, layout.getEllipsisCount(line))
                        }
                    }
                    val bitmap = Bitmap.createBitmap(pixels, parent.measuredHeight, Bitmap.Config.ARGB_8888)
                    parent.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "signal-card-$width-$index.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
