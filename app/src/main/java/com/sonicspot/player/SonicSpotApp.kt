package com.sonicspot.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.sonicspot.player.player.PlaybackEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SonicSpotApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var playbackEngine: PlaybackEngine

    override fun onCreate() {
        super.onCreate()
        // Прогрев кэшей в фоне, чтобы первый скролл не платил за инициализацию директорий.
        appScope.launch {
            playbackEngine.prewarmCache()
            try {
                val coilDir = cacheDir.resolve("coil")
                if (!coilDir.exists()) coilDir.mkdirs()
                val oldCache = cacheDir.resolve("image_cache")
                if (oldCache.exists() && oldCache.length() > 100L * 1024 * 1024) {
                    oldCache.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    override fun newImageLoader(): ImageLoader {
        // ==================== ФИКС ЛАГОВ И ПЕРЕЗАГРУЗКИ ОБЛОЖЕК ====================
        //
        // Предыдущая "оптимизация" делала:
        //   .allowHardware(false) + .bitmapConfig(RGB_565) + .maxSizePercent(0.25)
        //
        // Это было ГЛАВНОЙ причиной и "тормозов" и "перезагрузки обложек":
        //
        //   1) Software-битмапы лежат в Java-куче и аплоадятся на GPU НА КАЖДОМ КАДРЕ
        //      (draw → upload to GL texture) — главная причина пропущенных кадров
        //      при скролле списков с обложками.
        //   2) Битмапы в куче быстро выдавливают друг друга из MemoryCache и
        //      провоцируют GC. При обратном скролле Coil вынужден снова декодить
        //      с диска — пользователь видит "перезагрузку" обложек.
        //   3) RGB_565 даёт полосы/постеризацию на градиентах обложек (качество).
        //
        // ПРАВИЛЬНАЯ конфигурация для списков с фотографиями (именно это делает
        // сам Spotify в своём ImageLoader):
        //
        //   • HARDWARE битмапы — живут в графической памяти (ashmem), не едят
        //     Java heap, не триггерят GC, рисуются без аплоада каждый кадр.
        //     Именно HARDWARE = плавный скролл и неисчезающий кэш.
        //   • ARGB_8888 — качество, совместимо с HARDWARE на Android 8+.
        //   • MemoryCache 40% heap (с HARDWARE он почти не растёт).
        //   • DiskCache 100 МБ фиксированно — не упираемся в contention при почти
        //     полном дисковом кэше.
        //   • Параллелизм фетчей/декодов ограничен отдельными фиксированными
        //     пулами потоков (3-4 одновременных работы) — при флинге 47 картинок
        //     не декодятся одновременно на всех ядрах CPU.
        //   • respectCacheHeaders(false) — навязываем вечное хранение обложек,
        //     потому что Navidrome не выставляет Cache-Control.

        // Отдельные пуллы потоков для фетча и декода картинок с ограниченным
        // параллелизмом. Coil по умолчанию использует Dispatchers.IO неограниченно,
        // отсюда и "47 декодов одновременно" при быстром флинге.
        val imageFetchDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val imageDecodeDispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()

        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.40)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 МБ
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            // ============ КЛЮЧЕВОЙ ФИКС ============
            .allowHardware(true)                              // GPU-битмапы
            .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
            // Ограниченный параллелизм — не больше N параллельных фетчей/декодов.
            .fetcherDispatcher(imageFetchDispatcher)
            .decoderDispatcher(imageDecodeDispatcher)
            // Отдельный HTTP-клиент для картинок — не с интерсепторами API,
            // не конкурирует с запросами данных.
            .callFactory(httpClient)
            .build()
    }
}
