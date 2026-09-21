package com.sonicspot.player.ui.screens.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.AlbumDetail
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
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

@Immutable
data class AlbumDetailUiState(
    val isLoading: Boolean = true,
    val album: AlbumDetail? = null,
    val isStarred: Boolean = false,
    val visibleCount: Int = 20,
    val error: String? = null
) {
    val visibleSongs: List<Song> get() = album?.song?.take(visibleCount) ?: emptyList()
    val hasMore: Boolean get() = (album?.song?.size ?: 0) > visibleCount
    val remainingCount: Int get() = (album?.song?.size ?: 0) - visibleCount
}

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val starredRepository: StarredRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    companion object {
        const val PAGE_SIZE = 20
    }

    fun loadAlbum(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, visibleCount = PAGE_SIZE)
            val result = repository.getAlbum(id)
            result.onSuccess { album ->
                _uiState.value = AlbumDetailUiState(isLoading = false, album = album, isStarred = false, visibleCount = PAGE_SIZE)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val total = current.album?.song?.size ?: 0
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
    fun playSongs(startIndex: Int) {
        val songs = _uiState.value.album?.song ?: return
        playerManager.playSongs(songs, startIndex)
    }
    fun playAll() = playSongs(0)
    fun toggleDislike(songId: String) { viewModelScope.launch { dislikedRepository.toggleDislike(songId) } }
    fun toggleLike(songId: String) { viewModelScope.launch { starredRepository.toggleLike(songId) } }
    fun toggleStar() {
        val album = _uiState.value.album ?: return
        viewModelScope.launch {
            if (_uiState.value.isStarred) repository.unstar(album.id) else repository.star(album.id)
            _uiState.value = _uiState.value.copy(isStarred = !_uiState.value.isStarred)
        }
    }
}
