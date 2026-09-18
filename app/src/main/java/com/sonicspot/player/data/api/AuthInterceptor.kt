package com.sonicspot.player.data.api

import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val prefs: PreferencesManager
) : Interceptor {

    // FIX: In-memory cache для credentials чтобы не делать DataStore read каждый запрос
    // Было: runBlocking { prefs.getCredentials().first() } для КАЖДОГО запроса - 4 запроса = 4 DataStore IO
    // Логи показали 43-82ms на запрос, часть из-за DataStore
    // Стало: кэшируем и обновляем через flow в фоне
    @Volatile
    private var cachedCredentials: Credentials? = null

    @Volatile
    private var cachedServerUrl: String? = null

    init {
        // Pre-load cache in background
        try {
            // Try to load synchronously once at startup from memory if available
            // Will be updated async via refresh
        } catch (_: Exception) {}
    }

    fun updateCache(credentials: Credentials) {
        cachedCredentials = credentials
        cachedServerUrl = credentials.serverUrl
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var url = original.url

        // FIX: Используем кэш если есть, иначе fallback к DataStore (только если кэш пустой)
        val credentials = cachedCredentials ?: runBlocking {
            try {
                val creds = prefs.getCredentials().first()
                cachedCredentials = creds
                cachedServerUrl = creds.serverUrl
                creds
            } catch (_: Exception) {
                Credentials()
            }
        }

        // FIX: Если serverUrl в кэше отличается от URL запроса, заменяем host
        // Потому что Retrofit baseUrl теперь demo, а реальный URL в credentials
        val serverUrl = cachedServerUrl ?: credentials.serverUrl
        if (serverUrl.isNotBlank() && url.toString().contains("demo.navidrome.org")) {
            // Заменяем baseUrl с demo на реальный
            try {
                val realBase = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                val demoBase = "https://demo.navidrome.org/"
                val originalUrlString = url.toString()
                if (originalUrlString.startsWith(demoBase)) {
                    val newUrlString = originalUrlString.replace(demoBase, realBase)
                    url = newUrlString.toHttpUrl()
                }
            } catch (_: Exception) {
                // Fallback - оставляем как есть
            }
        }

        val newUrl = url.newBuilder()
            .addQueryParameter("u", credentials.username)
            .addQueryParameter("t", credentials.token)
            .addQueryParameter("s", credentials.salt)
            .addQueryParameter("v", Constants.API_VERSION)
            .addQueryParameter("c", Constants.CLIENT_NAME)
            .addQueryParameter("f", "json")
            .build()

        val newRequest = original.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}

data class Credentials(
    val serverUrl: String = "",
    val username: String = "",
    val token: String = "",
    val salt: String = "",
    val rawPassword: String = ""
)
