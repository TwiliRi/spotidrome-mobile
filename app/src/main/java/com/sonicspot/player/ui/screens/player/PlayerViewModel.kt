package com.sonicspot.player.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Artist
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.LyricsRepository
import com.sonicspot.player.data.repository.LyricsResult
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistInfoState(
    val isLoading: Boolean = false,
    val artist: Artist? = null,
    val artistDetail: com.sonicspot.player.data.model.ArtistDetail? = null,
    val topSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList()
)

data class LibraryInfoState(
    val libraryName: String? = null,
    val libraryId: Int? = null,
    val libraryPath: String? = null,
    val songPath: String? = null,
    val selectedFolderName: String? = null,
    val isExact: Boolean = false // true если определено через native API Navidrome, false если fallback
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val repository: MusicRepository,
    private val dislikedRepository: DislikedRepository,
    private val starredRepository: StarredRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val sleepTimerState = playerManager.sleepTimerState.stateIn(viewModelScope, SharingStarted.Eagerly, com.sonicspot.player.player.SleepTimerState.Off)

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Loading)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _artistInfo = MutableStateFlow(ArtistInfoState())
    val artistInfo: StateFlow<ArtistInfoState> = _artistInfo.asStateFlow()

    private val _libraryInfo = MutableStateFlow(LibraryInfoState())
    val libraryInfo: StateFlow<LibraryInfoState> = _libraryInfo.asStateFlow()

    private var lyricsJob: Job? = null
    private var artistJob: Job? = null

    init {
        viewModelScope.launch {
            playerManager.currentSongFlow.collect { song ->
                song?.let {
                    fetchLyrics(it)
                    fetchArtistInfo(it)
                    updateLibraryInfo(it)
                    val upcoming = playerManager.upcomingQueue.value
                    if (upcoming.isNotEmpty()) {
                        lyricsRepository.prefetchNextTracks(upcoming)
                    }
                }
            }
        }
        viewModelScope.launch {
            playerManager.upcomingQueue.collect { upcoming ->
                if (upcoming.isNotEmpty() && playerManager.currentSongFlow.value != null) {
                    lyricsRepository.prefetchNextTracks(upcoming)
                }
            }
        }
        viewModelScope.launch {
            repository.selectedMusicFolderIdFlow.collect { folderId ->
                val selectedName = if (folderId != null) repository.getMusicFolderName(folderId) else null
                _libraryInfo.value = _libraryInfo.value.copy(selectedFolderName = selectedName ?: repository.getCachedMusicFolders().find { it.id == folderId }?.name)
                // refresh library name for current song
                playerManager.currentSongFlow.value?.let { updateLibraryInfo(it) }
            }
        }
        viewModelScope.launch {
            try {
                repository.getMusicFolders()
            } catch (_: Exception) {}
        }
    }

    private suspend fun updateLibraryInfo(song: Song) {
        var libraryName: String? = null
        var libraryId: Int? = null
        var libraryPath: String? = null
        var isExact = false

        // Пробуем точное определение через native API Navidrome
        try {
            val libResult = repository.getLibraryForSongId(song.id)
            if (libResult.isSuccess) {
                val (id, name) = libResult.getOrNull()!!
                libraryId = id
                libraryName = name
                isExact = true
                // Пытаемся получить путь библиотеки
                libraryPath = try {
                    repository.getNativeLibraries().getOrNull()?.find { it.id == id }?.path
                        ?: repository.getLibraryPathForSong(song)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {}

        // Fallback если native не сработал
        if (libraryName == null) {
            libraryName = try {
                repository.getLibraryNameForSong(song)
            } catch (_: Exception) { null }
        }

        val selectedFolderId = try { repository.getSelectedMusicFolderId() } catch (_: Exception) { null }
        val selectedFolderName = if (selectedFolderId != null) {
            repository.getMusicFolderName(selectedFolderId) ?: repository.getCachedMusicFolders().find { it.id == selectedFolderId }?.name
        } else null

        _libraryInfo.value = LibraryInfoState(
            libraryName = libraryName,
            libraryId = libraryId,
            libraryPath = libraryPath,
            songPath = song.path,
            selectedFolderName = selectedFolderName,
            isExact = isExact
        )
    }

    fun getCoverUrl(id: String?, size: Int = 500) = repository.getCoverArtUrl(id, size)

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun playNext() = playerManager.playNext()
    fun playPrevious() = playerManager.playPrevious()
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun toggleRepeat() = playerManager.toggleRepeat()
    fun toggleAutoDj() = playerManager.toggleAutoDj()
    fun seekTo(pos: Long) = playerManager.seekTo(pos)

    fun addToQueue(song: Song) = playerManager.addToQueue(song)
    fun addNext(song: Song) = playerManager.addNext(song)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
    fun moveQueueItem(from: Int, to: Int) = playerManager.moveQueueItem(from, to)
    fun clearQueue() = playerManager.clearQueue()
    fun playQueueIndex(index: Int) = playerManager.playQueueIndex(index)

    fun startTrackRadio(song: Song) = playerManager.startTrackRadio(song)
    fun startArtistRadio(artistName: String) = playerManager.startArtistRadio(artistName)

    fun toggleLike(songId: String) {
        viewModelScope.launch { starredRepository.toggleLike(songId) }
    }

    fun toggleDislike(songId: String) {
        viewModelScope.launch { dislikedRepository.toggleDislike(songId) }
    }

    fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)
    fun cancelSleepTimer() = playerManager.cancelSleepTimer()

    fun syncQueueFromServer() = playerManager.syncQueueFromServer()
    fun saveQueueToServer() = playerManager.saveQueueToServerNow()

    init {
        viewModelScope.launch {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
        }
    }

    fun fetchLyrics(song: Song) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _lyricsState.value = LyricsUiState.Loading

            if (song.title.isBlank()) {
                _lyricsState.value = LyricsUiState.Empty
                return@launch
            }

            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(11_000L) {
                    lyricsRepository.getLyricsForSong(song)
                }

                if (result == null) {
                    _lyricsState.value = LyricsUiState.Empty
                    return@launch
                }

                result.fold(
                    onSuccess = { lyrics ->
                        if (lyrics.syncedLines.isNotEmpty() || !lyrics.plainLyrics.isNullOrBlank()) {
                            _lyricsState.value = LyricsUiState.Success(lyrics)
                        } else {
                            _lyricsState.value = LyricsUiState.Empty
                        }
                    },
                    onFailure = { error ->
                        val msg = error.message ?: ""
                        if (msg.contains("not found", ignoreCase = true) ||
                            msg.contains("timeout", ignoreCase = true) ||
                            msg.contains("10s", ignoreCase = true) ||
                            msg.contains("Empty", ignoreCase = true)
                        ) {
                            _lyricsState.value = LyricsUiState.Empty
                        } else {
                            _lyricsState.value = LyricsUiState.Error(msg.ifEmpty { "Не удалось загрузить текст" })
                        }
                    }
                )
            } catch (e: Exception) {
                _lyricsState.value = LyricsUiState.Empty
            }
        }
    }

    fun fetchArtistInfo(song: Song) {
        artistJob?.cancel()
        artistJob = viewModelScope.launch {
            val artistName = song.artist ?: return@launch
            val artistId = song.artistId
            _artistInfo.value = ArtistInfoState(isLoading = true)

            try {
                val topSongsDeferred = repository.getTopSongsForArtist(artistName, 10)
                val artistDetailDeferred = if (artistId != null) {
                    repository.getArtist(artistId)
                } else null

                val topSongs = topSongsDeferred.getOrNull() ?: emptyList()
                val artistDetail = artistDetailDeferred?.getOrNull()

                val albums = artistDetail?.album?.take(10) ?: emptyList()

                _artistInfo.value = ArtistInfoState(
                    isLoading = false,
                    artistDetail = artistDetail,
                    topSongs = topSongs.filterNot { it.id == song.id },
                    albums = albums
                )
            } catch (e: Exception) {
                _artistInfo.value = ArtistInfoState(isLoading = false)
            }
        }
    }

    fun retryLyrics() {
        val song = playerManager.playerState.value.currentSong ?: return
        viewModelScope.launch {
            _lyricsState.value = LyricsUiState.Loading
            try {
                lyricsRepository.clearCacheForSong(song.id)
            } catch (_: Exception) {}
            fetchLyrics(song)
        }
    }

    fun forceRefreshLyrics() = retryLyrics()
}

sealed class LyricsUiState {
    object Loading : LyricsUiState()
    object Empty : LyricsUiState()
    data class Success(val result: LyricsResult) : LyricsUiState()
    data class Error(val message: String) : LyricsUiState()
}
