package com.gouge.xbot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.gouge.xbot.XbotNavigation
import com.gouge.xbot.ui.XbotPage
import com.gouge.xbot.R
import com.gouge.xbot.data.AlertVisibilityStore
import com.gouge.xbot.data.SessionStore
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AlertWidgetTarget(val configId: String, val alertId: Long, val generation: Long = 0)

object AlertWidgetIntents {
    const val ConfigId = "alert_config_id"
    const val AlertId = "tv_alert_id"
    const val Kind = "alert_widget_kind"
    const val Toggle = "toggle"
    const val Open = "open"
}

object AlertWidgetRenderer {
    fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, AlertWidgetProvider::class.java))

    fun renderAll(context: Context) {
        widgetIds(context).forEach { render(context, it) }
        AlertWidgetScheduler.scheduleLocalRepaint(context)
    }

    @Suppress("DEPRECATION")
    fun render(context: Context, id: Int) {
        val session = SessionStore(context)
        session.withCurrentGeneration(session.generation()) {
            renderCurrent(context, id)
        }
    }

    @Suppress("DEPRECATION")
    private fun renderCurrent(context: Context, id: Int) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, AlertWidgetProvider::class.java)) return
        val store = AlertWidgetStore(context)
        val views = buildViews(context, id, store.snapshot(), AlertVisibilityStore(context).getVisibleIds(),
            store.settings(id), store.currentScope() != null)
        manager.updateAppWidget(id, views)
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.xbot_aw_list)
    }

    @Suppress("DEPRECATION")
    fun buildViews(
        context: Context,
        id: Int,
        snapshot: AlertSnapshot?,
        visibleIds: Set<String>,
        settings: AlertWidgetSettings,
        loggedIn: Boolean,
        now: Instant = Instant.now(),
    ): RemoteViews {
        val data = if (loggedIn) snapshot ?: AlertSnapshot() else AlertSnapshot()
        val rows = alertWidgetRows(data, visibleIds, settings)
        val total = alertWidgetEntries(data, visibleIds).size
        val groupCount = data.configs.count { it.id in visibleIds }
        val views = RemoteViews(context.packageName, R.layout.xbot_alert_widget)
        views.setTextViewText(R.id.xbot_aw_count, "$total 条/$groupCount 组")
        views.setContentDescription(R.id.xbot_aw_count, "首页已选 $groupCount 组，共 $total 条警报，${if (settings.mode == AlertWidgetMode.Grouped) "按组显示" else "按时间显示"}")
        views.setTextViewText(R.id.xbot_aw_empty, when {
            !loggedIn -> "请登录 XBot，点击进入应用"
            visibleIds.isEmpty() -> "首页未选择警报配置，点击进入应用"
            snapshot == null || (data.configs.isEmpty() && data.updatedAtMillis == 0L) -> "尚未获取警报，请点击刷新"
            else -> "首页已选配置暂无警报"
        })
        val updated = if (data.updatedAtMillis > 0) DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(data.updatedAtMillis)) else "未同步"
        views.setTextViewText(R.id.xbot_aw_status, when {
            !loggedIn -> "未登录"
            data.error != null -> if (data.updatedAtMillis > 0) "失败·$updated" else "刷新失败"
            data.updatedAtMillis == 0L -> "未同步"
            else -> "$updated 更新"
        })
        views.setTextColor(R.id.xbot_aw_status, context.getColor(
            if (loggedIn && data.error != null) R.color.xbot_alert_widget_expired else R.color.xbot_alert_widget_muted))
        views.setContentDescription(R.id.xbot_aw_status, when {
            !loggedIn -> "请登录，数据跟随首页已选警报配置"
            data.error != null -> "${data.error}，上次成功更新：$updated"
            else -> "更新时间：$updated，首页已选 $groupCount 组"
        })
        views.setEmptyView(R.id.xbot_aw_list, R.id.xbot_aw_empty)
        if (Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(3)
            rows.forEach { items.addItem(stableId(it.key), rowViews(context, it, settings.density, now)) }
            views.setRemoteAdapter(R.id.xbot_aw_list, items.build())
        } else {
            val adapter = Intent(context, AlertWidgetRemoteService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("xbot://alert-widget/$id/adapter"))
            views.setRemoteAdapter(R.id.xbot_aw_list, adapter)
        }
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val rowIntent = Intent(context, AlertWidgetActionActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("xbot://alert-widget/$id/row"))
        views.setPendingIntentTemplate(R.id.xbot_aw_list,
            PendingIntent.getActivity(context, id, rowIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutable))
        val home = PendingIntent.getActivity(context, id,
            XbotNavigation.createIntent(context, XbotPage.Alerts).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("xbot://alert-widget/$id/home")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.xbot_aw_title, home)
        views.setOnClickPendingIntent(R.id.xbot_aw_empty, home)
        views.setOnClickPendingIntent(R.id.xbot_aw_status, home)
        views.setOnClickPendingIntent(R.id.xbot_aw_settings, PendingIntent.getActivity(context, id,
            Intent(context, AlertWidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("xbot://alert-widget/$id/settings")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.xbot_aw_refresh, PendingIntent.getBroadcast(context, id,
            Intent(context, AlertWidgetProvider::class.java).setAction(AlertWidgetProvider.Refresh)
                .setData(Uri.parse("xbot://alert-widget/$id/refresh")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return views
    }

    fun rowViews(context: Context, row: AlertWidgetRow, density: AlertWidgetDensity, now: Instant): RemoteViews {
        return when (row) {
            is AlertWidgetRow.Group -> RemoteViews(context.packageName, R.layout.xbot_alert_widget_group).apply {
                setTextViewText(R.id.xbot_aw_group_title, row.config.title.ifBlank { "未命名警报" })
                setTextViewText(R.id.xbot_aw_group_summary, row.summary(now))
                setFloat(R.id.xbot_aw_group_arrow, "setRotation", if (row.expanded) 90f else 0f)
                val hasExpired = row.entries.any { it.expiresAt?.let { time -> time <= now } == true }
                setTextColor(R.id.xbot_aw_group_summary, context.getColor(if (hasExpired) R.color.xbot_alert_widget_expired else R.color.xbot_alert_widget_muted))
                setContentDescription(R.id.xbot_aw_row_root, "${if (row.expanded) "收起" else "展开"} ${row.config.title}，${row.summary(now)}")
                setOnClickFillInIntent(R.id.xbot_aw_row_root, Intent().putExtra(AlertWidgetIntents.Kind, AlertWidgetIntents.Toggle)
                    .putExtra(AlertWidgetIntents.ConfigId, row.config.id))
            }
            is AlertWidgetRow.Alert -> RemoteViews(context.packageName,
                if (density == AlertWidgetDensity.Compact) R.layout.xbot_alert_widget_row_compact else R.layout.xbot_alert_widget_row).apply {
                val expiry = row.entry.expiresAt
                val expired = expiry?.let { it <= now } == true
                setTextViewText(R.id.xbot_aw_row_title, row.title())
                setTextViewText(R.id.xbot_aw_row_period, row.entry.alert.resolution.ifBlank { "-" })
                setTextViewText(R.id.xbot_aw_row_expiry, alertWidgetExpiryText(expiry, now))
                setTextViewText(R.id.xbot_aw_row_remaining, alertWidgetRemaining(expiry, now))
                setTextColor(R.id.xbot_aw_row_expiry, context.getColor(if (expired) R.color.xbot_alert_widget_expired else R.color.xbot_alert_widget_muted))
                setTextColor(R.id.xbot_aw_row_remaining, context.getColor(if (expired) R.color.xbot_alert_widget_expired else R.color.xbot_alert_widget_text))
                val vertical = if (density == AlertWidgetDensity.Compact) 6 else 10
                fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
                setViewPadding(R.id.xbot_aw_row_root, px(if (row.grouped) 26 else 12), px(vertical), px(12), px(vertical))
                setOnClickFillInIntent(R.id.xbot_aw_row_root, Intent().putExtra(AlertWidgetIntents.Kind, AlertWidgetIntents.Open)
                    .putExtra(AlertWidgetIntents.ConfigId, row.entry.config.id)
                    .putExtra(AlertWidgetIntents.AlertId, row.entry.alert.alertId))
            }
        }
    }

    fun stableId(key: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(key.toByteArray())).long
}
