package com.sonicspot.player.ui.screens.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.DownloadStore
import com.sonicspot.player.data.local.PreferencesManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val playlist: PlaylistDetail? = null,
    val error: String? = null,
    val isPinned: Boolean = false,
    val visibleCount: Int = 20,
    /** Ник текущего пользователя: запасной вариант, если сервер не отдал владельца плейлиста. */
    val currentUsername: String = ""
) {
    /**
     * Автор плейлиста: владелец с сервера, иначе — текущий пользователь.
     * Пустая строка означает «автора нет» — тогда подпись не показываем.
     */
    val ownerName: String get() = playlist?.owner?.takeIf { it.isNotBlank() }
        ?: currentUsername.takeIf { it.isNotBlank() }
        ?: ""

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
    private val starredRepository: StarredRepository,
    private val downloadStore: DownloadStore,
    private val prefs: PreferencesManager
) : ViewModel() {

    val downloadedMap = downloadStore.downloaded
    val downloadingIds = downloadStore.inProgress

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    companion object {
        const val PAGE_SIZE = 20
    }

    init {
        viewModelScope.launch {
            try {
                val creds = prefs.getCredentials().first()
                _uiState.value = _uiState.value.copy(currentUsername = creds.username)
            } catch (_: Exception) {}
        }
    }

    fun loadPlaylist(id: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, visibleCount = PAGE_SIZE)
            val result = repository.getPlaylist(id, forceRefresh = forceRefresh)
            result.onSuccess { pl ->
                val isPinned = pinnedIds.value.contains(id)
                _uiState.value = PlaylistDetailUiState(
                    isLoading = false,
                    playlist = pl,
                    isPinned = isPinned,
                    visibleCount = PAGE_SIZE,
                    currentUsername = _uiState.value.currentUsername
                )
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
    /** Убирает трек из этого плейлиста и перечитывает состав. */
    fun removeSongFromPlaylist(songId: String) {
        val id = _uiState.value.playlist?.id ?: return
        viewModelScope.launch {
            repository.removeSongFromPlaylist(id, songId)
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось убрать трек")
                }
            loadPlaylist(id, forceRefresh = true)
        }
    }

    fun togglePin() {
        val id = _uiState.value.playlist?.id ?: return
        viewModelScope.launch { pinnedRepository.togglePin(id) }
    }

    /** Имя плейлиста + ссылка на него в Navidrome (копируется кнопкой «Поделиться»). */
    fun shareText(): String {
        val pl = _uiState.value.playlist ?: return ""
        val url = repository.getPlaylistShareUrl(pl.id) ?: return pl.name
        return "${pl.name}\n$url"
    }

    /** Скачивает все треки плейлиста (уже скачанные пропускаются — идемпотентно). */
    fun downloadAll() {
        val songs = _uiState.value.playlist?.entry ?: return
        viewModelScope.launch {
            songs.forEach { song ->
                try { downloadStore.download(song) } catch (_: Exception) {}
            }
        }
    }

    /** Удаляет скачанное этого плейлиста — файлы стираются, память освобождается. */
    fun removeDownloads() {
        val songs = _uiState.value.playlist?.entry ?: return
        viewModelScope.launch {
            songs.forEach { song ->
                try { downloadStore.delete(song.id) } catch (_: Exception) {}
            }
        }
    }

    fun renamePlaylist(newName: String) {
        val id = _uiState.value.playlist?.id ?: return
        viewModelScope.launch {
            try { repository.renamePlaylist(id, newName) } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(playlist = _uiState.value.playlist?.copy(name = newName))
        }
    }

    fun deletePlaylist(onDeleted: () -> Unit) {
        val id = _uiState.value.playlist?.id ?: return
        viewModelScope.launch {
            repository.deletePlaylist(id)
            onDeleted()
        }
    }
}
