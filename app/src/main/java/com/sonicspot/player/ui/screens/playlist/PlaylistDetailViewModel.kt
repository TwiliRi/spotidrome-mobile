package com.sonicspot.player.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.PlaylistDetail
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.PinnedRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val playlist: PlaylistDetail? = null,
    val error: String? = null,
    val isPinned: Boolean = false,
    val visibleCount: Int = 20
) {
    val visibleSongs get() = playlist?.entry?.take(visibleCount) ?: emptyList()
    val hasMore: Boolean get() = (playlist?.entry?.size ?: 0) > visibleCount
    val remainingCount: Int get() = (playlist?.entry?.size ?: 0) - visibleCount
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val pinnedRepository: PinnedRepository,
    private val starredRepository: StarredRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    companion object {
        const val PAGE_SIZE = 20
    }

    fun loadPlaylist(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, visibleCount = PAGE_SIZE)
            val result = repository.getPlaylist(id)
            result.onSuccess { pl ->
                val isPinned = pinnedIds.value.contains(id)
                _uiState.value = PlaylistDetailUiState(isLoading = false, playlist = pl, isPinned = isPinned, visibleCount = PAGE_SIZE)
                // Если это плейлист исключенных - синхронизируем все его треки в локальный список исключенных
                if (pl.name == DislikedRepository.EXCLUDED_PLAYLIST_NAME) {
                    try { dislikedRepository.syncFromServer() } catch (_: Exception) {}
                }
            }.onFailure { e -> _uiState.value = _uiState.value.copy(isLoading = false, error = e.message) }
        }
        viewModelScope.launch {
            pinnedIds.collect { pinned ->
                val current = _uiState.value.playlist
                if (current != null) {
                    _uiState.value = _uiState.value.copy(isPinned = pinned.contains(current.id))
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val total = current.playlist?.entry?.size ?: 0
        if (current.visibleCount < total) {
            val newCount = (current.visibleCount + PAGE_SIZE).coerceAtMost(total)
            _uiState.value = current.copy(visibleCount = newCount)
        }
    }

    init {
        viewModelScope.launch {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
        }
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
    fun playSongs(startIndex: Int = 0) {
        val allSongs = _uiState.value.playlist?.entry ?: return
        // Играем весь плейлист, а не только видимые
        playerManager.playSongs(allSongs, startIndex)
    }
    fun playAll() = playSongs(0)
    fun shufflePlay() { val songs = _uiState.value.playlist?.entry?.shuffled() ?: return; playerManager.playSongs(songs, 0) }
    fun toggleDislike(songId: String) { viewModelScope.launch { dislikedRepository.toggleDislike(songId) } }
    fun toggleLike(songId: String) { viewModelScope.launch { starredRepository.toggleLike(songId) } }
    fun togglePin() {
        val id = _uiState.value.playlist?.id ?: return
        viewModelScope.launch { pinnedRepository.togglePin(id) }
    }
}
