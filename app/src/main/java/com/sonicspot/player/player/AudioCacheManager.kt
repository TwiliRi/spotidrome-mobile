package com.sonicspot.player.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.sonicspot.player.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio cache как в Tempus — максимально оптимизированный клиент использует SimpleCache
 * для стриминга с LRU eviction.
 *
 * Tempus: streaming cache 256MB LRU, downloads NoOp, cover cache OkHttp 500MB
 * Наш фикс: 256MB LRU для аудио, чтобы не стримить каждый раз и не фризить на буферизации
 *
 * Проверка: adb shell du -sh /data/data/com.sonicspot.player/cache/audio_cache
 * Ожидаемый эффект: повторное воспроизведение трека <50ms вместо 1-2с сети, -буферизация
 */
@Singleton
class AudioCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var simpleCache: SimpleCache? = null
    private val lock = Any()

    companion object {
        private const val CACHE_DIR_NAME = "audio_cache"
        private const val CACHE_SIZE_BYTES = 256L * 1024L * 1024L // 256MB как в Tempus
    }

    fun getCache(): SimpleCache {
        synchronized(lock) {
            if (simpleCache != null) return simpleCache!!

            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val dbProvider = StandaloneDatabaseProvider(context)

            simpleCache = SimpleCache(cacheDir, evictor, dbProvider).also {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("AudioCache", "Cache ready dir=${cacheDir.absolutePath} size=${CACHE_SIZE_BYTES / 1024 / 1024}MB")
                }
            }
            return simpleCache!!
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                simpleCache?.release()
            } catch (_: Exception) {}
            simpleCache = null
        }
    }

    fun getCacheSize(): Long {
        return try {
            val dir = File(context.cacheDir, CACHE_DIR_NAME)
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun clear() {
        synchronized(lock) {
            try {
                simpleCache?.let { cache ->
                    val dir = File(context.cacheDir, CACHE_DIR_NAME)
                    cache.release()
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
                simpleCache = null
            } catch (_: Exception) {}
        }
    }
}
