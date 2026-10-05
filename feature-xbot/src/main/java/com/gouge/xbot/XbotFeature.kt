package com.gouge.xbot

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.gouge.xbot.widget.AlertWidgetRenderer
import com.gouge.xbot.widget.AlertWidgetScheduler
import com.gouge.xbot.widget.SignalWidgetProvider
import com.gouge.xbot.widget.SignalWidgetScheduler

/** Called once by the host Application, after its normal initialization. */
object XbotFeature {
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val signalWidgetIds = AppWidgetManager.getInstance(appContext).getAppWidgetIds(
            ComponentName(appContext, SignalWidgetProvider::class.java),
        )
        if (signalWidgetIds.isNotEmpty()) SignalWidgetScheduler.schedulePeriodic(appContext)
        if (AlertWidgetRenderer.widgetIds(appContext).isNotEmpty()) {
            AlertWidgetScheduler.schedulePeriodic(appContext)
            AlertWidgetScheduler.scheduleLocalRepaint(appContext)
        }
    }
}
