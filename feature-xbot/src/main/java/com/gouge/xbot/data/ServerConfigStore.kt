package com.gouge.xbot.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun getBaseUrl(): String = preferences.getString(BaseUrlKey, DefaultBaseUrl) ?: DefaultBaseUrl

    fun saveBaseUrl(baseUrl: String) {
        preferences.edit().putString(BaseUrlKey, normalizeBaseUrl(baseUrl)).apply()
    }

    companion object {
        const val DefaultBaseUrl = "http://192.168.1.100:3002/"
        private const val PreferencesName = "xbot_server_config"
        private const val BaseUrlKey = "signal_api_base_url"
    }
}

fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim()
    val url = trimmed.toHttpUrlOrNull()
    require(url != null && (url.scheme == "http" || url.scheme == "https")) {
        "服务器地址必须以 http:// 或 https:// 开头"
    }
    require(url.query == null && url.fragment == null) { "服务器地址不能包含查询参数或锚点" }
    val normalized = url.toString()
    return if (normalized.endsWith('/')) normalized else "$normalized/"
}
