package com.gouge.xbot.data

import android.content.Context

class TvAlertSymbolStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun getLastTicker(): String = preferences.getString(LastTickerKey, null)
        ?.takeIf(String::isNotBlank)
        ?: DefaultTicker

    fun saveLastTicker(ticker: String) {
        preferences.edit().putString(LastTickerKey, ticker).apply()
    }

    companion object {
        const val DefaultTicker = "BINANCE:BTCUSDT.P"
        private const val PreferencesName = "xbot_tv_alert_preferences"
        private const val LastTickerKey = "last_ticker"
    }
}
