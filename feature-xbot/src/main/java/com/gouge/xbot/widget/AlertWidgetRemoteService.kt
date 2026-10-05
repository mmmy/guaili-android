package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.SessionStore
import java.time.Instant

// Compatibility adapter for Android 8–11. Android 12+ uses RemoteCollectionItems.
class AlertWidgetRemoteService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        private var rows = emptyList<AlertWidgetRow>()
        private var density = AlertWidgetDensity.Compact
        private var loadedScope: String? = null
        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            val session = SessionStore(applicationContext)
            session.withCurrentGeneration(session.generation()) {
                loadCurrentRows(session)
            }
        }
        private fun loadCurrentRows(session: SessionStore) {
            val store = AlertWidgetStore(applicationContext)
            loadedScope = session.currentScope()
            val settings = store.settings(id)
            density = settings.density
            rows = alertWidgetRows(store.snapshot() ?: AlertSnapshot(), AlertVisibilityStore(applicationContext).getVisibleIds(), settings)
        }
        override fun getCount() = if (loadedScope != null && loadedScope == SessionStore(applicationContext).currentScope()) rows.size else 0
        override fun getViewAt(position: Int): RemoteViews? {
            var view: RemoteViews? = null
            SessionStore(applicationContext).withCurrentScope(loadedScope) {
                view = rows.getOrNull(position)?.let {
                    AlertWidgetRenderer.rowViews(applicationContext, it, density, Instant.now())
                }
            }
            return view
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 3
        override fun getItemId(position: Int) = rows.getOrNull(position)?.let { AlertWidgetRenderer.stableId(it.key) } ?: 0L
        override fun hasStableIds() = true
        override fun onDestroy() { rows = emptyList() }
    }
}
