package com.sonicspot.player.debug

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Профайлер специально для HomeScreen - измеряет каждый сетевой запрос
 * и IO операцию, чтобы найти точную причину лага 2-3 минуты.
 *
 * Вставь вызовы в HomeViewModel.loadData() или используй как обертку.
 */
object HomeLagProfiler {

    data class StepResult<T>(
        val name: String,
        val durationMs: Double,
        val success: Boolean,
        val resultSize: Int = 0,
        val error: String? = null,
        val data: T? = null
    )

    /**
     * Профилирует загрузку Home с детальным замером каждого шага.
     * Возвращает отчет + данные.
     *
     * Пример использования в ViewModel:
     * ```
     * val report = HomeLagProfiler.profileHomeLoad(
     *   getFolders = { repository.getMusicFolders() },
     *   getRecent = { repository.getAlbums("recent", 12) },
     *   ...
     * )
     * ```
     */
    suspend fun <Folders, Albums, Playlists, Songs, Artists> profileHomeLoad(
        getCache: suspend () -> Any?,
        getFolders: suspend () -> Result<Folders>,
        getRecent: suspend () -> Result<Albums>,
        getNewest: suspend () -> Result<Albums>,
        getPlaylists: suspend () -> Result<Playlists>,
        getRandom: suspend () -> Result<Songs>,
        getArtists: suspend () -> Result<Artists>,
        getDislikedSync: suspend () -> Result<*>,
        saveCache: suspend () -> Unit,
        cacheIsFresh: () -> Boolean
    ): PerformanceTracer.HomeLoadReport {
        val report = PerformanceTracer.newHomeReport()
        val totalStart = System.nanoTime()

        // 1. Cache read
        val cacheResult = PerformanceTracer.measure("cache_read") {
            withContext(Dispatchers.IO) { getCache() }
        }
        report.cacheHit = cacheResult != null
        report.cacheReadMs = PerformanceTracer.getRecentSpans(5).firstOrNull { it.tag == "cache_read" }?.durationMs ?: 0.0

        if (report.cacheHit && cacheIsFresh()) {
            Log.d("SonicLag", "🟢 Cache HIT and FRESH - UI должен показаться мгновенно")
        } else {
            Log.d("SonicLag", "🟡 Cache MISS or STALE - грузим с сети")
        }

        // 2. Critical path - параллельно как в оригинале
        val criticalStart = System.nanoTime()
        try {
            coroutineScope {
                val foldersDef = async {
                    val start = System.nanoTime()
                    val res = getFolders()
                    report.foldersMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "folders: ${report.foldersMs}ms success=${res.isSuccess} size=${(res.getOrNull() as? Collection<*>)?.size}")
                    res
                }
                val recentDef = async {
                    val start = System.nanoTime()
                    val res = getRecent()
                    report.recentMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "recent: ${report.recentMs}ms success=${res.isSuccess}")
                    res
                }
                val newestDef = async {
                    val start = System.nanoTime()
                    val res = getNewest()
                    report.newestMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "newest: ${report.newestMs}ms success=${res.isSuccess}")
                    res
                }
                val playlistsDef = async {
                    val start = System.nanoTime()
                    val res = getPlaylists()
                    report.playlistsMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "playlists: ${report.playlistsMs}ms success=${res.isSuccess}")
                    res
                }

                // Ждем критичные
                foldersDef.await()
                recentDef.await()
                newestDef.await()
                playlistsDef.await()
            }
        } catch (e: Exception) {
            Log.e("SonicLag", "Critical path failed", e)
        }
        report.totalMs = (System.nanoTime() - criticalStart) / 1_000_000.0

        // 3. Cache save - часто забывают что JSON сериализация тяжелая
        PerformanceTracer.start("cache_save")
        try {
            withContext(Dispatchers.IO) { saveCache() }
        } finally {
            report.cacheSaveMs = PerformanceTracer.end("cache_save")
        }

        // 4. Background - замеряем но не блокируем
        coroutineScope {
            async {
                val start = System.nanoTime()
                try {
                    val res = getRandom()
                    report.randomMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "random: ${report.randomMs}ms success=${res.isSuccess}")
                    Unit
                } catch (e: Exception) {
                    report.randomMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.e("SonicLag", "random failed ${report.randomMs}ms", e)
                    Unit
                }
            }
            async {
                val start = System.nanoTime()
                try {
                    val res = getArtists()
                    report.artistsMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "artists: ${report.artistsMs}ms success=${res.isSuccess}")
                    Unit
                } catch (e: Exception) {
                    report.artistsMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.e("SonicLag", "artists failed", e)
                    Unit
                }
            }
            async {
                val start = System.nanoTime()
                try {
                    val res = getDislikedSync()
                    report.dislikedSyncMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.d("SonicLag", "dislikedSync: ${report.dislikedSyncMs}ms success=${res.isSuccess}")
                    if (report.dislikedSyncMs > 1000) {
                        Log.e("SonicLag", "🔴 dislikedSync ОЧЕНЬ МЕДЛЕННО! ${report.dislikedSyncMs}ms - проверь getPlaylists + getPlaylist + DataStore loop")
                    }
                    Unit
                } catch (e: Exception) {
                    report.dislikedSyncMs = (System.nanoTime() - start) / 1_000_000.0
                    Log.e("SonicLag", "dislikedSync failed", e)
                    Unit
                }
            }
        }

        val totalTotal = (System.nanoTime() - totalStart) / 1_000_000.0
        Log.d("SonicLag", report.toLog())
        Log.d("SonicLag", "TOTAL wall time: ${totalTotal}ms")

        return report
    }

    /**
     * Диагностика PlayerManager - главный подозреваемый в лаге 2-3 минуты
     */
    object PlayerDiagnostics {
        fun logCreateMediaItem(songId: String, durationMs: Double) {
            if (durationMs > 16) {
                Log.e("SonicLag", "🔴 createMediaItem SLOW ${durationMs}ms for $songId - Uri.parse или getCoverArtUrl тяжелый")
            }
        }

        fun logSetMediaItems(count: Int, durationMs: Double) {
            Log.d("SonicLag", "setMediaItems count=$count ${durationMs}ms")
            if (durationMs > 100 && count > 20) {
                Log.e("SonicLag", "🔴 setMediaItems SLOW! $count items ${durationMs}ms - ExoPlayer готовит все треки сразу")
            }
        }

        fun logPrepare(count: Int, durationMs: Double, autoPlay: Boolean) {
            Log.d("SonicLag", "prepare count=$count autoPlay=$autoPlay ${durationMs}ms")
            if (!autoPlay && durationMs > 100) {
                Log.e("SonicLag", "🔴🔴🔴 prepare() вызван БЕЗ автоплея! $count треков ${durationMs}ms - ЭТО ГЛАВНАЯ ПРИЧИНА ЛАГА 2-3 МИН! Должен быть только setMediaItems без prepare")
            }
            if (autoPlay && count > 50 && durationMs > 500) {
                Log.e("SonicLag", "🔴 prepare $count треков ${durationMs}ms - очень тяжело, уменьши очередь или делай ленивую загрузку")
            }
        }

        fun logRestoreQueue(queueSize: Int, position: Long, autoPlay: Boolean) {
            Log.d("SonicLag", "restoreQueue size=$queueSize pos=$position autoPlay=$autoPlay thread=${Thread.currentThread().name}")
            if (queueSize > 50) {
                Log.w("SonicLag", "⚠ Большая очередь $queueSize - может лагать при восстановлении")
            }
        }
    }

    /**
     * Диагностика Compose рекомпозиций
     */
    object ComposeDiagnostics {
        private val recompositionCounts = mutableMapOf<String, Int>()
        private val lastLogTime = mutableMapOf<String, Long>()

        fun logRecomposition(component: String) {
            val count = (recompositionCounts[component] ?: 0) + 1
            recompositionCounts[component] = count

            val now = System.currentTimeMillis()
            val last = lastLogTime[component] ?: 0L
            if (now - last > 1000) { // логируем не чаще раза в секунду
                lastLogTime[component] = now
                if (count > 10) {
                    Log.e("SonicLag", "🔴 RECOMPOSITION STORM [$component] $count recompositions in last second - проверь remember/derivedStateOf/unstable params")
                } else if (count > 3) {
                    Log.w("SonicLag", "🟡 [$component] $count recompositions/sec")
                }
                recompositionCounts[component] = 0
            }
        }

        fun logBrushCreation() {
            Log.e("SonicLag", "🔴 Brush.verticalGradient создан заново! Должен быть remember { Brush... } - каждый рекомпоз создает новый объект")
        }

        fun logImageRequestCreation(url: String?) {
            Log.w("SonicLag", "🟡 ImageRequest.Builder создан для $url - лучше использовать AsyncImage(model=url) напрямую, Coil сам кэширует")
        }

        fun logLazyWithoutKeys() {
            Log.e("SonicLag", "🔴 LazyColumn/LazyRow без key! Compose не может реюзать - каждый скролл пересоздает все карточки")
        }
    }

    /**
     * Диагностика DataStore / Preferences - часто блокирует main
     */
    object DataStoreDiagnostics {
        fun logDataStoreRead(key: String, durationMs: Double, isMain: Boolean) {
            if (isMain) {
                Log.e("SonicLag", "🔴 DataStore READ ON MAIN [$key] ${durationMs}ms - ДОЛЖНО БЫТЬ В IO! Используй first() в Dispatchers.IO")
            } else if (durationMs > 100) {
                Log.w("SonicLag", "🟡 DataStore READ SLOW [$key] ${durationMs}ms")
            }
        }

        fun logDataStoreWriteLoop(count: Int, durationMs: Double) {
            Log.d("SonicLag", "DataStore WRITE LOOP count=$count ${durationMs}ms")
            if (count > 10 && durationMs > 500) {
                Log.e("SonicLag", "🔴 DataStore WRITE LOOP $count раз ${durationMs}ms - каждый edit() это IO! Сделай batch или используй один edit { } с циклом внутри")
            }
        }
    }
}
