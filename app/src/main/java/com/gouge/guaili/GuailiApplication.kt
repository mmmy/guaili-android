package com.gouge.guaili

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.gouge.guaili.widget.GuailiWidgetReceiver
import com.gouge.guaili.widget.GuailiWidgetScheduler
import com.gouge.guaili.widget.DecisionReminderScheduler
import com.gouge.xbot.XbotFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GuailiApplication : Application() {
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DecisionReminderScheduler.createNotificationChannel(this)
        // Existing widgets may restore encrypted XBot snapshots and initialize WorkManager.
        // Their reconciliation does not gate the Activity's first frame.
        initializationScope.launch {
            XbotFeature.initialize(this@GuailiApplication)
            val widgetIds = AppWidgetManager.getInstance(this@GuailiApplication).getAppWidgetIds(
                ComponentName(this@GuailiApplication, GuailiWidgetReceiver::class.java),
            )
            if (widgetIds.isNotEmpty()) {
                GuailiWidgetScheduler.schedulePeriodic(this@GuailiApplication)
                DecisionReminderScheduler.schedulePeriodicWidgetUpdates(this@GuailiApplication)
                DecisionReminderScheduler.rescheduleAll(this@GuailiApplication)
            }
        }
    }
}
