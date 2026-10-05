package com.gouge.xbot.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.gouge.xbot.XbotNavigation
import com.gouge.xbot.ui.XbotPage
import com.gouge.xbot.data.AlertVisibilityStore

class AlertWidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val configId = intent.getStringExtra(AlertWidgetIntents.ConfigId)
        if (intent.getStringExtra(AlertWidgetIntents.Kind) == AlertWidgetIntents.Toggle) {
            if (id in AlertWidgetRenderer.widgetIds(this) && configId != null) {
                val store = AlertWidgetStore(this)
                val configs = store.snapshot()?.configs.orEmpty().filter { it.id in AlertVisibilityStore(this).getVisibleIds() }
                if (configs.any { it.id == configId }) {
                    val settings = store.settings(id)
                    val expanded = (settings.expandedGroupIds ?: configs.take(1).mapTo(hashSetOf()) { it.id }).toMutableSet()
                    if (!expanded.remove(configId)) expanded.add(configId)
                    store.saveSettings(id, settings.copy(expandedGroupIds = expanded))
                    AlertWidgetRenderer.render(this, id)
                }
            }
        } else {
            startActivity(XbotNavigation.createIntent(this, XbotPage.Alerts)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlertWidgetIntents.ConfigId, configId)
                .putExtra(AlertWidgetIntents.AlertId, intent.getLongExtra(AlertWidgetIntents.AlertId, -1)))
        }
        finish()
    }
}
