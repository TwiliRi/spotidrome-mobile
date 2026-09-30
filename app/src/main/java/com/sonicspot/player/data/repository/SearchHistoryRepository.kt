package com.sonicspot.player.data.repository

import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.SearchHistoryEntry
import com.sonicspot.player.data.model.SearchListenEntry
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.player.PlayerManager
import com.sonicspot.player.util.buildArtistTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryRepository @Inject constructor(
    private val prefs: PreferencesManager,
    playerManager: PlayerManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val listSerializer = ListSerializer(SearchHistoryEntry.serializer())
    private val listensSerializer = ListSerializer(SearchListenEntry.serializer())

    companion object {
        const val MAX_HISTORY = 50
        const val MIN_QUERY_LENGTH = 2
        const val MAX_LISTENS = 30
        // Сколько времени ждём подтверждения «слушал» после перехода к исполнителю из поиска
        private const val ARTIST_CONFIRM_WINDOW_MS = 15 * 60_000L
    }

    val historyFlow: Flow<List<SearchHistoryEntry>> = prefs.searchHistoryJsonFlow
        .map { jsonString ->
            try {
                if (jsonString.isBlank() || jsonString == "[]") emptyList()
                else json.decodeFromString(listSerializer, jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        }
        .map { list ->
            list.sortedByDescending { it.lastUsed }
        }
        .distinctUntilChanged()

    val popularFlow: Flow<List<SearchHistoryEntry>> = historyFlow
        .map { list ->
            list.sortedWith(
                compareByDescending<SearchHistoryEntry> { it.count }
                    .thenByDescending { it.lastUsed }
            ).take(8)
        }

    // ==================== Прослушанное из поиска (треки и исполнители) ====================

    val listensFlow: Flow<List<SearchListenEntry>> = prefs.searchListensJsonFlow
        .map { jsonString ->
            try {
                if (jsonString.isBlank() || jsonString == "[]") emptyList()
                else json.decodeFromString(listensSerializer, jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        }
        .map { list -> list.sortedByDescending { it.timestamp } }
        .distinctUntilChanged()

    private val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Исполнитель, на страницу которого перешли из поиска: запись появится, только когда заиграет его трек
    @Volatile private var pendingArtist: SearchListenEntry? = null
    @Volatile private var pendingArtistSetAt = 0L

    init {
        // Подтверждение прослушивания: как только заиграл трек ожидаемого исполнителя — фиксируем запись
        listenScope.launch {
            playerManager.currentSongFlow.collect { song ->
                if (song != null) confirmPendingArtist(song)
            }
        }
    }

    /** Трек запущен из результатов поиска — фиксируем трек и его исполнителя как прослушанных */
    suspend fun recordSongPlayedFromSearch(song: Song) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val current = readListens()
            upsertListen(current, SearchListenEntry(
                kind = SearchListenEntry.KIND_SONG,
                id = song.id,
                title = song.title,
                subtitle = song.artist,
                coverArt = song.coverArt,
                artistId = song.artistId,
                albumId = song.albumId,
                timestamp = now
            ))
            val artistName = song.artist?.trim().orEmpty()
            if (artistName.isNotEmpty()) {
                upsertListen(current, SearchListenEntry(
                    kind = SearchListenEntry.KIND_ARTIST,
                    id = song.artistId?.takeIf { it.isNotBlank() } ?: SearchListenEntry.nameId(artistName),
                    title = artistName,
                    coverArt = song.coverArt,
                    timestamp = now
                ))
            }
            saveListens(current)
        } catch (_: Exception) {}
    }

    /** Переход к исполнителю из результатов поиска: подтвердим записью, когда заиграет его трек */
    fun markArtistOpenedFromSearch(artistId: String?, artistName: String, coverArt: String? = null) {
        val name = artistName.trim()
        if (name.isEmpty()) return
        pendingArtist = SearchListenEntry(
            kind = SearchListenEntry.KIND_ARTIST,
            id = artistId?.takeIf { it.isNotBlank() } ?: SearchListenEntry.nameId(name),
            title = name,
            coverArt = coverArt
        )
        pendingArtistSetAt = System.currentTimeMillis()
    }

    private fun confirmPendingArtist(playing: Song) {
        val pending = pendingArtist ?: return
        if (System.currentTimeMillis() - pendingArtistSetAt > ARTIST_CONFIRM_WINDOW_MS) {
            pendingArtist = null
            return
        }
        val idMatch = playing.artistId != null && playing.artistId == pending.id
        val nameMatch = playing.artist?.let { display ->
            display.trim().equals(pending.title, ignoreCase = true) ||
                buildArtistTokens(playing).any { it.name.trim().equals(pending.title, ignoreCase = true) }
        } == true
        if (idMatch || nameMatch) {
            pendingArtist = null
            val confirmed = pending.copy(timestamp = System.currentTimeMillis())
            listenScope.launch {
                try {
                    val current = readListens()
                    upsertListen(current, confirmed)
                    saveListens(current)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun readListens(): MutableList<SearchListenEntry> {
        val raw = prefs.searchListensJsonFlow.first()
        return try {
            if (raw.isBlank() || raw == "[]") mutableListOf()
            else json.decodeFromString(listensSerializer, raw).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private suspend fun saveListens(list: List<SearchListenEntry>) {
        val sorted = list.sortedByDescending { it.timestamp }.take(MAX_LISTENS)
        prefs.saveSearchListensJson(json.encodeToString(listensSerializer, sorted))
    }

    /** Обновляет существующую запись (kind + id) или добавляет новую */
    private fun upsertListen(list: MutableList<SearchListenEntry>, entry: SearchListenEntry) {
        val idx = list.indexOfFirst { it.kind == entry.kind && it.id.equals(entry.id, ignoreCase = true) }
        if (idx >= 0) list[idx] = entry else list.add(entry)
    }

    suspend fun addQuery(rawQuery: String) = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LENGTH) return@withContext
        if (query.all { !it.isLetterOrDigit() }) return@withContext

        try {
            val currentJson = prefs.searchHistoryJsonFlow.first()
            val currentList = try {
                if (currentJson.isBlank() || currentJson == "[]") mutableListOf()
                else json.decodeFromString(listSerializer, currentJson).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }

            val now = System.currentTimeMillis()
            val existingIndex = currentList.indexOfFirst { it.query.equals(query, ignoreCase = true) }

            if (existingIndex >= 0) {
                val existing = currentList[existingIndex]
                currentList[existingIndex] = existing.copy(
                    query = query,
                    count = existing.count + 1,
                    lastUsed = now,
                    timestamp = existing.timestamp
                )
            } else {
                currentList.add(0, SearchHistoryEntry(query = query, timestamp = now, lastUsed = now, count = 1))
            }

            val trimmed: List<SearchHistoryEntry> = if (currentList.size > MAX_HISTORY) {
                currentList.sortedWith(
                    compareByDescending<SearchHistoryEntry> { it.smartScore(now) }
                        .thenByDescending { it.lastUsed }
                ).take(MAX_HISTORY).sortedByDescending { it.lastUsed }
            } else currentList

            val newJson = json.encodeToString(listSerializer, trimmed)
            prefs.saveSearchHistoryJson(newJson)
        } catch (_: Exception) {}
    }

    suspend fun removeQuery(query: String) = withContext(Dispatchers.IO) {
        try {
            val currentJson = prefs.searchHistoryJsonFlow.first()
            val currentList = try {
                json.decodeFromString(listSerializer, currentJson).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.query.equals(query, ignoreCase = true) }
            val listToSave: List<SearchHistoryEntry> = currentList
            prefs.saveSearchHistoryJson(json.encodeToString(listSerializer, listToSave))
        } catch (_: Exception) {}
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.clearSearchHistory()
    }

    fun getSuggestionsFlow(currentInput: String): Flow<List<SearchHistoryEntry>> {
        return historyFlow.map { list ->
            val input = currentInput.trim()
            if (input.length < 1) return@map emptyList()
            val lower = input.lowercase()
            list.filter { it.query.lowercase().contains(lower) && !it.query.equals(input, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<SearchHistoryEntry> {
                        if (it.query.lowercase().startsWith(lower)) 1 else 0
                    }.thenByDescending { it.smartScore() }
                )
                .take(6)
        }
    }

    fun groupedByTime(history: List<SearchHistoryEntry>): Map<String, List<SearchHistoryEntry>> {
        val now = System.currentTimeMillis()
        val today = mutableListOf<SearchHistoryEntry>()
        val yesterday = mutableListOf<SearchHistoryEntry>()
        val thisWeek = mutableListOf<SearchHistoryEntry>()
        val older = mutableListOf<SearchHistoryEntry>()

        history.forEach { entry ->
            val diffDays = (now - entry.lastUsed) / 86_400_000
            when {
                diffDays < 1 -> today.add(entry)
                diffDays < 2 -> yesterday.add(entry)
                diffDays < 7 -> thisWeek.add(entry)
                else -> older.add(entry)
            }
        }

        val result = linkedMapOf<String, List<SearchHistoryEntry>>()
        if (today.isNotEmpty()) result["Сегодня"] = today
        if (yesterday.isNotEmpty()) result["Вчера"] = yesterday
        if (thisWeek.isNotEmpty()) result["На этой неделе"] = thisWeek
        if (older.isNotEmpty()) result["Давно"] = older
        return result
    }
}
