package com.gouge.guaili

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.gouge.guaili.widget.GuailiWidgetReceiver
import com.gouge.guaili.widget.GuailiWidgetScheduler
import com.gouge.guaili.widget.DecisionReminderScheduler

class GuailiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DecisionReminderScheduler.createNotificationChannel(this)
        val widgetIds = AppWidgetManager.getInstance(this).getAppWidgetIds(
            ComponentName(this, GuailiWidgetReceiver::class.java),
        )
        if (widgetIds.isNotEmpty()) {
            GuailiWidgetScheduler.schedulePeriodic(this)
            DecisionReminderScheduler.schedulePeriodicWidgetUpdates(this)
            DecisionReminderScheduler.rescheduleAll(this)
        }
    }
}
