package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gouge.xbot.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlertWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        repaint(context) { appContext -> ids.forEach { AlertWidgetRenderer.render(appContext, it) } }
        AlertWidgetScheduler.enqueueImmediate(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Refresh -> AlertWidgetScheduler.enqueueImmediate(context)
            Tick, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> repaint(context) {
                AlertWidgetRenderer.requestRenderAll(it).await()
            }
            SessionStore.ActionSessionChanged -> repaint(context) { appContext ->
                // SessionStore.clear() already removes the snapshot atomically with logout.
                // A delayed broadcast must never clear a new login's cache.
                AlertDataCoordinator(appContext).changedAndAwaitRender()
                if (AlertWidgetRenderer.widgetIds(appContext).isNotEmpty() && AlertWidgetStore(appContext).currentScope() != null) {
                    AlertWidgetScheduler.enqueueImmediate(appContext)
                }
            }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        repaint(context) { AlertWidgetRenderer.render(it, id) }
    }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { AlertWidgetStore(context).remove(it) } }
    override fun onEnabled(context: Context) { AlertWidgetScheduler.schedulePeriodic(context) }
    override fun onDisabled(context: Context) { AlertWidgetScheduler.cancel(context) }
    private fun repaint(context: Context, block: suspend (Context) -> Unit) {
        val pending = goAsync()
        val appContext = context.applicationContext
        repaintScope.launch {
            try { block(appContext) }
            catch (error: Exception) { android.util.Log.w("AlertWidget", "Widget repaint failed", error) }
            finally { pending.finish() }
        }
    }
    companion object {
        private val repaintScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val Refresh = "com.gouge.xbot.action.REFRESH_ALERT_WIDGET"
        const val Tick = "com.gouge.xbot.action.REPAINT_ALERT_WIDGET"
    }
}
