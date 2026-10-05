package com.gouge.xbot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.gouge.xbot.XbotNavigation
import com.gouge.xbot.ui.XbotPage
import com.gouge.xbot.R
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.domain.DirectionState
import com.gouge.xbot.domain.SignalCommentItem
import com.gouge.xbot.domain.SignalCommentType
import com.gouge.xbot.domain.directionState
import com.gouge.xbot.domain.formatWidgetExpiry
import com.gouge.xbot.domain.levelText
import com.gouge.xbot.domain.parseSignalComment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SignalWidgetRenderer {
    fun render(
        context: Context,
        appWidgetId: Int,
        state: WidgetState?,
        status: String? = null,
    ) {
        val session = SessionStore(context)
        // Read the persisted snapshot and publish while holding the session lock.
        // A delayed worker/provider can never republish a previous account's raw state.
        session.withCurrentGeneration(session.generation()) {
            val currentState = WidgetPreferences(context).get(appWidgetId)
            val currentStatus = when {
                session.currentScope() == null -> "请登录 XBot Signal"
                currentState != state -> null
                else -> status
            }
            renderCurrent(context, appWidgetId, currentState, currentStatus)
        }
    }

    private fun renderCurrent(
        context: Context,
        appWidgetId: Int,
        state: WidgetState?,
        status: String?,
    ) {
        if (state != null && state.signals.size > 1) {
            renderDual(context, appWidgetId, state, status)
            return
        }
        val snapshot = state?.signals?.firstOrNull()
        val views = RemoteViews(context.packageName, R.layout.xbot_signal_widget)
        val signal = snapshot?.toSignalViewDto()
        val comments = parseSignalComment(signal?.comment)

        if (signal == null) {
            views.setViewVisibility(R.id.xbot_widget_signal_icon, View.GONE)
            views.setViewVisibility(R.id.xbot_widget_symbol, View.VISIBLE)
            views.setTextViewText(R.id.xbot_widget_symbol, "XBot Signal")
            views.setTextViewText(R.id.xbot_widget_name, "尚未选择信号")
            views.setTextViewText(R.id.xbot_widget_level, "级别  -")
            views.setTextViewText(R.id.xbot_widget_direction, "-")
            views.setTextViewText(R.id.xbot_widget_expiry, "-")
        } else {
            val direction = directionState(signal.longOn, signal.shortOn)
            val expiry = formatWidgetExpiry(signal.expireAt)
            val iconType = resolveSignalIcon(
                signal.name,
                SignalIconMappingStore(context).getAll(),
            )
            views.setViewVisibility(
                R.id.xbot_widget_signal_icon,
                if (iconType == null) View.GONE else View.VISIBLE,
            )
            if (iconType != null) {
                views.setImageViewResource(
                    R.id.xbot_widget_signal_icon,
                    iconType.drawableResource(direction),
                )
                views.setContentDescription(
                    R.id.xbot_widget_signal_icon,
                    "${signal.name} · ${iconType.label} · ${direction.label}",
                )
            }
            views.setViewVisibility(
                R.id.xbot_widget_symbol,
                if (snapshot.showSymbol) View.VISIBLE else View.GONE,
            )
            views.setTextViewText(R.id.xbot_widget_symbol, signal.symbol.ifBlank { "-" })
            views.setTextViewText(
                R.id.xbot_widget_name,
                if (iconType == null) signal.name.ifBlank { "信号设置" } else "",
            )
            views.setTextViewText(R.id.xbot_widget_level, "级别  ${signal.levelText()}  ›")
            views.setTextViewText(R.id.xbot_widget_direction, direction.label)
            views.setTextViewText(R.id.xbot_widget_expiry, "${expiry.text}  ›")
            views.setTextColor(
                R.id.xbot_widget_direction,
                context.getColor(direction.colorResource()),
            )
            views.setTextColor(
                R.id.xbot_widget_expiry,
                context.getColor(
                    when {
                        expiry.isExpired -> R.color.xbot_widget_expired
                        expiry.isExpiringSoon -> R.color.xbot_signal_orange
                        else -> R.color.xbot_signal_cyan
                    },
                ),
            )
        }
        renderComments(
            views = views,
            comments = comments,
        )

        val footer = status ?: snapshot?.let {
            val updated = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it.updatedAtMillis))
            updated
        } ?: "点击进入应用"
        views.setTextViewText(R.id.xbot_widget_status, footer)

        val openIntent = XbotNavigation.createIntent(context, XbotPage.Signals).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_root,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        if (snapshot != null) {
            views.setOnClickPendingIntent(
                R.id.xbot_widget_level,
                quickSettingsPendingIntent(
                    context,
                    appWidgetId,
                    snapshot.signalId,
                    QuickSettingsField.Level,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.xbot_widget_expiry,
                quickSettingsPendingIntent(
                    context,
                    appWidgetId,
                    snapshot.signalId,
                    QuickSettingsField.Expiry,
                ),
            )
        }

        val settingsIntent = Intent(context, SignalWidgetConfigActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("xbot-widget://settings/$appWidgetId")
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_settings,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val refreshIntent = Intent(context, SignalWidgetProvider::class.java).apply {
            action = SignalWidgetProvider.ActionRefresh
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("xbot-widget://refresh/$appWidgetId")
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_refresh,
            PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }

    private fun renderDual(
        context: Context,
        appWidgetId: Int,
        state: WidgetState,
        status: String?,
    ) {
        val views = RemoteViews(context.packageName, R.layout.xbot_signal_widget_dual)
        val mappings = SignalIconMappingStore(context).getAll()
        val slots = listOf(
            CompactSlot(
                icon = R.id.xbot_widget_dual_icon_1,
                title = R.id.xbot_widget_dual_title_1,
                comment = R.id.xbot_widget_dual_comment_1,
                level = R.id.xbot_widget_dual_level_1,
                direction = R.id.xbot_widget_dual_direction_1,
                expiry = R.id.xbot_widget_dual_expiry_1,
            ),
            CompactSlot(
                icon = R.id.xbot_widget_dual_icon_2,
                title = R.id.xbot_widget_dual_title_2,
                comment = R.id.xbot_widget_dual_comment_2,
                level = R.id.xbot_widget_dual_level_2,
                direction = R.id.xbot_widget_dual_direction_2,
                expiry = R.id.xbot_widget_dual_expiry_2,
            ),
        )

        state.signals.take(slots.size).zip(slots).forEach { (snapshot, slot) ->
            renderCompactSignal(context, views, snapshot, slot, mappings)
            views.setOnClickPendingIntent(
                slot.level,
                quickSettingsPendingIntent(
                    context,
                    appWidgetId,
                    snapshot.signalId,
                    QuickSettingsField.Level,
                ),
            )
            views.setOnClickPendingIntent(
                slot.expiry,
                quickSettingsPendingIntent(
                    context,
                    appWidgetId,
                    snapshot.signalId,
                    QuickSettingsField.Expiry,
                ),
            )
        }

        val footer = status ?: state.signals.maxOfOrNull { it.updatedAtMillis }?.let {
            DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        } ?: "-"
        views.setTextViewText(R.id.xbot_widget_dual_status, footer)

        val openIntent = XbotNavigation.createIntent(context, XbotPage.Signals).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_dual_root,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val settingsIntent = Intent(context, SignalWidgetConfigActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("xbot-widget://settings/$appWidgetId")
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_dual_settings,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val refreshIntent = Intent(context, SignalWidgetProvider::class.java).apply {
            action = SignalWidgetProvider.ActionRefresh
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("xbot-widget://refresh/$appWidgetId")
        }
        views.setOnClickPendingIntent(
            R.id.xbot_widget_dual_refresh,
            PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }

    private fun renderCompactSignal(
        context: Context,
        views: RemoteViews,
        snapshot: WidgetSnapshot,
        slot: CompactSlot,
        mappings: List<SignalIconMapping>,
    ) {
        val signal = snapshot.toSignalViewDto()
        val direction = directionState(signal.longOn, signal.shortOn)
        val expiry = formatWidgetExpiry(signal.expireAt)
        val iconType = resolveSignalIcon(signal.name, mappings)
        views.setViewVisibility(slot.icon, if (iconType == null) View.GONE else View.VISIBLE)
        if (iconType != null) {
            views.setImageViewResource(slot.icon, iconType.drawableResource(direction))
            views.setContentDescription(
                slot.icon,
                "${signal.name} · ${iconType.label} · ${direction.label}",
            )
        }

        val title = buildList {
            if (snapshot.showSymbol && signal.symbol.isNotBlank()) add(signal.symbol)
            if (signal.name.isNotBlank()) add(signal.name)
        }.joinToString(" · ").ifBlank { "信号设置" }
        val comment = compactComment(signal.comment)
        views.setTextViewText(slot.title, title)
        views.setTextViewText(slot.comment, comment.text)
        views.setTextColor(slot.comment, context.getColor(comment.colorResource))
        views.setTextViewText(slot.level, "L ${signal.levelText()} ›")
        views.setTextViewText(slot.direction, direction.compactLabel())
        views.setViewVisibility(
            slot.direction,
            if (iconType == null) View.VISIBLE else View.GONE,
        )
        views.setTextViewText(slot.expiry, "${compactExpiry(expiry.text)} ›")
        views.setTextColor(slot.direction, context.getColor(direction.colorResource()))
        views.setTextColor(
            slot.expiry,
            context.getColor(
                when {
                    expiry.isExpired -> R.color.xbot_widget_expired
                    expiry.isExpiringSoon -> R.color.xbot_signal_orange
                    else -> R.color.xbot_signal_cyan
                },
            ),
        )
    }

    private fun compactComment(comment: String?): CompactComment {
        val item = parseSignalComment(comment).firstOrNull()
            ?: return CompactComment("", R.color.xbot_widget_muted)
        val label = item.type?.label.orEmpty()
        val text = when {
            item.type == SignalCommentType.OpenLong -> item.text
            item.type == SignalCommentType.OpenShort -> item.text
            label.isEmpty() -> item.text
            item.text.isEmpty() -> label
            else -> "$label ${item.text}"
        }
        val colorResource = when (item.type) {
            SignalCommentType.OpenLong -> R.color.xbot_signal_green
            SignalCommentType.OpenShort -> R.color.xbot_signal_red
            SignalCommentType.CloseLong -> R.color.xbot_signal_cyan
            SignalCommentType.CloseShort -> R.color.xbot_signal_orange
            null -> R.color.xbot_widget_muted
        }
        return CompactComment(text, colorResource)
    }

    private data class CompactComment(
        val text: String,
        val colorResource: Int,
    )

    private fun DirectionState.compactLabel(): String = when (this) {
        DirectionState.LongOnly -> "B"
        DirectionState.ShortOnly -> "S"
        DirectionState.Both -> "B/S"
        DirectionState.Disabled -> "—"
    }

    private fun compactExpiry(text: String): String = when {
        text.startsWith("已过期 · ") -> text.removePrefix("已过期 · ")
        text.startsWith("即将过期 · ") -> compactActiveExpiry(
            text.removePrefix("即将过期 · "),
        )
        "有效至 " in text -> compactActiveExpiry(text.substringAfter("有效至 "))
        "长期有效" in text -> "长期有效"
        else -> text.substringAfterLast(" · ")
    }

    private fun compactActiveExpiry(value: String): String {
        val exactTime = value.substringBefore(" · ")
        val remaining = value.substringAfter(" · ", missingDelimiterValue = "")
        return if (remaining.isEmpty()) exactTime else "$exactTime · $remaining"
    }

    private data class CompactSlot(
        val icon: Int,
        val title: Int,
        val comment: Int,
        val level: Int,
        val direction: Int,
        val expiry: Int,
    )

    fun renderAll(context: Context) {
        val widgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, SignalWidgetProvider::class.java),
        )
        val preferences = WidgetPreferences(context)
        widgetIds.forEach { appWidgetId ->
            render(context, appWidgetId, preferences.get(appWidgetId))
        }
    }

    private fun quickSettingsPendingIntent(
        context: Context,
        appWidgetId: Int,
        signalId: String,
        field: QuickSettingsField,
    ): PendingIntent {
        val intent = Intent(context, WidgetQuickSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetQuickSettingsActivity.ExtraField, field.intentValue)
            putExtra(WidgetQuickSettingsActivity.ExtraSignalId, signalId)
            data = Uri.parse("xbot-widget://quick/$appWidgetId/$signalId/${field.intentValue}")
        }
        return PendingIntent.getActivity(
            context,
            "$appWidgetId:$signalId:${field.intentValue}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun DirectionState.colorResource(): Int = when (this) {
        DirectionState.LongOnly -> R.color.xbot_signal_green
        DirectionState.ShortOnly -> R.color.xbot_signal_red
        DirectionState.Both -> R.color.xbot_signal_purple
        DirectionState.Disabled -> R.color.xbot_widget_muted
    }

    private fun renderComments(
        views: RemoteViews,
        comments: List<SignalCommentItem>,
    ) {
        val commentViewIds = listOf(
            R.id.xbot_widget_comment_plain,
            R.id.xbot_widget_open_long,
            R.id.xbot_widget_open_short,
            R.id.xbot_widget_close_long,
            R.id.xbot_widget_close_short,
        )
        commentViewIds.forEach { views.setViewVisibility(it, View.GONE) }

        if (comments.isEmpty()) {
            views.setViewVisibility(R.id.xbot_widget_comment_container, View.GONE)
            return
        }

        views.setViewVisibility(R.id.xbot_widget_comment_container, View.VISIBLE)
        val plainComment = comments.singleOrNull { it.type == null }
        if (plainComment != null) {
            views.setTextViewText(R.id.xbot_widget_comment_plain, plainComment.text)
            views.setViewVisibility(R.id.xbot_widget_comment_plain, View.VISIBLE)
            return
        }

        val commentsByType = comments.associateBy { it.type }
        setActionComment(views, R.id.xbot_widget_open_long, commentsByType[SignalCommentType.OpenLong])
        setActionComment(views, R.id.xbot_widget_open_short, commentsByType[SignalCommentType.OpenShort])
        setActionComment(views, R.id.xbot_widget_close_long, commentsByType[SignalCommentType.CloseLong])
        setActionComment(views, R.id.xbot_widget_close_short, commentsByType[SignalCommentType.CloseShort])
    }

    private fun setActionComment(
        views: RemoteViews,
        viewId: Int,
        comment: SignalCommentItem?,
    ) {
        if (comment == null) return
        val label = comment.type?.label.orEmpty()
        val text = if (comment.text.isEmpty()) label else "$label  ${comment.text}"
        views.setTextViewText(viewId, text)
        views.setViewVisibility(viewId, View.VISIBLE)
    }
}
