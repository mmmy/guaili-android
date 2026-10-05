package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gouge.xbot.data.SessionStore

class SignalWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val preferences = WidgetPreferences(context)
        appWidgetIds.forEach { appWidgetId ->
            SignalWidgetRenderer.render(
                context,
                appWidgetId,
                preferences.get(appWidgetId),
                "正在更新…",
            )
        }
        SignalWidgetScheduler.enqueueImmediate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == SessionStore.ActionSessionChanged) {
            SignalWidgetRenderer.renderAll(context)
            if (SessionStore(context).currentScope() != null) SignalWidgetScheduler.enqueueImmediate(context)
        }
        if (intent.action == ActionRefresh) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val snapshot = WidgetPreferences(context).get(appWidgetId)
                SignalWidgetRenderer.render(context, appWidgetId, snapshot, "正在更新…")
                SignalWidgetScheduler.enqueueImmediate(context, appWidgetId)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        SignalWidgetRenderer.render(
            context,
            appWidgetId,
            WidgetPreferences(context).get(appWidgetId),
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val preferences = WidgetPreferences(context)
        appWidgetIds.forEach(preferences::remove)
    }

    override fun onEnabled(context: Context) {
        SignalWidgetScheduler.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        SignalWidgetScheduler.cancelPeriodic(context)
    }

    companion object {
        const val ActionRefresh = "com.gouge.xbot.action.REFRESH_SIGNAL_WIDGET"
    }
}
