package com.gouge.xbot.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class AlertWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (AlertWidgetRenderer.widgetIds(applicationContext).isEmpty()) return Result.success()
        AlertWidgetRenderer.renderAll(applicationContext)
        if (AlertWidgetStore(applicationContext).currentScope() == null) return Result.success()
        return try {
            // Ordinary list reads only. Cache rebuild is an explicit action on the home page.
            AlertDataCoordinator(applicationContext).refresh()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (runAttemptCount < 2 && AlertWidgetStore(applicationContext).currentScope() != null) Result.retry()
            else Result.success()
        }
    }
}
