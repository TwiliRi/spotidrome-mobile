package com.sonicspot.player.debug

import android.util.Log
import com.sonicspot.player.data.local.CacheManager
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.PinnedRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Трассированная версия loadData для диагностики.
 * Скопируй этот код в HomeViewModel.loadData чтобы увидеть точные замеры в Logcat.
 *
 * Использование:
 * 1. Замени тело loadData на вызов HomeViewModelTracer.tracedLoadData(...)
 * 2. Запусти приложение, фильтр Logcat: SonicLag
 * 3. Увидишь отчет где тормозит
 */
object HomeViewModelTracer {

    suspend fun tracedLoadData(
        repository: MusicRepository,
        cacheManager: CacheManager,
        dislikedRepository: DislikedRepository,
        starredRepository: StarredRepository,
        pinnedRepository: PinnedRepository,
        playerManager: PlayerManager,
        // callbacks to update UI
        onInterimState: (folders: Any, recent: Any, newest: Any, playlists: Any) -> Unit,
        onRandomLoaded: (List<Any>) -> Unit,
        onArtistsLoaded: (List<Any>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val report = PerformanceTracer.newHomeReport()
        val totalStart = System.nanoTime()

        try {
            // --- CACHE READ ---
            PerformanceTracer.start("home_cache_read")
            val cached = withContext(Dispatchers.IO) {
                cacheManager.getHomeCache()
            }
            report.cacheReadMs = PerformanceTracer.end("home_cache_read")
            report.cacheHit = cached != null
            Log.d("SonicLag", "Cache hit=$cached isFresh=${cacheManager.isHomeCacheFresh()} read=${report.cacheReadMs}ms")

            // --- CRITICAL PATH ---
            PerformanceTracer.start("critical_path")
            val criticalStart = System.nanoTime()

            val foldersDeferred = coroutineScope {
                async {
                    PerformanceTracer.start("getMusicFolders")
                    try {
                        val result = withContext(Dispatchers.IO) { repository.getMusicFolders() }
                        val dur = PerformanceTracer.end("getMusicFolders")
                        report.foldersMs = dur
                        Log.d("SonicLag", "folders: ${dur}ms success=${result.isSuccess} size=${result.getOrNull()?.size}")
                        result
                    } catch (e: Exception) {
                        PerformanceTracer.end("getMusicFolders")
                        Log.e("SonicLag", "folders failed", e)
                        Result.failure(e)
                    }
                }
            }

            // Для остальных используем тот же scope что и в оригинале - viewModelScope
            // Здесь упрощенно в одном scope для теста
            val (folders, recent, newest, playlists) = coroutineScope {
                val foldersD = async {
                    PerformanceTracer.start("getMusicFolders")
                    val res = try {
                        withContext(Dispatchers.IO) { repository.getMusicFolders() }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    report.foldersMs = PerformanceTracer.end("getMusicFolders")
                    res
                }
                val recentD = async {
                    PerformanceTracer.start("getRecentAlbums")
                    val res = try {
                        withContext(Dispatchers.IO) { repository.getAlbums("recent", 12) }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    val dur = PerformanceTracer.end("getRecentAlbums")
                    report.recentMs = dur
                    Log.d("SonicLag", "recent: ${dur}ms success=${res.isSuccess}")
                    res
                }
                val newestD = async {
                    PerformanceTracer.start("getNewestAlbums")
                    val res = try {
                        withContext(Dispatchers.IO) { repository.getAlbums("newest", 12) }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    val dur = PerformanceTracer.end("getNewestAlbums")
                    report.newestMs = dur
                    Log.d("SonicLag", "newest: ${dur}ms success=${res.isSuccess}")
                    res
                }
                val playlistsD = async {
                    PerformanceTracer.start("getPlaylists")
                    val res = try {
                        withContext(Dispatchers.IO) { repository.getPlaylists() }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    val dur = PerformanceTracer.end("getPlaylists")
                    report.playlistsMs = dur
                    Log.d("SonicLag", "playlists: ${dur}ms success=${res.isSuccess} size=${res.getOrNull()?.size}")
                    res
                }

                val f = foldersD.await()
                val r = recentD.await()
                val n = newestD.await()
                val p = playlistsD.await()

                listOf(f, r, n, p)
            }

            report.totalMs = (System.nanoTime() - criticalStart) / 1_000_000.0
            PerformanceTracer.end("critical_path")

            // Interim state
            onInterimState(folders, recent, newest, playlists)

            // --- CACHE SAVE ---
            PerformanceTracer.start("cache_save")
            try {
                withContext(Dispatchers.IO) {
                    // cacheManager.saveHomeCache(...)
                    // Симуляция
                }
            } finally {
                report.cacheSaveMs = PerformanceTracer.end("cache_save")
            }

            // --- BACKGROUND ---
            coroutineScope {
                launch(Dispatchers.IO) {
                    PerformanceTracer.start("getRandomSongs")
                    try {
                        val res = repository.getRandomSongs(20)
                        val dur = PerformanceTracer.end("getRandomSongs")
                        report.randomMs = dur
                        Log.d("SonicLag", "random: ${dur}ms success=${res.isSuccess} size=${res.getOrNull()?.size}")
                        // onRandomLoaded(res.getOrNull() ?: emptyList())
                    } catch (e: Exception) {
                        PerformanceTracer.end("getRandomSongs")
                        Log.e("SonicLag", "random failed", e)
                    }
                }
                launch(Dispatchers.IO) {
                    PerformanceTracer.start("getArtists")
                    try {
                        val res = repository.getArtists()
                        val dur = PerformanceTracer.end("getArtists")
                        report.artistsMs = dur
                        Log.d("SonicLag", "artists: ${dur}ms success=${res.isSuccess} size=${res.getOrNull()?.size}")
                        // onArtistsLoaded(...)
                    } catch (e: Exception) {
                        PerformanceTracer.end("getArtists")
                        Log.e("SonicLag", "artists failed", e)
                    }
                }
                launch(Dispatchers.IO) {
                    PerformanceTracer.start("dislikedSync")
                    try {
                        val res = dislikedRepository.syncFromServer()
                        val dur = PerformanceTracer.end("dislikedSync")
                        report.dislikedSyncMs = dur
                        Log.d("SonicLag", "dislikedSync: ${dur}ms success=${res.isSuccess}")

                        if (dur > 1000) {
                            Log.e("SonicLag", "🔴🔴🔴 dislikedSync SLOW ${dur}ms! Check getPlaylists + getPlaylist + DataStore loop")
                        }
                    } catch (e: Exception) {
                        PerformanceTracer.end("dislikedSync")
                        Log.e("SonicLag", "dislikedSync failed", e)
                    }
                }
                launch(Dispatchers.IO) {
                    PerformanceTracer.start("starredSync")
                    try {
                        starredRepository.syncFromServer()
                        val dur = PerformanceTracer.end("starredSync")
                        report.starredSyncMs = dur
                        Log.d("SonicLag", "starredSync: ${dur}ms")
                    } catch (e: Exception) {
                        PerformanceTracer.end("starredSync")
                    }
                }
            }

            val totalTotal = (System.nanoTime() - totalStart) / 1_000_000.0
            Log.d("SonicLag", report.toLog())
            Log.d("SonicLag", "TOTAL wall time: ${totalTotal}ms")

        } catch (e: Exception) {
            Log.e("SonicLag", "loadData failed", e)
            onError(e)
        }
    }

    /**
     * Инструкция как встроить трейсер в существующий HomeViewModel
     */
    fun printIntegrationInstructions() {
        Log.d("SonicLag", """
            === HOW TO INTEGRATE TRACER INTO HomeViewModel ===
            
            1. В начало loadData добавь:
                val report = PerformanceTracer.newHomeReport()
                PerformanceTracer.start("loadData_total")
            
            2. Для каждого запроса:
                PerformanceTracer.start("getMusicFolders")
                val folders = repository.getMusicFolders()
                report.foldersMs = PerformanceTracer.end("getMusicFolders")
            
            3. После критичного пути:
                report.totalMs = ...
                _uiState.value = interimState
                PerformanceTracer.start("cache_save")
                cacheManager.saveHomeCache(...)
                report.cacheSaveMs = PerformanceTracer.end("cache_save")
            
            4. В фоне:
                viewModelScope.launch(Dispatchers.IO) {
                    PerformanceTracer.start("getRandomSongs")
                    val random = repository.getRandomSongs(20)
                    report.randomMs = PerformanceTracer.end("getRandomSongs")
                    _uiState.value = _uiState.value.copy(randomSongs = random)
                }
            
            5. В конце:
                PerformanceTracer.end("loadData_total")
                Log.d("SonicLag", report.toLog())
            
            6. Запусти, фильтр Logcat: SonicLag
            
            === PLAYER MANAGER TRACING ===
            
            В PlayerManager.restoreQueueFromServer:
                PerformanceTracer.start("restoreQueue")
                PerformanceTracer.start("getPlayQueue_network")
                val result = repository.getPlayQueue()
                PerformanceTracer.end("getPlayQueue_network")
                
                PerformanceTracer.start("createMediaItems_${'$'}{songs.size}")
                val mediaItems = songs.map { createMediaItem(it) }
                PerformanceTracer.end("createMediaItems_${'$'}{songs.size}")
                
                PerformanceTracer.start("setMediaItems_${'$'}{songs.size}")
                player.setMediaItems(mediaItems, startIndex, position)
                PerformanceTracer.end("setMediaItems_${'$'}{songs.size}")
                
                if (autoPlay) {
                    PerformanceTracer.start("prepare_${'$'}{songs.size}")
                    player.prepare()
                    PerformanceTracer.end("prepare_${'$'}{songs.size}")
                } else {
                    Log.d("SonicLag", "Skipping prepare() for autoPlay=false - FIX FOR LAG!")
                }
                
                PerformanceTracer.end("restoreQueue")
        """.trimIndent())
    }
}
