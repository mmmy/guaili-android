package com.gouge.xbot.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

object ApiClientFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    // newBuilder retains the shared connection pool and dispatcher. Session interceptors
    // belong to a service, so a queued request can never pick up another account's token.
    private val transport by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    private data class SessionKey(val baseUrl: String, val generation: Long, val token: String)
    private val sessions = object : LinkedHashMap<SessionKey, XbotApiService>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionKey, XbotApiService>): Boolean = size > 4
    }

    internal fun forSession(
        baseUrl: String,
        generation: Long,
        token: String,
        isSessionCurrent: () -> Boolean,
    ): XbotApiService = synchronized(sessions) {
        val key = SessionKey(normalizeBaseUrl(baseUrl), generation, token)
        sessions.getOrPut(key) { create(key.baseUrl, isSessionCurrent) { token } }
    }

    internal fun clearSessions() = synchronized(sessions) { sessions.clear() }

    fun create(
        baseUrl: String,
        isSessionCurrent: () -> Boolean = { true },
        tokenProvider: () -> String?,
    ): XbotApiService {
        val client = transport.newBuilder()
            .addInterceptor { chain ->
                if (!isSessionCurrent()) throw java.io.IOException("账户已切换")
                val token = tokenProvider()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(XbotApiService::class.java)
    }
}
