package com.sonicspot.player.data.api

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.sonicspot.player.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Дисковый кэш ответов API. Без него каждый вход в экран — полный круг до сервера:
     * Navidrome не отдаёт заголовков кэширования, поэтому OkHttp складывает ответ только если
     * мы сами допишем Cache-Control (этим занимается ApiCacheInterceptor).
     *
     * 50 МБ рассчитаны на каталог целиком: JSON альбомов и артистов занимает единицы мегабайт,
     * основной объём — обложки. При нехватке места OkHttp сам вытеснит старое по LRU.
     */
    @Provides
    @Singleton
    fun provideApiCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, "http_api"), 50L * 1024 * 1024)

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: AuthInterceptor,
        apiCache: Cache
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            // Сетевой перехватчик, а не обычный: он должен дописать Cache-Control ДО того,
            // как ответ попадёт в кэш OkHttp. Обычный перехватчик выполнился бы уже после кэша.
            .addNetworkInterceptor(ApiCacheInterceptor())
            .cache(apiCache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("nativeOkHttp")
    fun provideNativeOkHttp(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("subsonic")
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        // FIX: Убран runBlocking DataStore read на MAIN потоке при старте - вызывал лаг 750ms!
        // Было: runBlocking { prefs.getCredentials().first() } в provideRetrofit - блокировало MAIN при Hilt init
        // Стало: используем demo URL, реальный URL резолвится в AuthInterceptor через cached credentials
        // AuthInterceptor теперь использует in-memory cache, а не DataStore каждый раз
        val baseUrl = "https://demo.navidrome.org/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    @Named("native")
    fun provideNativeRetrofit(
        json: Json,
        @Named("nativeOkHttp") nativeOkHttp: OkHttpClient
    ): Retrofit {
        // FIX: Убран runBlocking
        val baseUrl = "https://demo.navidrome.org/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(nativeOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideNavidromeApi(@Named("subsonic") retrofit: Retrofit): NavidromeApi {
        return retrofit.create(NavidromeApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNavidromeNativeApi(@Named("native") nativeRetrofit: Retrofit): NavidromeNativeApi {
        return nativeRetrofit.create(NavidromeNativeApi::class.java)
    }

    @Provides
    @Singleton
    fun provideLrclibApi(
        json: Json
    ): LrclibApi {
        val lrclibClient = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(lrclibClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LrclibApi::class.java)
    }

    @Provides
    @Singleton
    fun provideApiFactory(
        okHttpClient: OkHttpClient,
        json: Json
    ): ApiFactory = ApiFactory(okHttpClient, json)

    @Provides
    @Singleton
    fun provideNativeApiFactory(
        @Named("nativeOkHttp") nativeOkHttp: OkHttpClient,
        json: Json
    ): NativeApiFactory = NativeApiFactory(nativeOkHttp, json)
}

class ApiFactory(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun create(baseUrl: String): NavidromeApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NavidromeApi::class.java)
    }
}

class NativeApiFactory(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun create(baseUrl: String): NavidromeNativeApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NavidromeNativeApi::class.java)
    }
}
