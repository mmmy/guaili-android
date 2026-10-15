package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.XbotRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

class SignalWidgetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(applicationContext, SignalWidgetProvider::class.java),
        )
        if (widgetIds.isEmpty()) return Result.success()

        val requestedWidgetId = inputData.getInt(
            InputAppWidgetId,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val targetWidgetIds = if (requestedWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetIds
        } else {
            if (requestedWidgetId !in widgetIds) return Result.success()
            intArrayOf(requestedWidgetId)
        }

        val preferences = WidgetPreferences(applicationContext)
        val sessionStore = SessionStore(applicationContext)
        val requestGeneration = sessionStore.generation()
        if (sessionStore.getAccessToken().isNullOrBlank()) {
            targetWidgetIds.forEach { id ->
                SignalWidgetRenderer.render(
                    applicationContext,
                    id,
                    preferences.get(id),
                    "请登录 XBot Signal",
                )
            }
            return Result.success()
        }

        val repository = XbotRepository(ServerConfigStore(applicationContext), sessionStore).forSession(requestGeneration)
        if (requestedWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && preferences.get(requestedWidgetId) == null) {
            SignalWidgetRenderer.render(applicationContext, requestedWidgetId, null, "请重新配置小组件")
            return Result.success()
        }
        return try {
            val observation = repository.getSignalObservation()
            SignalWidgetUpdater.update(applicationContext, observation, requestGeneration, targetWidgetIds)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (requestGeneration != sessionStore.generation()) return Result.success()
            val unauthorized = error is HttpException && error.code() == 401
            val missingSignal = error is HttpException && error.code() == 404
            if (unauthorized) sessionStore.clearIfCurrent(requestGeneration)
            targetWidgetIds.forEach { id ->
                SignalWidgetRenderer.render(
                    applicationContext,
                    id,
                    preferences.get(id),
                    when {
                        unauthorized -> "登录已失效"
                        missingSignal -> "信号已不存在"
                        else -> "更新失败，保留上次数据"
                    },
                )
            }
            if (unauthorized || missingSignal) Result.success() else Result.retry()
        }
    }

    companion object {
        const val InputAppWidgetId = "app_widget_id"
    }
}
