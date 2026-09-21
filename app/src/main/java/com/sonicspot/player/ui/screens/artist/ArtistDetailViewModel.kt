package com.sonicspot.player.ui.screens.artist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.ArtistDetail
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
data class ArtistDetailUiState(
    val isLoading: Boolean = true,
    val artist: ArtistDetail? = null,
    val topSongs: List<Song> = emptyList(),
    val isTopSongsLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val starredRepository: StarredRepository,
    private val dislikedRepository: DislikedRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun loadArtist(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.getArtist(id)
            result.onSuccess { artist ->
                _uiState.value = _uiState.value.copy(isLoading = false, artist = artist)
                // Load top songs in parallel
                loadTopSongs(artist.name)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private fun loadTopSongs(artistName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTopSongsLoading = true)
            val result = repository.getTopSongsForArtist(artistName, 30)
            result.onSuccess { songs ->
                _uiState.value = _uiState.value.copy(topSongs = songs, isTopSongsLoading = false)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isTopSongsLoading = false)
            }
        }
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)

    fun getServerUrl() = repository.getServerUrl()

    fun getArtistShareUrl(artistId: String) = repository.getArtistShareUrl(artistId) ?: "https://navidrome.org"
    fun getAlbumShareUrl(albumId: String) = repository.getAlbumShareUrl(albumId) ?: "https://navidrome.org"
    fun getSongShareUrl(songId: String) = repository.getSongShareUrl(songId) ?: "https://navidrome.org"

    fun playAll() {
        if (_uiState.value.topSongs.isNotEmpty()) {
            viewModelScope.launch {
                playerManager.playSongs(_uiState.value.topSongs, startIndex = 0)
            }
        } else {
            // Если топов нет - играем все треки из альбомов? Пока радио как fallback
            startArtistRadio()
        }
    }

    fun shufflePlay() {
        val songs = _uiState.value.topSongs
        if (songs.isNotEmpty()) {
            viewModelScope.launch {
                playerManager.playSongs(songs.shuffled(), startIndex = 0)
            }
        }
    }

    fun startArtistRadio() {
        val artistName = _uiState.value.artist?.name ?: return
        viewModelScope.launch {
            playerManager.startArtistRadio(artistName)
        }
    }

    fun playTopSongAt(index: Int) {
        val songs = _uiState.value.topSongs
        if (index in songs.indices) {
            viewModelScope.launch {
                playerManager.playSongs(songs, startIndex = index)
            }
        }
    }

    // ===== Новые функции для кнопок =====

    fun toggleLike(songId: String) {
        viewModelScope.launch { starredRepository.toggleLike(songId) }
    }

    fun toggleDislike(songId: String) {
        viewModelScope.launch { dislikedRepository.toggleDislike(songId) }
    }

    fun addToQueue(song: Song) = playerManager.addToQueue(song)
    fun addNext(song: Song) = playerManager.addNext(song)

    fun startTrackRadio(song: Song) = playerManager.startTrackRadio(song)

    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            try {
                val album = repository.getAlbum(albumId).getOrNull() ?: return@launch
                val songs = album.song
                if (songs.isNotEmpty()) {
                    playerManager.playSongs(songs, startIndex = 0)
                }
            } catch (_: Exception) {}
        }
    }

    fun addAlbumToQueue(albumId: String) {
        viewModelScope.launch {
            try {
                val album = repository.getAlbum(albumId).getOrNull() ?: return@launch
                album.song.forEach { playerManager.addToQueue(it) }
            } catch (_: Exception) {}
        }
    }

    fun shuffleAlbum(albumId: String) {
        viewModelScope.launch {
            try {
                val album = repository.getAlbum(albumId).getOrNull() ?: return@launch
                val songs = album.song
                if (songs.isNotEmpty()) playerManager.playSongs(songs.shuffled(), 0)
            } catch (_: Exception) {}
        }
    }
}
