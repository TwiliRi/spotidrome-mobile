package com.sonicspot.player.ui.screens.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Artist
import com.sonicspot.player.data.model.SearchHistoryEntry
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.SearchHistoryRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
    val visibleSongCount: Int = 20,
    val visibleAlbumCount: Int = 20,
    val visibleArtistCount: Int = 20,
    val suggestions: List<SearchHistoryEntry> = emptyList()
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
    val visibleSongs get() = songs.take(visibleSongCount)
    val visibleAlbums get() = albums.take(visibleAlbumCount)
    val visibleArtists get() = artists.take(visibleArtistCount)
    val hasMoreSongs get() = songs.size > visibleSongCount
    val hasMoreAlbums get() = albums.size > visibleAlbumCount
    val hasMoreArtists get() = artists.size > visibleArtistCount
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val starredRepository: StarredRepository,
    private val searchHistoryRepository: SearchHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val searchHistory = searchHistoryRepository.historyFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val popularSearches = searchHistoryRepository.popularFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groupedHistory = searchHistory.map { searchHistoryRepository.groupedByTime(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    companion object {
        const val PAGE_SIZE = 20
    }

    init {
        viewModelScope.launch {
            queryFlow.debounce(400).distinctUntilChanged().collect { q ->
                if (q.length >= 2) {
                    performSearch(q)
                }
                // Обновляем подсказки из истории для любого ввода
                updateSuggestions(q)
            }
        }
        viewModelScope.launch {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
        }
    }

    private fun updateSuggestions(q: String) {
        viewModelScope.launch {
            searchHistoryRepository.getSuggestionsFlow(q).first().let { suggestions ->
                _uiState.value = _uiState.value.copy(suggestions = suggestions)
            }
        }
    }

    fun onQueryChange(q: String) {
        _uiState.value = _uiState.value.copy(query = q)
        queryFlow.value = q
        if (q.isBlank()) {
            _uiState.value = SearchUiState(query = "", suggestions = emptyList())
        }
    }

    fun onSearchSubmitted(query: String) {
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            viewModelScope.launch {
                searchHistoryRepository.addQuery(trimmed)
            }
        }
    }

    fun loadMoreSongs() {
        val cur = _uiState.value
        if (cur.hasMoreSongs) _uiState.value = cur.copy(visibleSongCount = (cur.visibleSongCount + PAGE_SIZE).coerceAtMost(cur.songs.size))
    }

    fun loadMoreAlbums() {
        val cur = _uiState.value
        if (cur.hasMoreAlbums) _uiState.value = cur.copy(visibleAlbumCount = (cur.visibleAlbumCount + PAGE_SIZE).coerceAtMost(cur.albums.size))
    }

    fun loadMoreArtists() {
        val cur = _uiState.value
        if (cur.hasMoreArtists) _uiState.value = cur.copy(visibleArtistCount = (cur.visibleArtistCount + PAGE_SIZE).coerceAtMost(cur.artists.size))
    }

    private suspend fun performSearch(q: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, visibleSongCount = PAGE_SIZE, visibleAlbumCount = PAGE_SIZE, visibleArtistCount = PAGE_SIZE)
        try {
            val result = repository.search(q)
            result.onSuccess { search ->
                val disliked = dislikedIds.value
                val filteredSongs = if (disliked.isNotEmpty()) search.song.filterNot { disliked.contains(it.id) } else search.song
                _uiState.value = _uiState.value.copy(isLoading = false, artists = search.artist, albums = search.album, songs = filteredSongs)
                // Умное сохранение: сохраняем только если есть результаты или запрос достаточно специфичный
                if (filteredSongs.isNotEmpty() || search.artist.isNotEmpty() || search.album.isNotEmpty()) {
                    searchHistoryRepository.addQuery(q)
                } else if (q.length >= 4) {
                    // Даже если ничего не найдено, но запрос длинный - сохраняем (пользователь пытался)
                    searchHistoryRepository.addQuery(q)
                }
            }.onFailure { _uiState.value = _uiState.value.copy(isLoading = false) }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun removeFromHistory(query: String) {
        viewModelScope.launch { searchHistoryRepository.removeQuery(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.clearAll() }
    }

    fun getCoverUrl(id: String?, size: Int = 300): String? = repository.getCoverArtUrl(id, size)
    fun playSongs(songs: List<Song>, index: Int) = playerManager.playSongs(songs, index)
    fun toggleDislike(songId: String) { viewModelScope.launch { dislikedRepository.toggleDislike(songId) } }
    fun toggleLike(songId: String) { viewModelScope.launch { starredRepository.toggleLike(songId) } }
}
