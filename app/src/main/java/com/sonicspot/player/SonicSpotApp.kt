package com.sonicspot.player

import android.app.Application
import com.sonicspot.player.BuildConfig
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SonicSpotApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // FIX: Pre-initialize Coil disk cache dir in background to avoid contention on first scroll
        // Логи показали DiskLruCache contention 233ms + 195ms и Image decoding dropped x15
        appScope.launch {
            try {
                val coilDir = cacheDir.resolve("coil")
                if (!coilDir.exists()) coilDir.mkdirs()
                // Clean old huge image_cache if exists (was causing contention)
                val oldCache = cacheDir.resolve("image_cache")
                if (oldCache.exists() && oldCache.length() > 100L * 1024 * 1024) {
                    oldCache.deleteRecursively()
                    if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "Deleted old huge image_cache")
                }
                if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "Coil cache dir ready: ${coilDir.absolutePath}")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "Coil pre-init failed", e)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% RAM for ~30 images 304px RGB_565 = ~7MB
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizePercent(0.10) // FIX: Tempus cover cache 500MB, было 5% ~50MB -> часто evict и перекачка -> фризы
                    // Стало 10% ~100MB, баланс между местом и скоростью, LRU как в Tempus
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false) // FIX: no crossfade for 60fps
            .allowHardware(false) // FIX: no hardware bitmaps to avoid GPU upload contention
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565) // FIX: 2 bytes vs 4, 2x less memory, faster decode
            // FIX: Use IO dispatcher for decoding, not Default
            .fetcherDispatcher(Dispatchers.IO)
            .decoderDispatcher(Dispatchers.IO)
            .build()
    }
}
