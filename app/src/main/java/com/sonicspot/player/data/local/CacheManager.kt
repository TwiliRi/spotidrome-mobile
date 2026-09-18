package com.sonicspot.player.data.local

import android.content.Context
import com.sonicspot.player.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Serializable
data class CachedHomeData(
    val timestamp: Long = System.currentTimeMillis(),
    val recentAlbums: List<CachedAlbum> = emptyList(),
    val newestAlbums: List<CachedAlbum> = emptyList(),
    val randomSongs: List<CachedSong> = emptyList(),
    val artists: List<CachedArtist> = emptyList(),
    val playlists: List<CachedPlaylist> = emptyList()
)

@Serializable
data class CachedAlbum(val id: String, val name: String, val artist: String?, val artistId: String?, val coverArt: String?, val songCount: Int, val year: Int?)
@Serializable
data class CachedSong(val id: String, val title: String, val artist: String?, val album: String?, val albumId: String?, val artistId: String?, val coverArt: String?, val duration: Int)
@Serializable
data class CachedArtist(val id: String, val name: String, val coverArt: String?, val albumCount: Int)
@Serializable
data class CachedPlaylist(val id: String, val name: String, val songCount: Int, val public: Boolean, val owner: String?, val coverArt: String?)

data class CacheItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val sizeBytes: Long,
    val icon: CacheType
)

enum class CacheType {
    LYRICS,
    COVERS,
    HOME,
    SEARCH,
    TEMP,
    ALL
}

@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val _cacheState = MutableStateFlow<List<CacheItem>>(emptyList())
    val cacheState: StateFlow<List<CacheItem>> = _cacheState

    private val lyricsDir: File get() = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val homeCacheFile: File get() = File(context.cacheDir, "home_cache.json")
    private val searchCacheFile: File get() = File(context.cacheDir, "search_cache.json")

    // Smart lyrics cache config
    private val MAX_LYRICS_CACHE_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB
    private val MAX_LYRICS_FILES = 1000
    private val lyricsCacheMutex = Mutex()

    // LRU tracking for disk cache eviction
    private fun getLyricsFilesSortedByAccess(): List<File> {
        return try {
            lyricsDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun refreshCacheInfo() = withContext(Dispatchers.IO) {
        val items = mutableListOf<CacheItem>()

        // Lyrics cache
        val lyricsSize = getDirSize(lyricsDir)
        val lyricsCount = lyricsDir.listFiles()?.size ?: 0
        items.add(
            CacheItem(
                id = "lyrics",
                title = "Тексты песен",
                subtitle = "$lyricsCount файлов • LRCLIB + встроенные • кэшируются навсегда",
                sizeBytes = lyricsSize,
                icon = CacheType.LYRICS
            )
        )

        // Covers - Coil disk cache
        val coilCacheDir = File(context.cacheDir, "image_cache")
        val coilSize = getDirSize(coilCacheDir)
        // Также проверяем стандартный Coil cache
        val coilAltDir = File(context.cacheDir, "coil")
        val coilAltSize = getDirSize(coilAltDir)
        val totalCovers = coilSize + coilAltSize
        items.add(
            CacheItem(
                id = "covers",
                title = "Обложки",
                subtitle = "Кэш Coil • загружаются один раз • ускоряет скролл",
                sizeBytes = totalCovers,
                icon = CacheType.COVERS
            )
        )

        // Home cache - главная как в Twitch
        val homeSize = if (homeCacheFile.exists()) homeCacheFile.length() else 0L
        val homeAge = if (homeCacheFile.exists()) {
            val ageHours = (System.currentTimeMillis() - homeCacheFile.lastModified()) / (1000 * 60 * 60)
            if (ageHours < 1) "обновлен только что"
            else if (ageHours < 24) "$ageHours ч назад"
            else "${ageHours / 24} дн назад"
        } else "нет кэша"
        items.add(
            CacheItem(
                id = "home",
                title = "Главная страница",
                subtitle = "Кэш как в Twitch • $homeAge • мгновенная загрузка",
                sizeBytes = homeSize,
                icon = CacheType.HOME
            )
        )

        // Search cache
        val searchSize = if (searchCacheFile.exists()) searchCacheFile.length() else 0L
        items.add(
            CacheItem(
                id = "search",
                title = "Поиск",
                subtitle = "История и результаты поиска",
                sizeBytes = searchSize,
                icon = CacheType.SEARCH
            )
        )

        // Temp
        val tempSize = getDirSize(context.cacheDir) - lyricsSize - totalCovers - homeSize - searchSize
        items.add(
            CacheItem(
                id = "temp",
                title = "Временные файлы",
                subtitle = "Логи, временные данные",
                sizeBytes = tempSize.coerceAtLeast(0),
                icon = CacheType.TEMP
            )
        )

        _cacheState.value = items
    }

    suspend fun clearCache(type: CacheType) = withContext(Dispatchers.IO) {
        when (type) {
            CacheType.LYRICS -> {
                lyricsDir.deleteRecursively()
                lyricsDir.mkdirs()
            }
            CacheType.COVERS -> {
                File(context.cacheDir, "image_cache").deleteRecursively()
                File(context.cacheDir, "coil").deleteRecursively()
            }
            CacheType.HOME -> {
                homeCacheFile.delete()
            }
            CacheType.SEARCH -> {
                searchCacheFile.delete()
            }
            CacheType.TEMP -> {
                // Чистим все кроме lyrics, covers, home, search
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name != "lyrics" && file.name != "image_cache" && file.name != "coil" && file.name != "home_cache.json" && file.name != "search_cache.json") {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                }
            }
            CacheType.ALL -> {
                context.cacheDir.deleteRecursively()
                context.cacheDir.mkdirs()
                // Пересоздаем нужные папки
                lyricsDir.mkdirs()
            }
        }
        refreshCacheInfo()
    }

    // Lyrics disk cache - современно как Spotify
    suspend fun saveLyricsToDisk(key: String, plainLyrics: String?, syncedLyrics: String?, source: String) = withContext(Dispatchers.IO) {
        saveLyricsSmart(
            key = key,
            plain = plainLyrics,
            synced = syncedLyrics,
            source = source,
            duration = null,
            isSynced = !syncedLyrics.isNullOrBlank(),
            isInstrumental = false,
            qualityScore = if (!syncedLyrics.isNullOrBlank()) 80 else 50
        )
    }

    suspend fun getLyricsFromDisk(key: String): Triple<String?, String?, String?>? = withContext(Dispatchers.IO) {
        val entry = getLyricsSmart(key) ?: return@withContext null
        Triple(entry.plain.ifEmpty { null }, entry.synced.ifEmpty { null }, entry.source)
    }

    // ============ SMART LYRICS SYSTEM ============

    suspend fun saveLyricsSmart(
        key: String,
        plain: String?,
        synced: String?,
        source: String,
        duration: Int? = null,
        isSynced: Boolean = !synced.isNullOrBlank(),
        isInstrumental: Boolean = false,
        qualityScore: Int = 0,
        artist: String? = null,
        title: String? = null,
        language: String? = null
    ) = withContext(Dispatchers.IO) {
        lyricsCacheMutex.withLock {
            try {
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(120)
                val file = File(lyricsDir, "$safeKey.json")

                // Если уже есть кэш с более высоким качеством - не перезаписываем
                if (file.exists()) {
                    try {
                        val existingContent = file.readText()
                        val existing = json.decodeFromString<LyricsCacheEntry>(existingContent)
                        // Не перезаписываем высококачественный синк низким качеством
                        if (existing.isHighQuality() && qualityScore < existing.qualityScore) {
                            // Обновляем только hitCount и lastAccess
                            val updated = existing.copy(
                                hitCount = existing.hitCount + 1,
                                lastAccess = System.currentTimeMillis()
                            )
                            file.writeText(json.encodeToString(updated))
                            file.setLastModified(System.currentTimeMillis())
                            return@withLock
                        }
                    } catch (_: Exception) {}
                }

                val entry = LyricsCacheEntry(
                    plain = plain ?: "",
                    synced = synced ?: "",
                    source = source,
                    timestamp = System.currentTimeMillis(),
                    duration = duration,
                    qualityScore = qualityScore,
                    isInstrumental = isInstrumental,
                    isSynced = isSynced || !synced.isNullOrBlank(),
                    language = language,
                    hitCount = 1,
                    lastAccess = System.currentTimeMillis(),
                    artist = artist,
                    title = title
                )
                file.writeText(json.encodeToString(entry))
                file.setLastModified(System.currentTimeMillis())

                // Проверяем лимиты и чистим если нужно (LRU eviction)
                ensureLyricsCacheLimits()
            } catch (_: Exception) {}
        }
    }

    suspend fun getLyricsSmart(key: String): LyricsCacheEntry? = withContext(Dispatchers.IO) {
        lyricsCacheMutex.withLock {
            try {
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(120)
                val file = File(lyricsDir, "$safeKey.json")
                if (!file.exists()) return@withLock null

                val content = file.readText()
                // Пробуем новый формат
                try {
                    val entry = json.decodeFromString<LyricsCacheEntry>(content)
                    // Обновляем hitCount и lastAccess для LRU
                    val updated = entry.copy(
                        hitCount = entry.hitCount + 1,
                        lastAccess = System.currentTimeMillis()
                    )
                    file.writeText(json.encodeToString(updated))
                    file.setLastModified(System.currentTimeMillis())
                    return@withLock updated
                } catch (_: Exception) {
                    // Fallback старый формат Map
                    try {
                        val map = json.decodeFromString<Map<String, String>>(content)
                        val plain = map["plain"] ?: ""
                        val synced = map["synced"] ?: ""
                        val source = map["source"] ?: "lrclib"
                        val isSynced = synced.isNotBlank()
                        val entry = LyricsCacheEntry(
                            plain = plain,
                            synced = synced,
                            source = source,
                            timestamp = map["timestamp"]?.toLongOrNull() ?: file.lastModified(),
                            qualityScore = if (isSynced) 80 else 50,
                            isSynced = isSynced,
                            hitCount = 1,
                            lastAccess = System.currentTimeMillis()
                        )
                        // Мигрируем в новый формат
                        file.writeText(json.encodeToString(entry))
                        return@withLock entry
                    } catch (_: Exception) {
                        return@withLock null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun hasHighQualityLyrics(key: String): Boolean = withContext(Dispatchers.IO) {
        val entry = getLyricsSmart(key)
        entry?.isHighQuality() == true
    }

    suspend fun ensureLyricsCacheLimits() = withContext(Dispatchers.IO) {
        try {
            val files = getLyricsFilesSortedByAccess()
            val totalSize = files.sumOf { it.length() }

            // Если превышаем лимиты - удаляем самые старые (LRU)
            if (files.size > MAX_LYRICS_FILES || totalSize > MAX_LYRICS_CACHE_SIZE_BYTES) {
                var currentSize = totalSize
                var currentCount = files.size

                for (file in files) {
                    if (currentCount <= MAX_LYRICS_FILES * 0.8 && currentSize <= MAX_LYRICS_CACHE_SIZE_BYTES * 0.8) break
                    try {
                        currentSize -= file.length()
                        currentCount--
                        file.delete()
                    } catch (_: Exception) {}
                }
            }

            // Удаляем expired plain-only кэши старше 30 дней для апгрейда
            files.forEach { file ->
                try {
                    if (file.length() > 0) {
                        val content = file.readText()
                        val entry = json.decodeFromString<LyricsCacheEntry>(content)
                        if (entry.isExpired()) {
                            // Не удаляем, но помечаем как нуждающийся в обновлении
                            // Можно удалить чтобы попробовать найти синк заново
                            if (entry.qualityScore < 50) {
                                file.delete()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    suspend fun getLyricsCacheStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        try {
            val files = lyricsDir.listFiles() ?: emptyArray()
            val count = files.size
            val size = files.sumOf { it.length() }
            count to size
        } catch (_: Exception) {
            0 to 0L
        }
    }

    suspend fun prefetchLyricsCheck(key: String): Boolean = withContext(Dispatchers.IO) {
        // Проверяем нужен ли префетч: если нет кэша или кэш низкого качества
        val entry = getLyricsSmart(key)
        entry == null || !entry.isHighQuality()
    }

    suspend fun deleteLyricsCache(key: String) = withContext(Dispatchers.IO) {
        lyricsCacheMutex.withLock {
            try {
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(120)
                val file = File(lyricsDir, "$safeKey.json")
                if (file.exists()) file.delete()
                // Также удаляем возможные варианты с артистом_названием
                lyricsDir.listFiles()?.forEach { f ->
                    if (f.name.contains(safeKey.take(20)) || safeKey.contains(f.nameWithoutExtension.take(20))) {
                        // Не удаляем всё подряд, только если точно совпадает начало
                        if (f.nameWithoutExtension == safeKey) f.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Home cache - как в Twitch: мгновенная загрузка без прогрузки
    // FIX: Детальный трейсинг чтобы найти почему 735ms на MAIN
    suspend fun saveHomeCache(data: CachedHomeData) = withContext(Dispatchers.IO) {
        try {
            // FIX: Всегда тримим перед сохранением, даже если caller забыл
            val trimmed = trimForCache(data)

            com.sonicspot.player.debug.PerformanceTracer.start("saveHome_encode")
            val jsonString = json.encodeToString(trimmed)
            val encodeMs = com.sonicspot.player.debug.PerformanceTracer.end("saveHome_encode")
            if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "saveHome encode ${jsonString.length} chars ${encodeMs}ms trimmed: recent=${trimmed.recentAlbums.size} newest=${trimmed.newestAlbums.size} random=${trimmed.randomSongs.size} artists=${trimmed.artists.size} playlists=${trimmed.playlists.size}")

            // Защита: если даже после трима >60KB - не сохраняем, что-то пошло не так
            if (jsonString.length > 60 * 1024) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 saveHome STILL TOO BIG ${jsonString.length} chars after trim - deleting")
                if (homeCacheFile.exists()) homeCacheFile.delete()
                return@withContext
            }

            com.sonicspot.player.debug.PerformanceTracer.start("saveHome_write")
            homeCacheFile.writeText(jsonString)
            val writeMs = com.sonicspot.player.debug.PerformanceTracer.end("saveHome_write")
            if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "saveHome write ${writeMs}ms fileSize=${homeCacheFile.length()}")

            if (encodeMs > 200) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 saveHome encode SLOW ${encodeMs}ms - слишком много данных, уменьши размер кэша")
            }
            if (writeMs > 200) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 saveHome write SLOW ${writeMs}ms - медленный storage")
            }
            Unit
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "saveHome failed", e)
            Unit
        }
    }

    suspend fun clearHomeCache() = withContext(Dispatchers.IO) {
        try { if (homeCacheFile.exists()) homeCacheFile.delete() } catch (_: Exception) {}
    }

    suspend fun getHomeCache(): CachedHomeData? = withContext(Dispatchers.IO) {
        try {
            com.sonicspot.player.debug.PerformanceTracer.start("getHome_exists")
            val exists = homeCacheFile.exists()
            val existsMs = com.sonicspot.player.debug.PerformanceTracer.end("getHome_exists")
            if (!exists) {
                if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "getHomeCache: file not exists ${existsMs}ms")
                return@withContext null
            }

            val fileSize = homeCacheFile.length()
            if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "getHomeCache: fileSize=${fileSize} bytes existsCheck=${existsMs}ms")

            // FIX: Агрессивная защита от старого огромного кэша (логи показали 140KB + 1072 артистов + decode 279ms)
            // Было: удаляем только если >1MB. Стало: >60KB уже подозрительно, >100KB удаляем сразу
            if (fileSize > 100 * 1024) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 home_cache.json TOO BIG ${fileSize / 1024}KB (was 140KB with 1072 artists) - deleting to fix lag!")
                homeCacheFile.delete()
                return@withContext null
            }
            if (fileSize > 60 * 1024) {
                android.util.Log.w("SonicLag", "⚠️ home_cache.json big ${fileSize / 1024}KB - will try to decode but will trim after")
            }

            com.sonicspot.player.debug.PerformanceTracer.start("getHome_readText")
            val content = homeCacheFile.readText()
            val readMs = com.sonicspot.player.debug.PerformanceTracer.end("getHome_readText")
            if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "getHomeCache: readText ${content.length} chars ${readMs}ms")

            if (readMs > 200) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 readText SLOW ${readMs}ms for ${content.length} chars - медленный storage")
            }

            com.sonicspot.player.debug.PerformanceTracer.start("getHome_decode")
            val data = json.decodeFromString<CachedHomeData>(content)
            val decodeMs = com.sonicspot.player.debug.PerformanceTracer.end("getHome_decode")
            if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "getHomeCache: decode ${decodeMs}ms recent=${data.recentAlbums.size} newest=${data.newestAlbums.size} random=${data.randomSongs.size} artists=${data.artists.size} playlists=${data.playlists.size}")

            if (decodeMs > 200) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 JSON decode SLOW ${decodeMs}ms - слишком много данных или медленный CPU")
            }

            // FIX: Если в кэше 1072 артистов как в логах - это старый баг, удаляем кэш и возвращаем null
            // Логи: recent=12 newest=12 random=50 artists=1072 playlists=22 -> 140KB -> 279ms decode -> Davey 1094ms
            if (data.artists.size > 100 || data.randomSongs.size > 30 || data.recentAlbums.size > 20) {
                if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "🔴 Cache contains HUGE data artists=${data.artists.size} random=${data.randomSongs.size} - deleting old cache! This was causing Davey 1094ms + Skipped 43 frames")
                homeCacheFile.delete()
                // Возвращаем тримнутую версию чтобы не лагать, но в следующий раз будет уже маленький файл
                val trimmed = trimForCache(data)
                if (BuildConfig.DEBUG) android.util.Log.d("SonicLag", "Returning trimmed cache instead: artists ${data.artists.size} -> ${trimmed.artists.size}, random ${data.randomSongs.size} -> ${trimmed.randomSongs.size}")
                return@withContext trimmed
            }

            data
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("SonicLag", "getHomeCache failed", e)
            try { if (homeCacheFile.exists()) homeCacheFile.delete() } catch (_: Exception) {}
            null
        }
    }

    fun isHomeCacheFresh(): Boolean {
        return try {
            if (!homeCacheFile.exists()) return false
            val age = System.currentTimeMillis() - homeCacheFile.lastModified()
            age < 24 * 60 * 60 * 1000
        } catch (_: Exception) {
            false
        }
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            // Оптимизировано для производительности - не используем walkTopDown для больших кэшей
            var size = 0L
            val files = dir.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isFile) file.length()
                else {
                    // Для поддиректорий считаем только 1 уровень чтобы не дёргать UI
                    try {
                        file.listFiles()?.sumOf { it.length() } ?: 0L
                    } catch (_: Exception) { 0L }
                }
                // Ранний выход если уже большой размер чтобы не блокировать
                if (size > 200L * 1024 * 1024) break
            }
            size
        } catch (_: Exception) {
            0L
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${(bytes / 1024f).roundToInt()} КБ"
            bytes < 1024 * 1024 * 1024 -> "${(bytes / (1024f * 1024f) * 10).roundToInt() / 10f} МБ"
            else -> "${(bytes / (1024f * 1024f * 1024f) * 10).roundToInt() / 10f} ГБ"
        }
    }

    fun getTotalSize(): Long = _cacheState.value.sumOf { it.sizeBytes }

    fun trimForCache(data: CachedHomeData): CachedHomeData {
        return data.copy(
            recentAlbums = data.recentAlbums.take(6),
            newestAlbums = data.newestAlbums.take(6),
            randomSongs = data.randomSongs.take(6),
            artists = data.artists.take(10),
            playlists = data.playlists.take(10)
        )
    }
}
