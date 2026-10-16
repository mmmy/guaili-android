package com.gouge.guaili.widget

import android.content.Context
import com.gouge.guaili.settings.GuailiSettings

/** These modes consume the legacy market snapshot; V2 and reminders have independent sources. */
internal fun WidgetMode.usesLegacyMarketSnapshot(): Boolean = when (this) {
    WidgetMode.Signals, WidgetMode.Matrix, WidgetMode.SingleSymbol -> true
    WidgetMode.SignalsV2, WidgetMode.PullbackObservation, WidgetMode.DecisionReminders -> false
}

internal fun WidgetMode.usesServerSignalsSnapshot(): Boolean =
    this == WidgetMode.SignalsV2 || this == WidgetMode.PullbackObservation

internal fun legacyMarketWidgetTargets(targets: List<WidgetRefreshTarget>): List<WidgetRefreshTarget> =
    targets.filter { it.config.mode.usesLegacyMarketSnapshot() }

/** Keep live Glance snapshot subscriptions intact, and wake only affected inactive sessions. */
internal suspend fun updateLegacyMarketWidgets(context: Context, settings: GuailiSettings) {
    val widget = GuailiWidget()
    legacyMarketWidgetTargets(widgetRefreshTargets(context, settings)).forEach { target ->
        widget.update(context, target.id)
    }
}
