package com.gouge.guaili

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.gouge.guaili.widget.GuailiWidgetReceiver
import com.gouge.guaili.widget.GuailiWidgetScheduler

class GuailiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val widgetIds = AppWidgetManager.getInstance(this).getAppWidgetIds(
            ComponentName(this, GuailiWidgetReceiver::class.java),
        )
        if (widgetIds.isNotEmpty()) {
            GuailiWidgetScheduler.schedulePeriodic(this)
        }
    }
}
