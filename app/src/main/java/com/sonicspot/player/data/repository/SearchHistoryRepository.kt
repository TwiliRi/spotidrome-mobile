package com.sonicspot.player.data.repository

import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.SearchHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryRepository @Inject constructor(
    private val prefs: PreferencesManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val listSerializer = ListSerializer(SearchHistoryEntry.serializer())

    companion object {
        const val MAX_HISTORY = 50
        const val MIN_QUERY_LENGTH = 2
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
