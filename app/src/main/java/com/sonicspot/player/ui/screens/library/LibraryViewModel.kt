package com.sonicspot.player.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.DownloadStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
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
    // Альбомы подгружаются страницами с сервера, поэтому счётчик «сколько показать» им больше
    // не нужен — показывается всё, что уже загружено. Артисты и избранное приходят одним
    // списком, для них обрезка на клиенте остаётся.
    val albumsEndReached: Boolean = false,
    val isLoadingMoreAlbums: Boolean = false,
    val visibleArtistCount: Int = 20,
    val visibleStarredCount: Int = 20,
    val musicFolders: List<MusicFolder> = emptyList(),
    val selectedFolderId: Int? = null,
    /** Как пользователь предпочитает переключать библиотеки: [PreferencesManager.LIBRARY_SWITCHER_MENU] или [PreferencesManager.LIBRARY_SWITCHER_BUTTONS]. */
    val librarySwitcherMode: String = PreferencesManager.LIBRARY_SWITCHER_BUTTONS
) {
    val pinnedPlaylists: List<Playlist> get() = playlists.filter { pinnedIds.contains(it.id) }
    val publicPlaylists: List<Playlist> get() = playlists.filter { it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val privatePlaylists: List<Playlist> get() = playlists.filter { !it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val excludedPlaylist: Playlist? get() = playlists.find { it.name == DislikedRepository.EXCLUDED_PLAYLIST_NAME }

    val visibleAlbums get() = albums
    val visibleArtists get() = artists.take(visibleArtistCount)
    val visibleStarred get() = starredSongs.take(visibleStarredCount)

    val hasMoreAlbums get() = !albumsEndReached
    val hasMoreArtists get() = artists.size > visibleArtistCount
    val hasMoreStarred get() = starredSongs.size > visibleStarredCount

    val selectedFolderName: String? get() = musicFolders.find { it.id == selectedFolderId }?.name

    /** Переключать библиотеки нужно только когда их больше одной. */
    private val hasSwitchableFolders: Boolean get() = musicFolders.size > 1

    /** Режим «меню»: чип в шапке, открывающий список библиотек. */
    val showLibraryMenuChip: Boolean get() = librarySwitcherMode == PreferencesManager.LIBRARY_SWITCHER_MENU && hasSwitchableFolders

    /** Режим «кнопки»: строка чипов «Все библиотеки» + папки под шапкой. */
    val showLibraryButtonsRow: Boolean get() = librarySwitcherMode != PreferencesManager.LIBRARY_SWITCHER_MENU && hasSwitchableFolders
}

@Immutable
data class LibrarySearchState(
    val isActive: Boolean = false,
    val query: String = "",
    val isSearching: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList()
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val pinnedRepository: PinnedRepository,
    private val starredRepository: StarredRepository,
    private val prefs: PreferencesManager,
    private val downloadStore: DownloadStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedAlbumIds = pinnedRepository.pinnedAlbumsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val downloadedCount = downloadStore.downloaded.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
            prefs.librarySwitcherModeFlow.collect { mode ->
                _uiState.value = _uiState.value.copy(librarySwitcherMode = mode)
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

        // Прогрессивная отрисовка. Раньше четыре запроса запускались параллельно, но состояние
        // обновлялось один раз — после await() всех четырёх. Экран therefore ждал самый
        // медленный ответ: даже если альбомы пришли за 300 мс, а избранное тянется 3 с,
        // пользователь три секунды смотрел на пустоту во всех вкладках.
        // Теперь каждая часть обновляет состояние сама, по мере готовности.
        var pending = 4
        fun sectionDone() {
            if (--pending == 0) {
                _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
            }
        }

        resetAlbumPaging()
        viewModelScope.launch {
            val list = repository.getArtists(forceRefresh = isRefresh).getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(artists = list)
            sectionDone()
        }
        viewModelScope.launch {
            val page = repository.getAlbums("alphabeticalByName", ALBUM_PAGE_SIZE, offset = 0, forceRefresh = isRefresh)
                .getOrDefault(emptyList())
            albumOffset = page.size
            albumsEndReached = page.size < ALBUM_PAGE_SIZE
            _uiState.value = _uiState.value.copy(albums = page, albumsEndReached = albumsEndReached)
            sectionDone()
        }
        viewModelScope.launch {
            val list = repository.getPlaylists(forceRefresh = isRefresh).getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(playlists = list)
            sectionDone()
        }
        viewModelScope.launch {
            val starred = repository.getStarred(forceRefresh = isRefresh).getOrNull()
            _uiState.value = _uiState.value.copy(starredSongs = starred?.song ?: emptyList())
            sectionDone()
        }

        _uiState.value = _uiState.value.copy(
            pinnedIds = pinnedIds.value,
            pinnedAlbumIds = pinnedAlbumIds.value,
            currentUsername = currentUsername,
            musicFolders = folders,
            selectedFolderId = selectedFolderId
        )

        try {
            dislikedRepository.cleanupDuplicateExcludedPlaylists()
            dislikedRepository.syncFromServer()
        } catch (_: Exception) {}
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
        /** Сколько альбомов просим у сервера за раз. */
        const val ALBUM_PAGE_SIZE = 50
    }

    // ===== Альбомы: настоящая постраничная загрузка =====
    // Было: getAlbums(size = 100) тянул сотню альбомов сразу и показывал из них 20. То есть
    // платили за 100 — трафик, разбор JSON, обложки, — а видели 20, а остальные 80 догружались
    // по кнопке уже бесплатно, но всё равно уже были скачаны.
    // Стало: первая страница при открытии, дальше — по кнопке с реальным offset.
    private var albumOffset = 0
    private var albumsEndReached = false
    private var albumsLoading = false

    private fun resetAlbumPaging() {
        albumOffset = 0
        albumsEndReached = false
        albumsLoading = false
    }

    fun loadMoreAlbums() {
        if (albumsLoading || albumsEndReached) return
        albumsLoading = true
        _uiState.value = _uiState.value.copy(isLoadingMoreAlbums = true)
        viewModelScope.launch {
            val page = repository
                .getAlbums("alphabeticalByName", ALBUM_PAGE_SIZE, offset = albumOffset)
                .getOrDefault(emptyList())
            albumOffset += page.size
            // Сервер вернул меньше, чем просили — больше страниц нет.
            if (page.size < ALBUM_PAGE_SIZE) albumsEndReached = true
            val merged = _uiState.value.albums + page
            // На всякий случай убираем дубликаты: сервер мог сдвинуть выдачу между страницами.
            val distinct = merged.distinctBy { it.id }
            _uiState.value = _uiState.value.copy(
                albums = distinct,
                albumsEndReached = albumsEndReached,
                isLoadingMoreAlbums = false
            )
            albumsLoading = false
        }
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

    // ---------- Поиск треков и альбомов по медиатеке ----------
    private val _searchState = MutableStateFlow(LibrarySearchState())
    val searchState: StateFlow<LibrarySearchState> = _searchState.asStateFlow()
    private var searchJob: Job? = null

    fun setSearchActive(active: Boolean) {
        _searchState.value = if (active) _searchState.value.copy(isActive = true) else LibrarySearchState()
    }

    fun onSearchQueryChange(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _searchState.value = _searchState.value.copy(isSearching = false, songs = emptyList(), albums = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            _searchState.value = _searchState.value.copy(isSearching = true)
            delay(350) // дебаунс — не долбить сервер на каждую букву
            val result = repository.search(q, forceRefresh = true)
            val found = result.getOrNull()
            _searchState.value = _searchState.value.copy(
                isSearching = false,
                songs = found?.song ?: emptyList(),
                albums = found?.album ?: emptyList()
            )
        }
    }

    /** Создание плейлиста: isPublic=false — личный, true — публичный. */
    fun createPlaylist(name: String, isPublic: Boolean, onDone: (Playlist?) -> Unit = {}) {
        viewModelScope.launch {
            val created = repository.createPlaylist(name, isPublic).getOrNull()
            try { load(isRefresh = true) } catch (_: Exception) {}
            onDone(created)
        }
    }
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
