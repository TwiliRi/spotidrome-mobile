package com.sonicspot.player.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Artist
import com.sonicspot.player.data.model.MusicFolder
import com.sonicspot.player.data.model.Playlist
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.PinnedRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val starredSongs: List<Song> = emptyList(),
    val pinnedIds: Set<String> = emptySet(),
    val pinnedAlbumIds: Set<String> = emptySet(),
    val currentUsername: String = "",
    val visibleAlbumCount: Int = 20,
    val visibleArtistCount: Int = 20,
    val visibleStarredCount: Int = 20,
    val musicFolders: List<MusicFolder> = emptyList(),
    val selectedFolderId: Int? = null
) {
    val pinnedPlaylists: List<Playlist> get() = playlists.filter { pinnedIds.contains(it.id) }
    val publicPlaylists: List<Playlist> get() = playlists.filter { it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val privatePlaylists: List<Playlist> get() = playlists.filter { !it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val excludedPlaylist: Playlist? get() = playlists.find { it.name == DislikedRepository.EXCLUDED_PLAYLIST_NAME }

    val visibleAlbums get() = albums.take(visibleAlbumCount)
    val visibleArtists get() = artists.take(visibleArtistCount)
    val visibleStarred get() = starredSongs.take(visibleStarredCount)

    val hasMoreAlbums get() = albums.size > visibleAlbumCount
    val hasMoreArtists get() = artists.size > visibleArtistCount
    val hasMoreStarred get() = starredSongs.size > visibleStarredCount

    val selectedFolderName: String? get() = musicFolders.find { it.id == selectedFolderId }?.name
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val pinnedRepository: PinnedRepository,
    private val starredRepository: StarredRepository,
    private val prefs: PreferencesManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedAlbumIds = pinnedRepository.pinnedAlbumsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            pinnedIds.collect { pinned ->
                _uiState.value = _uiState.value.copy(pinnedIds = pinned)
            }
        }
        viewModelScope.launch {
            pinnedAlbumIds.collect { pinned ->
                _uiState.value = _uiState.value.copy(pinnedAlbumIds = pinned)
            }
        }
        viewModelScope.launch {
            repository.selectedMusicFolderIdFlow.collect { folderId ->
                _uiState.value = _uiState.value.copy(selectedFolderId = folderId)
            }
        }
        viewModelScope.launch {
            // preload folders
            try {
                val foldersResult = repository.getMusicFolders()
                foldersResult.getOrNull()?.let { folders ->
                    _uiState.value = _uiState.value.copy(musicFolders = folders)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun load(isRefresh: Boolean = false) {
        if (isRefresh) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        } else {
            _uiState.value = _uiState.value.copy(isLoading = true)
        }
        val credentials = try { prefs.getCredentials().first() } catch (_: Exception) { null }
        val currentUsername = credentials?.username ?: ""

        // Load folders first
        val folders = try { repository.getMusicFolders().getOrDefault(emptyList()) } catch (_: Exception) { emptyList() }
        val selectedFolderId = try { repository.getSelectedMusicFolderId() } catch (_: Exception) { null }

        val artistsDef = viewModelScope.async { repository.getArtists() }
        val albumsDef = viewModelScope.async { repository.getAlbums("alphabeticalByName", 100) }
        val playlistsDef = viewModelScope.async { repository.getPlaylists() }
        val starredDef = viewModelScope.async { repository.getStarred() }

        val artists = artistsDef.await().getOrDefault(emptyList())
        val albums = albumsDef.await().getOrDefault(emptyList())
        val playlists = playlistsDef.await().getOrDefault(emptyList())
        val starred = starredDef.await().getOrNull()

        try {
            dislikedRepository.cleanupDuplicateExcludedPlaylists()
            dislikedRepository.syncFromServer()
        } catch (_: Exception) {}

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isRefreshing = false,
            artists = artists,
            albums = albums,
            playlists = playlists,
            starredSongs = starred?.song ?: emptyList(),
            pinnedIds = pinnedIds.value,
            pinnedAlbumIds = pinnedAlbumIds.value,
            currentUsername = currentUsername,
            musicFolders = folders,
            selectedFolderId = selectedFolderId
        )
    }

    fun refresh() {
        viewModelScope.launch { load(isRefresh = true) }
    }

    fun selectMusicFolder(folderId: Int?) {
        viewModelScope.launch {
            repository.setSelectedMusicFolderId(folderId)
            _uiState.value = _uiState.value.copy(selectedFolderId = folderId, isLoading = true)
            load()
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }

    fun loadMoreAlbums() {
        val cur = _uiState.value
        if (cur.hasMoreAlbums) _uiState.value = cur.copy(visibleAlbumCount = (cur.visibleAlbumCount + PAGE_SIZE).coerceAtMost(cur.albums.size))
    }

    fun loadMoreArtists() {
        val cur = _uiState.value
        if (cur.hasMoreArtists) _uiState.value = cur.copy(visibleArtistCount = (cur.visibleArtistCount + PAGE_SIZE).coerceAtMost(cur.artists.size))
    }

    fun loadMoreStarred() {
        val cur = _uiState.value
        if (cur.hasMoreStarred) _uiState.value = cur.copy(visibleStarredCount = (cur.visibleStarredCount + PAGE_SIZE).coerceAtMost(cur.starredSongs.size))
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
    fun playSongs(songs: List<Song>, index: Int) = playerManager.playSongs(songs, index)
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val result = repository.getAlbum(albumId)
            result.getOrNull()?.song?.let { songs ->
                if (songs.isNotEmpty()) playerManager.playSongs(songs, 0)
            }
        }
    }
    fun shuffleAlbum(albumId: String) {
        viewModelScope.launch {
            val result = repository.getAlbum(albumId)
            result.getOrNull()?.song?.let { songs ->
                if (songs.isNotEmpty()) playerManager.playSongs(songs.shuffled(), 0)
            }
        }
    }
    fun toggleDislike(songId: String) { viewModelScope.launch { dislikedRepository.toggleDislike(songId) } }
    fun toggleLike(songId: String) { viewModelScope.launch { starredRepository.toggleLike(songId) } }
    fun togglePin(playlistId: String) { viewModelScope.launch { pinnedRepository.togglePin(playlistId) } }
    fun toggleAlbumPin(albumId: String) { viewModelScope.launch { pinnedRepository.toggleAlbumPin(albumId) } }
    fun cleanupDuplicates() { viewModelScope.launch { dislikedRepository.cleanupDuplicateExcludedPlaylists(); load() } }

    init {
        viewModelScope.launch {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
        }
    }
}
