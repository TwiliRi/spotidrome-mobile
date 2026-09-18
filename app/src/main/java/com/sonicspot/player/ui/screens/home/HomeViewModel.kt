package com.sonicspot.player.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.CacheManager
import com.sonicspot.player.data.local.CachedAlbum
import com.sonicspot.player.data.local.CachedArtist
import com.sonicspot.player.data.local.CachedHomeData
import com.sonicspot.player.data.local.CachedPlaylist
import com.sonicspot.player.data.local.CachedSong
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Artist
import com.sonicspot.player.data.model.MusicFolder
import com.sonicspot.player.data.model.Playlist
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.PinnedRepository
import com.sonicspot.player.data.repository.StarredRepository
import com.sonicspot.player.debug.PerformanceTracer
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isFromCache: Boolean = false,
    val recentAlbums: List<Album> = emptyList(),
    val newestAlbums: List<Album> = emptyList(),
    val randomSongs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val pinnedIds: Set<String> = emptySet(),
    val pinnedAlbumIds: Set<String> = emptySet(),
    val error: String? = null,
    val musicFolders: List<MusicFolder> = emptyList(),
    val selectedFolderId: Int? = null
) {
    val pinnedPlaylists: List<Playlist> get() = playlists.filter { pinnedIds.contains(it.id) }
    val publicPlaylists: List<Playlist> get() = playlists.filter { it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val privatePlaylists: List<Playlist> get() = playlists.filter { !it.public && !pinnedIds.contains(it.id) && it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
    val selectedFolderName: String? get() = musicFolders.find { it.id == selectedFolderId }?.name
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MusicRepository,
    val playerManager: PlayerManager,
    private val dislikedRepository: DislikedRepository,
    private val pinnedRepository: PinnedRepository,
    private val starredRepository: StarredRepository,
    private val cacheManager: CacheManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val likedIds = starredRepository.likedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinnedAlbumIds = pinnedRepository.pinnedAlbumsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // FIX: Все IO в IO диспетчере, чтобы не блокировать MAIN и не скипать кадры
        // Логи показали cache_read 299ms + decode 279ms (1072 артистов 140KB) + Skipped 43 frames + Davey 1094ms + 809ms
        // Причина Davey: _uiState.value = fromCache с 1072 артистами триггерит тяжелую композицию на MAIN
        viewModelScope.launch(Dispatchers.IO) {
            PerformanceTracer.start("home_init")
            PerformanceTracer.start("refreshCredentials")
            repository.refreshCredentialsCache()
            PerformanceTracer.end("refreshCredentials")

            PerformanceTracer.start("cache_read")
            val cached = cacheManager.getHomeCache()
            val cacheReadMs = PerformanceTracer.end("cache_read")
            val report = PerformanceTracer.lastHomeReport ?: PerformanceTracer.newHomeReport()
            report.cacheReadMs = cacheReadMs
            report.cacheHit = cached != null

            withContext(Dispatchers.Main) {
                if (cached != null) {
                    val fromCache = withContext(Dispatchers.IO) {
                        PerformanceTracer.start("cache_toUiState")
                        val ui = cached.toUiState(isLoading = false, isFromCache = true, pinnedIds = emptySet(), pinnedAlbumIds = emptySet())
                        val dur = PerformanceTracer.end("cache_toUiState")
                        // Логируем если toUiState тяжелый из-за 1072 артистов
                        if (dur > 50) {
                            PerformanceTracer.log("cache_toUiState", "SLOW ${dur}ms artists=${ui.artists.size} random=${ui.randomSongs.size} - trimming needed!")
                        }
                        ui
                    }
                    _uiState.value = fromCache
                    PerformanceTracer.log("home_init", "Cache HIT, showing instantly, isFresh=${cacheManager.isHomeCacheFresh()} artists=${fromCache.artists.size} random=${fromCache.randomSongs.size}")
                    if (cacheManager.isHomeCacheFresh()) {
                        launch(Dispatchers.IO) { loadData(fromCache = true) }
                        PerformanceTracer.end("home_init")
                        return@withContext
                    }
                } else {
                    PerformanceTracer.log("home_init", "Cache MISS, loading from network")
                }
                launch(Dispatchers.IO) { loadData(fromCache = cached != null) }
                PerformanceTracer.end("home_init")
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PerformanceTracer.start("init_folders")
                val folders = repository.getMusicFolders().getOrDefault(emptyList())
                val selectedId = repository.getSelectedMusicFolderId()
                PerformanceTracer.end("init_folders")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(musicFolders = folders, selectedFolderId = selectedId)
                }
            } catch (_: Exception) {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { starredRepository.syncFromServer() } catch (_: Exception) {}
        }
    }

    // LRU cache for cover URLs to avoid string concat 59x per frame
    private val coverUrlCache = mutableMapOf<String, String?>()
    private val coverUrlCacheLock = Any()

    fun getCoverUrl(coverArtId: String?, size: Int = 300): String? {
        if (coverArtId == null) return null
        val key = "${coverArtId}-${size}"
        synchronized(coverUrlCacheLock) {
            coverUrlCache[key]?.let { return it }
        }
        val url = repository.getCoverArtUrl(coverArtId, size)
        synchronized(coverUrlCacheLock) {
            if (coverUrlCache.size > 500) coverUrlCache.clear()
            coverUrlCache[key] = url
        }
        return url
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

    fun selectMusicFolder(folderId: Int?) {
        viewModelScope.launch {
            repository.setSelectedMusicFolderId(folderId)
            _uiState.value = _uiState.value.copy(selectedFolderId = folderId, isLoading = true)
            withContext(Dispatchers.IO) { cacheManager.clearHomeCache() }
            loadData(isRefresh = true)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadData(isRefresh = true)
        }
    }

    private suspend fun loadData(fromCache: Boolean = false, isRefresh: Boolean = false) {
        val report = PerformanceTracer.lastHomeReport ?: PerformanceTracer.newHomeReport()

        withContext(Dispatchers.Main) {
            if (isRefresh) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            } else if (!fromCache) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
        }

        try {
            // FIX: Плавная загрузка секциями как в Spotify, а не одной пачкой
            // Было: все 4 запроса параллельно, потом один _uiState.value = interimState -> одна большая рекомпозиция 6 секций сразу
            // Стало: каждая секция обновляет UI сразу как пришла -> 4 маленькие рекомпозиции по 1 секции
            // Логи показывали critical_path 117ms на MAIN из-за одной большой пачки
            withContext(Dispatchers.IO) {
                PerformanceTracer.start("critical_path")
                PerformanceTracer.start("loadData_total")

                // Запускаем все 4 запроса параллельно
                val foldersDeferred = async {
                    PerformanceTracer.start("getMusicFolders")
                    val res = repository.getMusicFolders()
                    report.foldersMs = PerformanceTracer.end("getMusicFolders")
                    res
                }
                val recentDeferred = async {
                    PerformanceTracer.start("getRecentAlbums")
                    val res = repository.getAlbums("recent", 12)
                    report.recentMs = PerformanceTracer.end("getRecentAlbums")
                    res
                }
                val newestDeferred = async {
                    PerformanceTracer.start("getNewestAlbums")
                    val res = repository.getAlbums("newest", 12)
                    report.newestMs = PerformanceTracer.end("getNewestAlbums")
                    res
                }
                val playlistsDeferred = async {
                    PerformanceTracer.start("getPlaylists")
                    val res = repository.getPlaylists()
                    report.playlistsMs = PerformanceTracer.end("getPlaylists")
                    res
                }
                val selectedIdDeferred = async {
                    repository.getSelectedMusicFolderId()
                }

                // Плавно обновляем UI по мере готовности каждой секции (как Spotify)
                // Каждая секция - отдельная маленькая рекомпозиция, а не одна большая на 64 элемента

                // 1. Папки - самые быстрые (29ms), обновляем сразу
                try {
                    val folders = foldersDeferred.await().getOrDefault(emptyList())
                    val selId = selectedIdDeferred.await()
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            musicFolders = folders,
                            selectedFolderId = selId
                        )
                    }
                    PerformanceTracer.log("progressive", "folders ${folders.size} loaded, UI updated")
                } catch (_: Exception) {}

                // 2. Плейлисты - вторые по скорости (35ms)
                try {
                    val playlists = playlistsDeferred.await().getOrDefault(emptyList())
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            playlists = playlists,
                            pinnedIds = pinnedIds.value,
                            pinnedAlbumIds = pinnedAlbumIds.value,
                            isLoading = false,
                            isFromCache = false,
                            isRefreshing = false
                        )
                    }
                    PerformanceTracer.log("progressive", "playlists ${playlists.size} loaded, UI updated")
                } catch (_: Exception) {}

                // 3. Recent альбомы (53ms)
                try {
                    val recent = recentDeferred.await().getOrDefault(emptyList())
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            recentAlbums = recent,
                            isLoading = false,
                            isFromCache = false,
                            isRefreshing = false
                        )
                    }
                    PerformanceTracer.log("progressive", "recent ${recent.size} loaded, UI updated")
                } catch (_: Exception) {}

                // 4. Newest альбомы (54ms) - самые медленные из критичных
                try {
                    val newest = newestDeferred.await().getOrDefault(emptyList())
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            newestAlbums = newest,
                            isLoading = false,
                            isFromCache = false,
                            isRefreshing = false
                        )
                    }
                    PerformanceTracer.log("progressive", "newest ${newest.size} loaded, UI updated")
                } catch (_: Exception) {}

                report.totalMs = PerformanceTracer.end("critical_path")

                // Сохраняем кэш после всех критичных секций (уже тримнутый до 6+6+10)
                try {
                    PerformanceTracer.start("cache_save")
                    PerformanceTracer.start("toCachedData")
                    val currentForCache = _uiState.value
                    val cachedData = currentForCache.toCachedData()
                    val trimmed = cacheManager.trimForCache(cachedData)
                    PerformanceTracer.end("toCachedData")
                    cacheManager.saveHomeCache(trimmed)
                    report.cacheSaveMs = PerformanceTracer.end("cache_save")
                    PerformanceTracer.log("progressive", "cache saved ${trimmed.recentAlbums.size}+${trimmed.newestAlbums.size}+${trimmed.playlists.size}")
                } catch (_: Exception) {
                    try { PerformanceTracer.end("cache_save") } catch (_: Exception) {}
                }

                PerformanceTracer.end("loadData_total")
            }

            // Догружаем тяжелое в фоне с задержкой чтобы не мешать скроллу
            // FIX #3: getArtists только если кэш не свежий + delay 2000ms (был 600ms и всегда)
            // Было: 6 запросов данных (4 критичных + random + artists) + 2 sync = 8
            // Стало: 5 критичных (4 + random) когда кэш свежий, 6 когда не свежий но artists с задержкой 2с
            // Ожидаемый эффект: -1 тяжелый запрос на старте, -300ms конкуренция за сеть, -GC от 1072 артистов

            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                PerformanceTracer.start("getRandomSongs")
                try {
                    val randomRaw = repository.getRandomSongs(20).getOrDefault(emptyList())
                    report.randomMs = PerformanceTracer.end("getRandomSongs")
                    val disliked = dislikedIds.value
                    val random = if (disliked.isNotEmpty()) randomRaw.filterNot { disliked.contains(it.id) } else randomRaw
                    val trimmedRandom = random.take(20)
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(randomSongs = trimmedRandom)
                    }
                    PerformanceTracer.log("progressive", "random ${trimmedRandom.size} loaded")
                } catch (_: Exception) {
                    PerformanceTracer.end("getRandomSongs")
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                // FIX: artists только если кэш не свежий или пустой, и с большой задержкой
                val isCacheFresh = cacheManager.isHomeCacheFresh()
                val hasCachedArtists = _uiState.value.artists.isNotEmpty()
                if (isCacheFresh && hasCachedArtists) {
                    PerformanceTracer.log("getArtists", "SKIP - cache fresh and has ${hasCachedArtists} artists")
                    return@launch
                }
                kotlinx.coroutines.delay(2000)
                PerformanceTracer.start("getArtists")
                try {
                    val artists = repository.getArtists().getOrDefault(emptyList())
                    report.artistsMs = PerformanceTracer.end("getArtists")
                    val trimmedArtists = artists.take(30)
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(artists = trimmedArtists)
                    }
                    PerformanceTracer.log("progressive", "artists ${trimmedArtists.size} loaded")
                } catch (_: Exception) {
                    PerformanceTracer.end("getArtists")
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(1000)
                PerformanceTracer.start("dislikedSync")
                try {
                    val res = dislikedRepository.syncFromServer()
                    report.dislikedSyncMs = PerformanceTracer.end("dislikedSync")
                    PerformanceTracer.log("dislikedSync", "result=$res ${report.dislikedSyncMs}ms")
                } catch (e: Exception) {
                    PerformanceTracer.end("dislikedSync")
                }
                if (com.sonicspot.player.BuildConfig.DEBUG) {
                    android.util.Log.d("SonicLag", report.toLog())
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(1500)
                PerformanceTracer.start("starredSync")
                try {
                    starredRepository.syncFromServer()
                    report.starredSyncMs = PerformanceTracer.end("starredSync")
                } catch (_: Exception) {
                    PerformanceTracer.end("starredSync")
                }
            }

        } catch (e: Exception) {
            try { PerformanceTracer.end("loadData_total") } catch (_: Exception) {}
            try { PerformanceTracer.end("critical_path") } catch (_: Exception) {}
            try { PerformanceTracer.end("cache_save") } catch (_: Exception) {}
            PerformanceTracer.log("loadData", "FAILED ${e.message}")
            withContext(Dispatchers.Main) {
                if (_uiState.value.recentAlbums.isEmpty() && _uiState.value.newestAlbums.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false, error = e.message)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
                }
            }
        }
    }

    private fun CachedHomeData.toUiState(isLoading: Boolean, isFromCache: Boolean, pinnedIds: Set<String>, pinnedAlbumIds: Set<String>): HomeUiState {
        // FIX: Тримим даже из кэша - логи показали 1072 артистов в кэше -> Davey 1094ms + Skipped 43 frames + GC 10MB
        return HomeUiState(
            isLoading = isLoading,
            isFromCache = isFromCache,
            recentAlbums = recentAlbums.take(12).map { Album(id = it.id, name = it.name, artist = it.artist, artistId = it.artistId, coverArt = it.coverArt, songCount = it.songCount, year = it.year) },
            newestAlbums = newestAlbums.take(12).map { Album(id = it.id, name = it.name, artist = it.artist, artistId = it.artistId, coverArt = it.coverArt, songCount = it.songCount, year = it.year) },
            randomSongs = randomSongs.take(20).map { Song(id = it.id, title = it.title, artist = it.artist, album = it.album, albumId = it.albumId, artistId = it.artistId, coverArt = it.coverArt, duration = it.duration) },
            artists = artists.take(30).map { Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount) },
            playlists = playlists.take(20).map { Playlist(id = it.id, name = it.name, songCount = it.songCount, public = it.public, owner = it.owner, coverArt = it.coverArt) },
            pinnedIds = pinnedIds,
            pinnedAlbumIds = pinnedAlbumIds
        )
    }

    private fun HomeUiState.toCachedData(): CachedHomeData {
        // FIX: Тримим перед сохранением - было 1072 артиста -> 140KB -> 279ms decode
        return CachedHomeData(
            timestamp = System.currentTimeMillis(),
            recentAlbums = recentAlbums.take(6).map { CachedAlbum(it.id, it.name, it.artist, it.artistId, it.coverArt, it.songCount, it.year) },
            newestAlbums = newestAlbums.take(6).map { CachedAlbum(it.id, it.name, it.artist, it.artistId, it.coverArt, it.songCount, it.year) },
            randomSongs = randomSongs.take(6).map { CachedSong(it.id, it.title, it.artist, it.album, it.albumId, it.artistId, it.coverArt, it.duration) },
            artists = artists.take(10).map { CachedArtist(it.id, it.name, it.coverArt, it.albumCount) },
            playlists = playlists.take(10).map { CachedPlaylist(it.id, it.name, it.songCount, it.public, it.owner, it.coverArt) }
        )
    }
}
