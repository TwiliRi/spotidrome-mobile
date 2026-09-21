package com.sonicspot.player.ui.screens.favorites

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
data class FavoritesUiState(
    val isLoading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val visibleCount: Int = 20,
    val error: String? = null
) {
    val visibleSongs get() = songs.take(visibleCount)
    val totalCount get() = songs.size
    val hasMore get() = songs.size > visibleCount
    val remainingCount get() = songs.size - visibleCount
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val starredRepository: StarredRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    companion object {
        const val PAGE_SIZE = 20
    }

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = repository.getStarred()
                val starred = result.getOrNull()
                val songs = starred?.song ?: emptyList()
                // Фильтруем исключенные? Нет, в любимых показываем все кроме исключенных? Показываем все, но помечаем дизлайкнутые
                _uiState.value = FavoritesUiState(isLoading = false, songs = songs, visibleCount = PAGE_SIZE)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadMore() {
        val cur = _uiState.value
        if (cur.hasMore) {
            _uiState.value = cur.copy(visibleCount = (cur.visibleCount + PAGE_SIZE).coerceAtMost(cur.songs.size))
        }
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
    fun playSongs(index: Int) {
        val songs = _uiState.value.songs
        playerManager.playSongs(songs, index)
    }
    fun playAll() = playSongs(0)
    fun shufflePlay() {
        val songs = _uiState.value.songs.shuffled()
        if (songs.isNotEmpty()) playerManager.playSongs(songs, 0)
    }
    fun toggleDislike(songId: String) { viewModelScope.launch { dislikedRepository.toggleDislike(songId) } }
    fun toggleLike(songId: String) {
        viewModelScope.launch {
            val result = starredRepository.toggleLike(songId)
            // Если удалили лайк в экране Любимых - сразу убираем из списка для мгновенной реакции как в Spotify
            if (result.isSuccess && result.getOrNull() == false) {
                _uiState.value = _uiState.value.copy(songs = _uiState.value.songs.filterNot { it.id == songId })
            } else if (result.isSuccess) {
                // Если добавили - синхронизируем
                try { starredRepository.syncFromServer() } catch (_: Exception) {}
            }
        }
    }

    fun refreshLiked() {
        viewModelScope.launch {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
            load()
        }
    }
}
