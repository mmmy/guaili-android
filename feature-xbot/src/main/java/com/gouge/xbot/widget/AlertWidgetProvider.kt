package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gouge.xbot.data.SessionStore

class AlertWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { AlertWidgetRenderer.render(context, it) }
        AlertWidgetScheduler.enqueueImmediate(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Refresh -> AlertWidgetScheduler.enqueueImmediate(context)
            Tick, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> AlertWidgetRenderer.renderAll(context)
            SessionStore.ActionSessionChanged -> {
                if (AlertWidgetStore(context).currentScope() == null) AlertWidgetStore(context).clearSnapshot()
                AlertDataCoordinator(context).changed()
                if (AlertWidgetRenderer.widgetIds(context).isNotEmpty() && AlertWidgetStore(context).currentScope() != null) {
                    AlertWidgetScheduler.enqueueImmediate(context)
                }
            }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        AlertWidgetRenderer.render(context, id)
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { AlertWidgetStore(context).remove(it) } }
    override fun onEnabled(context: Context) { AlertWidgetScheduler.schedulePeriodic(context) }
    override fun onDisabled(context: Context) { AlertWidgetScheduler.cancel(context) }
    companion object {
        const val Refresh = "com.gouge.xbot.action.REFRESH_ALERT_WIDGET"
        const val Tick = "com.gouge.xbot.action.REPAINT_ALERT_WIDGET"
    }
}
