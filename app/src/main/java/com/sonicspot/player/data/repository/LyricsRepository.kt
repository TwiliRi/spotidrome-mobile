package com.sonicspot.player.data.repository

import com.sonicspot.player.data.api.LrclibApi
import com.sonicspot.player.data.local.CacheManager
import com.sonicspot.player.data.local.LyricsQualityScorer
import com.sonicspot.player.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

data class LyricsResult(
    val plainLyrics: String? = null,
    val syncedLines: List<LyricLine> = emptyList(),
    val syncedRaw: String? = null,
    val isSynced: Boolean = false,
    val source: String = "lrclib",
    val qualityScore: Int = 0,
    val isInstrumental: Boolean = false
)

@Singleton
class LyricsRepository @Inject constructor(
    private val lrclibApi: LrclibApi,
    private val musicRepository: MusicRepository,
    private val cacheManager: CacheManager
) {
    // LRU memory cache - 100 entries max, как в Spotify
    private val memoryCache = object : LinkedHashMap<String, LyricsResult>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean {
            return size > 100
        }
    }
    private val memoryCacheMutex = Mutex()

    // Deduplication: если уже идет запрос для этого трека, не делаем второй
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<Result<LyricsResult>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val LRCLIB_TIMEOUT_MS = 10_000L
    private val PREFETCH_TIMEOUT_MS = 15_000L

    /**
     * Умная система загрузки субтитров:
     * 1. Memory LRU cache - мгновенно (<1ms)
     * 2. Disk cache с проверкой качества - мгновенно (<10ms)
     * 3. Если кэш низкого качества (plain only) - отдаем сразу, но в фоне пытаемся апгрейдить до synced
     * 4. LRCLIB приоритет с таймаутом 10 сек, оценка качества
     * 5. Fallback на embedded/server только если LRCLIB не нашел
     * 6. Deduplication concurrent запросов
     * 7. Prefetch следующих треков
     */
    suspend fun getLyricsForSong(song: Song): Result<LyricsResult> {
        val memKey = song.id
        val diskKey = song.id

        // Deduplication: если уже есть in-flight запрос для этого ID, ждем его
        inFlightRequests[memKey]?.let { deferred ->
            try {
                return deferred.await()
            } catch (_: Exception) {}
        }

        val deferred = scope.async {
            getLyricsInternal(song, memKey, diskKey, isPrefetch = false)
        }
        inFlightRequests[memKey] = deferred
        try {
            val result = deferred.await()
            return result
        } finally {
            inFlightRequests.remove(memKey)
        }
    }

    private suspend fun getLyricsInternal(song: Song, memKey: String, diskKey: String, isPrefetch: Boolean): Result<LyricsResult> = withContext(Dispatchers.IO) {
        val artist = song.artist?.trim() ?: ""
        val title = song.title.trim()
        val album = song.album?.trim()
        val durationSec = if (song.duration > 0) song.duration else null

        // 1. MEMORY CACHE - мгновенно, как в Spotify, без сети
        memoryCacheMutex.withLock {
            memoryCache[memKey]?.let { cached ->
                if (cached.syncedLines.isNotEmpty() || !cached.plainLyrics.isNullOrBlank() || cached.isInstrumental) {
                    // Если высококачественный синк - отдаем сразу, без сети
                    if (cached.isSynced && cached.qualityScore >= 80) {
                        return@withContext Result.success(cached)
                    }
                    // Если низкокачественный (plain only) и это не префетч - отдаем сразу, но в фоне апгрейдим
                    if (!isPrefetch && (cached.syncedLines.isEmpty() && !cached.isInstrumental)) {
                        // Запускаем фоновый апгрейд до synced
                        scope.launch {
                            tryUpgradeToSynced(song, memKey, diskKey)
                        }
                        // Отдаем то что есть сейчас, чтобы не показывать Empty
                        return@withContext Result.success(cached)
                    }
                    // Для префетча или если есть хоть что-то - отдаем
                    return@withContext Result.success(cached)
                }
            }
        }

        // 2. DISK CACHE - мгновенно, без сети, с проверкой качества
        try {
            val diskEntry = cacheManager.getLyricsSmart(diskKey)
            if (diskEntry != null && (diskEntry.plain.isNotBlank() || diskEntry.synced.isNotBlank() || diskEntry.isInstrumental)) {
                val syncedLines = if (diskEntry.synced.isNotBlank()) parseLrc(diskEntry.synced) else emptyList()
                val result = LyricsResult(
                    plainLyrics = diskEntry.plain.ifEmpty { null },
                    syncedLines = syncedLines,
                    syncedRaw = diskEntry.synced.ifEmpty { null },
                    isSynced = syncedLines.isNotEmpty(),
                    source = diskEntry.source,
                    qualityScore = diskEntry.qualityScore,
                    isInstrumental = diskEntry.isInstrumental
                )

                // Кэшируем в память для еще более быстрого доступа
                memoryCacheMutex.withLock {
                    memoryCache[memKey] = result
                }

                // Если высококачественный - отдаем сразу
                if (diskEntry.isHighQuality()) {
                    return@withContext Result.success(result)
                }

                // Если низкокачественный и не префетч - отдаем сразу, но в фоне апгрейдим
                if (!isPrefetch && !diskEntry.isHighQuality() && !diskEntry.isInstrumental) {
                    scope.launch {
                        tryUpgradeToSynced(song, memKey, diskKey)
                    }
                    return@withContext Result.success(result)
                }

                // Для префетча или если есть - отдаем
                if (diskEntry.synced.isNotBlank() || diskEntry.plain.isNotBlank()) {
                    return@withContext Result.success(result)
                }
            }
        } catch (_: Exception) {}

        // 3. Если это префетч и нет кэша - не блокируем UI, делаем с большим таймаутом в фоне
        val timeout = if (isPrefetch) PREFETCH_TIMEOUT_MS else LRCLIB_TIMEOUT_MS

        // 4. LRCLIB ПРИОРИТЕТ с таймаутом 10 сек и оценкой качества
        val lrclibResult = withTimeoutOrNull(timeout) {
            fetchFromLrclib(artist, title, album, durationSec)
        }

        if (lrclibResult != null && lrclibResult.isSuccess) {
            val res = lrclibResult.getOrNull()
            if (res != null && (res.syncedLines.isNotEmpty() || !res.plainLyrics.isNullOrBlank() || res.isInstrumental)) {
                // Сохраняем в оба кэша для мгновенной отдачи в следующий раз
                memoryCacheMutex.withLock {
                    memoryCache[memKey] = res
                }
                cacheManager.saveLyricsSmart(
                    key = diskKey,
                    plain = res.plainLyrics,
                    synced = res.syncedRaw,
                    source = res.source,
                    duration = durationSec,
                    isSynced = res.isSynced,
                    isInstrumental = res.isInstrumental,
                    qualityScore = res.qualityScore,
                    artist = artist,
                    title = title
                )
                return@withContext Result.success(res)
            }
        }

        // 5. Если LRCLIB не нашел за 10 сек и это не префетч - пробуем fallback быстро
        if (!isPrefetch) {
            // Встроенные тексты - быстро, локально
            try {
                val embeddedResult = musicRepository.getEmbeddedLyrics(song.id)
                embeddedResult.getOrNull()?.let { data ->
                    if (data.syncedLines.isNotEmpty() || !data.plainLyrics.isNullOrBlank()) {
                        val syncedRaw = if (data.syncedLines.isNotEmpty()) {
                            data.syncedLines.joinToString("\n") { line ->
                                "[%02d:%02d.%03d]${line.text}".format(
                                    line.timestampMs / 60000,
                                    (line.timestampMs / 1000) % 60,
                                    line.timestampMs % 1000
                                )
                            }
                        } else null
                        val quality = LyricsQualityScorer.score(
                            isSynced = data.syncedLines.isNotEmpty(),
                            source = data.source,
                            durationMatch = true,
                            exactMatch = true,
                            isInstrumental = false
                        )
                        val result = LyricsResult(
                            plainLyrics = data.plainLyrics,
                            syncedLines = data.syncedLines.map { LyricLine(it.timestampMs, it.text) },
                            syncedRaw = syncedRaw,
                            isSynced = data.syncedLines.isNotEmpty(),
                            source = data.source,
                            qualityScore = quality
                        )
                        memoryCacheMutex.withLock {
                            memoryCache[memKey] = result
                        }
                        cacheManager.saveLyricsSmart(
                            key = diskKey,
                            plain = data.plainLyrics,
                            synced = syncedRaw,
                            source = data.source,
                            duration = durationSec,
                            isSynced = data.syncedLines.isNotEmpty(),
                            qualityScore = quality,
                            artist = artist,
                            title = title
                        )
                        return@withContext Result.success(result)
                    }
                }
            } catch (_: Exception) {}

            // Сервер Navidrome
            try {
                if (artist.isNotBlank()) {
                    val serverResult = musicRepository.getLyricsByArtistTitle(artist, title)
                    serverResult.getOrNull()?.let { data ->
                        if (data.syncedLines.isNotEmpty() || !data.plainLyrics.isNullOrBlank()) {
                            val syncedRaw = if (data.syncedLines.isNotEmpty()) {
                                data.syncedLines.joinToString("\n") { line ->
                                    "[%02d:%02d.%03d]${line.text}".format(
                                        line.timestampMs / 60000,
                                        (line.timestampMs / 1000) % 60,
                                        line.timestampMs % 1000
                                    )
                                }
                            } else null
                            val quality = LyricsQualityScorer.score(
                                isSynced = data.syncedLines.isNotEmpty(),
                                source = data.source,
                                durationMatch = false,
                                exactMatch = false,
                                isInstrumental = false
                            )
                            val result = LyricsResult(
                                plainLyrics = data.plainLyrics,
                                syncedLines = data.syncedLines.map { LyricLine(it.timestampMs, it.text) },
                                syncedRaw = syncedRaw,
                                isSynced = data.syncedLines.isNotEmpty(),
                                source = data.source,
                                qualityScore = quality
                            )
                            if (data.syncedLines.isNotEmpty()) {
                                memoryCacheMutex.withLock {
                                    memoryCache[memKey] = result
                                }
                                cacheManager.saveLyricsSmart(
                                    key = diskKey,
                                    plain = data.plainLyrics,
                                    synced = syncedRaw,
                                    source = data.source,
                                    duration = durationSec,
                                    isSynced = true,
                                    qualityScore = quality,
                                    artist = artist,
                                    title = title
                                )
                                return@withContext Result.success(result)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 6. Ничего не нашли
        Result.failure(Exception("Lyrics not found"))
    }

    // Фоновая попытка апгрейда plain -> synced
    private suspend fun tryUpgradeToSynced(song: Song, memKey: String, diskKey: String) {
        try {
            val artist = song.artist?.trim() ?: ""
            val title = song.title.trim()
            val album = song.album?.trim()
            val durationSec = if (song.duration > 0) song.duration else null

            val result = withTimeoutOrNull(PREFETCH_TIMEOUT_MS) {
                fetchFromLrclib(artist, title, album, durationSec)
            } ?: return

            if (result.isSuccess) {
                val res = result.getOrNull() ?: return
                if (res.isSynced && res.qualityScore > 70) {
                    // Апгрейдим кэш если нашли более качественный синк
                    memoryCacheMutex.withLock {
                        val existing = memoryCache[memKey]
                        if (existing == null || res.qualityScore > existing.qualityScore) {
                            memoryCache[memKey] = res
                        }
                    }
                    cacheManager.saveLyricsSmart(
                        key = diskKey,
                        plain = res.plainLyrics,
                        synced = res.syncedRaw,
                        source = res.source,
                        duration = durationSec,
                        isSynced = true,
                        qualityScore = res.qualityScore,
                        artist = artist,
                        title = title
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Prefetch следующих треков в очереди - умная предзагрузка как в Spotify
     * Загружает тексты для следующих 2 треков в фоне, чтобы при переключении текст был мгновенно
     */
    fun prefetchNextTracks(songs: List<Song>) {
        if (songs.isEmpty()) return
        scope.launch {
            songs.take(2).forEach { song ->
                try {
                    val memKey = song.id
                    // Проверяем нужен ли префетч
                    val needsPrefetch = memoryCacheMutex.withLock {
                        val cached = memoryCache[memKey]
                        cached == null || (!cached.isSynced && !cached.isInstrumental && cached.qualityScore < 80)
                    }
                    if (needsPrefetch) {
                        // Проверяем диск тоже
                        val diskNeeds = cacheManager.prefetchLyricsCheck(memKey)
                        if (diskNeeds) {
                            getLyricsInternal(song, memKey, memKey, isPrefetch = true)
                        }
                    }
                } catch (_: Exception) {}
                // Небольшая задержка между префетчами чтобы не спамить API
                delay(500)
            }
        }
    }

    suspend fun getLyrics(artist: String, title: String, album: String? = null, durationSec: Int? = null): Result<LyricsResult> = withContext(Dispatchers.IO) {
        val cacheKey = "${artist}_${title}".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(100)
        memoryCacheMutex.withLock {
            memoryCache[cacheKey]?.let { return@withContext Result.success(it) }
        }

        try {
            val diskEntry = cacheManager.getLyricsSmart(cacheKey)
            if (diskEntry != null && (diskEntry.plain.isNotBlank() || diskEntry.synced.isNotBlank())) {
                val synced = if (diskEntry.synced.isNotBlank()) parseLrc(diskEntry.synced) else emptyList()
                val result = LyricsResult(
                    plainLyrics = diskEntry.plain.ifEmpty { null },
                    syncedLines = synced,
                    syncedRaw = diskEntry.synced.ifEmpty { null },
                    isSynced = synced.isNotEmpty(),
                    source = diskEntry.source,
                    qualityScore = diskEntry.qualityScore
                )
                memoryCacheMutex.withLock {
                    memoryCache[cacheKey] = result
                }
                return@withContext Result.success(result)
            }
        } catch (_: Exception) {}

        val result = withTimeoutOrNull(LRCLIB_TIMEOUT_MS) {
            fetchFromLrclib(artist, title, album, durationSec)
        } ?: return@withContext Result.failure(Exception("LRCLIB timeout 10s"))

        if (result.isSuccess) {
            val res = result.getOrNull()!!
            memoryCacheMutex.withLock {
                memoryCache[cacheKey] = res
            }
            cacheManager.saveLyricsSmart(
                key = cacheKey,
                plain = res.plainLyrics,
                synced = res.syncedRaw,
                source = res.source,
                duration = durationSec,
                isSynced = res.isSynced,
                qualityScore = res.qualityScore
            )
        }
        result
    }

    private suspend fun fetchFromLrclib(artist: String, title: String, album: String?, durationSec: Int?): Result<LyricsResult> = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext Result.failure(Exception("Empty artist/title"))
        val cleanArtist = cleanArtistName(artist)
        val cleanTitle = cleanTrackTitle(title)

        var response: com.sonicspot.player.data.api.LrclibResponse? = null

        // Попытка 1: точное совпадение с длительностью (самый качественный)
        try {
            response = if (durationSec != null) {
                lrclibApi.getLyricsByDuration(cleanArtist, cleanTitle, durationSec)
            } else {
                lrclibApi.getLyrics(cleanArtist, cleanTitle, null, null)
            }
            if (response != null && (!response.plainLyrics.isNullOrBlank() || !response.syncedLyrics.isNullOrBlank())) {
                return@withContext response.toResult(durationSec, exactMatch = true)
            }
        } catch (e: HttpException) {
            if (e.code() != 404) {}
        } catch (_: Exception) {}

        // Попытка 2: с альбомом
        try {
            response = lrclibApi.getLyrics(cleanArtist, cleanTitle, album, durationSec)
            if (response != null && (!response.plainLyrics.isNullOrBlank() || !response.syncedLyrics.isNullOrBlank())) {
                return@withContext response.toResult(durationSec, exactMatch = true)
            }
        } catch (_: Exception) {}

        // Попытка 3: без альбома и длительности
        try {
            response = lrclibApi.getLyrics(cleanArtist, cleanTitle, null, null)
            if (response != null && (!response.plainLyrics.isNullOrBlank() || !response.syncedLyrics.isNullOrBlank())) {
                return@withContext response.toResult(durationSec, exactMatch = false)
            }
        } catch (_: Exception) {}

        // Попытка 4: поиск
        response = searchBestMatch(artist, title, cleanArtist, cleanTitle, durationSec)
        if (response != null) {
            return@withContext response.toResult(durationSec, exactMatch = false)
        }

        Result.failure(Exception("LRCLIB: not found"))
    }

    private fun com.sonicspot.player.data.api.LrclibResponse.toResult(durationSec: Int?, exactMatch: Boolean): Result<LyricsResult> {
        if (instrumental) {
            return Result.success(
                LyricsResult(
                    plainLyrics = "Инструментал",
                    syncedLines = listOf(LyricLine(0, "♪ Инструментал ♪")),
                    syncedRaw = null,
                    isSynced = false,
                    source = "lrclib",
                    qualityScore = 30,
                    isInstrumental = true
                )
            )
        }
        val synced = syncedLyrics?.let { parseLrc(it) } ?: emptyList()
        val plain = plainLyrics
        if (synced.isEmpty() && plain.isNullOrBlank()) return Result.failure(Exception("Empty lyrics"))

        val durationMatch = durationSec != null && duration != null && abs(duration!!.toInt() - durationSec) < 5
        val quality = LyricsQualityScorer.score(
            isSynced = synced.isNotEmpty(),
            source = "lrclib",
            durationMatch = durationMatch,
            exactMatch = exactMatch,
            isInstrumental = false
        )

        return Result.success(
            LyricsResult(
                plainLyrics = plain,
                syncedLines = synced,
                syncedRaw = syncedLyrics,
                isSynced = synced.isNotEmpty(),
                source = "lrclib",
                qualityScore = quality,
                isInstrumental = false
            )
        )
    }

    private suspend fun searchBestMatch(originalArtist: String, originalTitle: String, cleanArtist: String, cleanTitle: String, durationSec: Int?): com.sonicspot.player.data.api.LrclibResponse? {
        val queries = listOf("$cleanArtist $cleanTitle", "$originalArtist $originalTitle", cleanTitle)
        for (q in queries.distinct()) {
            try {
                val results = lrclibApi.searchLyrics(q)
                if (results.isEmpty()) continue
                val filtered = results.filter { r ->
                    val artistMatch = r.artistName.contains(cleanArtist, ignoreCase = true) || cleanArtist.contains(r.artistName, ignoreCase = true) || r.artistName.contains(originalArtist, ignoreCase = true)
                    val titleMatch = r.trackName.contains(cleanTitle, ignoreCase = true) || cleanTitle.contains(r.trackName, ignoreCase = true)
                    artistMatch || titleMatch
                }.ifEmpty { results }
                val withSynced = filtered.filter { !it.syncedLyrics.isNullOrBlank() }
                val best = if (durationSec != null) {
                    (if (withSynced.isNotEmpty()) withSynced else filtered).minByOrNull { abs((it.duration?.toInt() ?: 0) - durationSec) }
                } else {
                    withSynced.firstOrNull() ?: filtered.firstOrNull()
                }
                if (best != null && (!best.plainLyrics.isNullOrBlank() || !best.syncedLyrics.isNullOrBlank())) return best
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun cleanArtistName(artist: String): String = artist.split(Regex("""\s*(?:feat\.?|ft\.?|&|,|;| x | X )\s*"""), limit = 2).first().trim()
    private fun cleanTrackTitle(title: String): String = title.replace(Regex("""\s*[\(\[][^\)\]]*(?:remaster|live|edit|version|remix|acoustic|instrumental)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s*-\s*(?:remaster|live|edit|remix).*""", RegexOption.IGNORE_CASE), "").trim()

    fun parseLrc(lrcContent: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in lrcContent.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val matches = Regex("""\[(\d+):(\d+)(?:\.(\d+))?]""").findAll(trimmed)
            val text = trimmed.replace(Regex("""\[(\d+):(\d+)(?:\.(\d+))?]"""), "").trim()
            if (text.isEmpty()) continue
            for (match in matches) {
                try {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msPart = match.groupValues[3]
                    val ms = when {
                        msPart.isEmpty() -> 0L
                        msPart.length == 1 -> msPart.toLong() * 100
                        msPart.length == 2 -> msPart.toLong() * 10
                        msPart.length == 3 -> msPart.toLong()
                        else -> msPart.take(3).toLongOrNull() ?: 0L
                    }
                    lines.add(LyricLine(min * 60 * 1000 + sec * 1000 + ms, text))
                } catch (_: Exception) { continue }
            }
        }
        return lines.sortedBy { it.timestampMs }
    }

    fun findCurrentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timestampMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    suspend fun clearMemoryCache() {
        memoryCacheMutex.withLock {
            memoryCache.clear()
        }
        inFlightRequests.clear()
    }

    suspend fun clearCacheForSong(songId: String) {
        memoryCacheMutex.withLock {
            memoryCache.remove(songId)
            // Also remove any keys that contain songId as substring (artist_title keys)
            val keysToRemove = memoryCache.keys.filter { it.contains(songId) || songId.contains(it) }
            keysToRemove.forEach { memoryCache.remove(it) }
        }
        inFlightRequests.remove(songId)
        try {
            cacheManager.deleteLyricsCache(songId)
        } catch (_: Exception) {}
    }

    fun getMemoryCacheSize(): Int = memoryCache.size
}
