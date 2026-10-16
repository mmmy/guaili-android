package com.gouge.guaili.ui

import android.content.Intent
import com.gouge.guaili.MainActivity
import com.gouge.xbot.XbotNavigation
import com.gouge.xbot.widget.AlertWidgetIntents
import com.gouge.xbot.widget.AlertWidgetTarget

enum class AppDestination(val label: String, val testTag: String) {
    Market("行情", "nav-market"),
    Signals("信号", "nav-signals"),
    Alerts("TV警报", "nav-alerts"),
    Settings("设置", "nav-settings"),
}

data class AppLaunchRequest(
    val destination: AppDestination,
    val klineTarget: KlineTarget? = null,
    val alertTarget: AlertWidgetTarget? = null,
    val openAccount: Boolean = false,
    val marketView: String? = null,
)

/** Explicit destinations keep widget taps within their own service and business context. */
fun resolveAppLaunch(intent: Intent?): AppLaunchRequest? {
    if (intent == null) return null
    val symbol = intent.getStringExtra(MainActivity.ExtraWidgetSymbol)
    val interval = intent.getStringExtra(MainActivity.ExtraWidgetInterval)
    if (!symbol.isNullOrBlank() && !interval.isNullOrBlank()) {
        return AppLaunchRequest(AppDestination.Market, klineTarget = KlineTarget(symbol, interval,
            intent.getLongExtra("widget_price_alert", -1).takeIf { it > 0 },
            fromPullback = intent.getStringExtra("widget_market_view") == "Pullback"),
            marketView = intent.getStringExtra("widget_market_view"))
    }
    val configId = intent.getStringExtra(AlertWidgetIntents.ConfigId)
    if (intent.getStringExtra("widget_market_view") == "Pullback") return AppLaunchRequest(AppDestination.Market, marketView = "Pullback")
    if (!configId.isNullOrBlank()) {
        return AppLaunchRequest(
            AppDestination.Alerts,
            alertTarget = AlertWidgetTarget(configId, intent.getLongExtra(AlertWidgetIntents.AlertId, -1)),
        )
    }
    return when (intent.getStringExtra(XbotNavigation.ExtraPage)) {
        "Signals" -> AppLaunchRequest(AppDestination.Signals)
        "Alerts" -> AppLaunchRequest(AppDestination.Alerts)
        "Account" -> AppLaunchRequest(AppDestination.Settings, openAccount = true)
        else -> null
    }
}

fun Intent.clearAppLaunchExtras() {
    removeExtra(MainActivity.ExtraWidgetSymbol)
    removeExtra(MainActivity.ExtraWidgetInterval)
    removeExtra("widget_market_view")
    removeExtra("widget_market_source")
    removeExtra("widget_price_alert")
    removeExtra(XbotNavigation.ExtraPage)
    removeExtra(AlertWidgetIntents.ConfigId)
    removeExtra(AlertWidgetIntents.AlertId)
}
