package com.sonicspot.player.data.repository

import com.sonicspot.player.data.api.AuthInterceptor
import com.sonicspot.player.data.api.Credentials
import com.sonicspot.player.data.api.NavidromeApi
import com.sonicspot.player.data.api.NavidromeNativeApi
import com.sonicspot.player.data.api.NativeApiFactory
import com.sonicspot.player.data.api.NativeLibrary
import com.sonicspot.player.data.api.NativeLoginRequest
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.*
import com.sonicspot.player.util.CoverArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    private val api: NavidromeApi,
    private val nativeApi: NavidromeNativeApi,
    private val nativeApiFactory: NativeApiFactory,
    private val prefs: PreferencesManager,
    private val authInterceptor: AuthInterceptor
) {
    private var cachedCredentials: Credentials? = null
    private var cachedMusicFolders: List<MusicFolder> = emptyList()
    private var cachedNativeLibraries: List<NativeLibrary> = emptyList()
    private var nativeToken: String? = null
    private val nativeTokenMutex = Mutex()
    private val libraryCache = mutableMapOf<String, Pair<Int, String>>() // songId -> (libraryId, libraryName)

    suspend fun refreshCredentialsCache() {
        val creds = prefs.getCredentials().first()
        cachedCredentials = creds
        // FIX: Обновляем кэш в AuthInterceptor чтобы не делать DataStore read каждый запрос
        authInterceptor.updateCache(creds)
    }

    // При явном обновлении (pull-to-refresh) OkHttp обязан не брать ответ из дискового кэша,
    // а пойти в сеть. null -> Retrofit не добавит заголовок вовсе, и кэш работает как обычно.
    private fun cacheControl(forceRefresh: Boolean): String? = if (forceRefresh) "no-cache" else null

    fun getStreamUrl(songId: String): String {
        val cred = cachedCredentials ?: return ""
        return "${cred.serverUrl}/rest/stream.view?id=$songId&u=${cred.username}&t=${cred.token}&s=${cred.salt}&v=1.16.1&c=Spotidrome&f=json"
    }

    // FIX: default 300 вместо 500 - большинство UI 44-88dp, только большие плееры 500-600 явно запрашивают больше
    fun getCoverArtUrl(coverArtId: String?, size: Int = 300): String? {
        if (coverArtId == null) return null
        val cred = cachedCredentials ?: return null
        // Из сети просим только один из двух размеров (CoverArt.LIST / CoverArt.LARGE).
        // Остальное доделает Coil: он декодирует под конкретный sizePx элемента UI, а на диске
        // и в сети обложка лежит в одном экземпляре на корзину вместо восьми.
        val px = CoverArt.bucket(size)
        return "${cred.serverUrl}/rest/getCoverArt.view?id=$coverArtId&size=$px&u=${cred.username}&t=${cred.token}&s=${cred.salt}&v=1.16.1&c=Spotidrome"
    }

    fun getServerUrl(): String? = cachedCredentials?.serverUrl

    fun getArtistShareUrl(artistId: String): String? {
        val base = cachedCredentials?.serverUrl ?: return null
        return "$base/app/#/artist/$artistId/show"
    }

    fun getAlbumShareUrl(albumId: String): String? {
        val base = cachedCredentials?.serverUrl ?: return null
        return "$base/app/#/album/$albumId/show"
    }

    fun getPlaylistShareUrl(playlistId: String): String? {
        val base = cachedCredentials?.serverUrl ?: return null
        return "$base/app/#/playlist/$playlistId/show"
    }

    suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.updatePlaylist(playlistId = playlistId, name = newName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSongShareUrl(songId: String): String? {
        val base = cachedCredentials?.serverUrl ?: return null
        return "$base/app/#/song/$songId/show"
    }

    val selectedMusicFolderIdFlow: Flow<Int?> = prefs.selectedMusicFolderIdFlow

    suspend fun getSelectedMusicFolderId(): Int? = prefs.selectedMusicFolderIdFlow.first()

    suspend fun setSelectedMusicFolderId(id: Int?) {
        prefs.setSelectedMusicFolderId(id)
    }

    suspend fun getMusicFolders(): Result<List<MusicFolder>> = withContext(Dispatchers.IO) {
        try {
            val res = api.getMusicFolders()
            val folders = res.subsonicResponse.musicFolders?.musicFolder ?: emptyList()
            cachedMusicFolders = folders
            Result.success(folders)
        } catch (e: Exception) {
            if (cachedMusicFolders.isNotEmpty()) Result.success(cachedMusicFolders)
            else Result.failure(e)
        }
    }

    fun getCachedMusicFolders(): List<MusicFolder> = cachedMusicFolders

    fun getMusicFolderName(folderId: Int?): String? {
        if (folderId == null) return null
        // Сначала ищем в native кэше (более точный путь), потом в subsonic
        cachedNativeLibraries.find { it.id == folderId }?.let { return it.name }
        return cachedMusicFolders.find { it.id == folderId }?.name
    }

    // ==================== NATIVE API - для точного определения библиотеки трека ====================
    private suspend fun ensureNativeToken(): String? = nativeTokenMutex.withLock {
        if (!nativeToken.isNullOrBlank()) return nativeToken

        try {
            val creds = prefs.getCredentials().first()
            if (creds.rawPassword.isBlank() || creds.serverUrl.isBlank()) return null

            val freshApi = nativeApiFactory.create(creds.serverUrl)
            val resp = freshApi.login(NativeLoginRequest(creds.username, creds.rawPassword))
            if (resp.token.isNotBlank()) {
                nativeToken = resp.token
                return resp.token
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun getNativeApiWithToken(): Pair<NavidromeNativeApi, String>? {
        val token = ensureNativeToken() ?: return null
        return try {
            val creds = prefs.getCredentials().first()
            val freshApi = nativeApiFactory.create(creds.serverUrl)
            Pair(freshApi, "Bearer $token")
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getNativeLibraries(): Result<List<NativeLibrary>> = withContext(Dispatchers.IO) {
        try {
            val (api, token) = getNativeApiWithToken() ?: return@withContext Result.failure(Exception("No native token"))
            val libs = api.getLibraries(token)
            cachedNativeLibraries = libs
            Result.success(libs)
        } catch (e: Exception) {
            // Если 401 - пробуем перелогиниться
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                nativeTokenMutex.withLock { nativeToken = null }
                try {
                    val (api, token) = getNativeApiWithToken() ?: return@withContext Result.failure(e)
                    val libs = api.getLibraries(token)
                    cachedNativeLibraries = libs
                    Result.success(libs)
                } catch (e2: Exception) {
                    if (cachedNativeLibraries.isNotEmpty()) Result.success(cachedNativeLibraries)
                    else Result.failure(e2)
                }
            } else {
                if (cachedNativeLibraries.isNotEmpty()) Result.success(cachedNativeLibraries)
                else Result.failure(e)
            }
        }
    }

    suspend fun getLibraryForSongId(songId: String): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        // Проверяем кэш
        libraryCache[songId]?.let { return@withContext Result.success(it) }

        try {
            val (api, token) = getNativeApiWithToken() ?: return@withContext Result.failure(Exception("No native token"))
            val song = api.getSong(songId, token)
            val libId = song.libraryId
            var libName = song.libraryName

            // Если libraryName не пришло, ищем в кэше либ
            if (libName.isNullOrBlank()) {
                if (cachedNativeLibraries.isEmpty()) {
                    try {
                        val libs = api.getLibraries(token)
                        cachedNativeLibraries = libs
                    } catch (_: Exception) {}
                }
                libName = cachedNativeLibraries.find { it.id == libId }?.name
                    ?: cachedMusicFolders.find { it.id == libId }?.name
                    ?: "Библиотека $libId"
            }

            val result = Pair(libId, libName ?: "Библиотека $libId")
            libraryCache[songId] = result
            Result.success(result)
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) {
                nativeTokenMutex.withLock { nativeToken = null }
            }
            Result.failure(e)
        }
    }

    suspend fun getLibraryNameForSong(song: Song): String? {
        // 1. Пробуем точное определение через native API (Navidrome libraryId)
        try {
            val libResult = getLibraryForSongId(song.id)
            if (libResult.isSuccess) {
                return libResult.getOrNull()?.second
            }
        } catch (_: Exception) {}

        // 2. Если native не доступен, пробуем через альбом (альбомы привязаны к одной библиотеке в Navidrome)
        // Но subsonic не дает libraryId, поэтому fallback:
        // 3. Используем выбранную пользователем библиотеку как подсказку
        val selectedId = try { getSelectedMusicFolderId() } catch (_: Exception) { null }
        val selectedName = getMusicFolderName(selectedId)
        if (selectedName != null) return selectedName

        // 4. Fallback: пытаемся угадать по path если там есть имя библиотеки
        val path = song.path
        if (!path.isNullOrBlank()) {
            // Если в кэше есть библиотеки, проверяем содержит ли path имя библиотеки
            for (folder in cachedNativeLibraries) {
                if (path.contains(folder.name, ignoreCase = true) || path.contains(folder.path, ignoreCase = true)) {
                    return folder.name
                }
            }
            for (folder in cachedMusicFolders) {
                if (path.contains(folder.name, ignoreCase = true)) {
                    return folder.name
                }
            }
            // Последний fallback - первый сегмент пути
            val firstSegment = path.split("/").firstOrNull()?.takeIf { it.isNotBlank() }
            if (firstSegment != null && firstSegment.length < 30) return firstSegment
        }

        return selectedName
    }

    suspend fun getLibraryPathForSong(song: Song): String? {
        // Возвращает путь библиотеки если есть
        try {
            val libResult = getLibraryForSongId(song.id)
            if (libResult.isSuccess) {
                val libId = libResult.getOrNull()?.first
                return cachedNativeLibraries.find { it.id == libId }?.path
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun ping(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = api.ping()
            if (res.subsonicResponse.status == "ok") Result.success(Unit)
            else Result.failure(Exception(res.subsonicResponse.error?.message ?: "Ping failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtists(musicFolderId: Int? = null, forceRefresh: Boolean = false): Result<List<Artist>> = withContext(Dispatchers.IO) {
        try {
            val folderId = musicFolderId ?: getSelectedMusicFolderId()
            val res = api.getArtists(folderId, cacheControl(forceRefresh))
            val list = res.subsonicResponse.artists?.index?.flatMap { it.artist } ?: emptyList()
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArtist(id: String, forceRefresh: Boolean = false): Result<ArtistDetail> = withContext(Dispatchers.IO) {
        try {
            val res = api.getArtist(id, cacheControl(forceRefresh))
            res.subsonicResponse.artist?.let { Result.success(it) } ?: Result.failure(Exception("Artist not found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbum(id: String, forceRefresh: Boolean = false): Result<AlbumDetail> = withContext(Dispatchers.IO) {
        try {
            val res = api.getAlbum(id, cacheControl(forceRefresh))
            res.subsonicResponse.album?.let { Result.success(it) } ?: Result.failure(Exception("Album not found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbums(type: String = "newest", size: Int = 50, offset: Int = 0, musicFolderId: Int? = null, forceRefresh: Boolean = false): Result<List<Album>> = withContext(Dispatchers.IO) {
        try {
            val folderId = musicFolderId ?: getSelectedMusicFolderId()
            val res = api.getAlbumList2(type, size, offset, folderId, cacheControl(forceRefresh))
            Result.success(res.subsonicResponse.albumList2?.album ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun search(query: String, musicFolderId: Int? = null, forceRefresh: Boolean = false): Result<SearchResult3> = withContext(Dispatchers.IO) {
        try {
            val folderId = musicFolderId ?: getSelectedMusicFolderId()
            val res = api.search3(query, musicFolderId = folderId, cacheControl = cacheControl(forceRefresh))
            Result.success(res.subsonicResponse.searchResult3 ?: SearchResult3())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylists(forceRefresh: Boolean = false): Result<List<Playlist>> = withContext(Dispatchers.IO) {
        try {
            val res = api.getPlaylists(cacheControl(forceRefresh))
            Result.success(res.subsonicResponse.playlists?.playlist ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaylist(id: String, forceRefresh: Boolean = false): Result<PlaylistDetail> = withContext(Dispatchers.IO) {
        try {
            val res = api.getPlaylist(id, cacheControl(forceRefresh))
            res.subsonicResponse.playlist?.let { Result.success(it) } ?: Result.failure(Exception("Playlist not found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlaylist(name: String, isPublic: Boolean = false): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val res = api.createPlaylist(name, public = isPublic)
            res.subsonicResponse.playlist?.let {
                // Страховка видимости: дублируем public через updatePlaylist — переживает
                // серверы, которые проигнорировали бы public в createPlaylist.
                if (isPublic) {
                    try { api.updatePlaylist(playlistId = it.id, public = true) } catch (_: Exception) {}
                }
                Result.success(Playlist(id = it.id, name = it.name, songCount = it.songCount, duration = it.duration, public = isPublic))
            } ?: run {
                val playlists = api.getPlaylists().subsonicResponse.playlists?.playlist ?: emptyList()
                playlists.find { it.name == name }?.let { Result.success(it) } ?: Result.failure(Exception("Failed to create playlist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addToPlaylist(playlistId: String, songId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.updatePlaylist(playlistId = playlistId, songIdToAdd = songId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, songIndex: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.updatePlaylist(playlistId = playlistId, songIndexToRemove = songIndex)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.deletePlaylist(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRandomSongs(size: Int = 50, musicFolderId: Int? = null): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val folderId = musicFolderId ?: getSelectedMusicFolderId()
            val res = api.getRandomSongs(size, musicFolderId = folderId)
            Result.success(res.subsonicResponse.randomSongs?.song ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStarred(forceRefresh: Boolean = false): Result<StarredContainer> = withContext(Dispatchers.IO) {
        try {
            val res = api.getStarred(cacheControl(forceRefresh))
            Result.success(res.subsonicResponse.starred ?: StarredContainer())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSimilarSongs(songId: String, count: Int = 20): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val res = api.getSimilarSongs(songId, count)
            val songs = res.subsonicResponse.similarSongs?.song ?: res.subsonicResponse.similarSongs2?.song ?: emptyList()
            Result.success(songs)
        } catch (e: Exception) {
            try {
                val random = api.getRandomSongs(count).subsonicResponse.randomSongs?.song ?: emptyList()
                Result.success(random)
            } catch (e2: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getTopSongsForArtist(artistName: String, count: Int = 20): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val res = api.getTopSongs(artistName, count)
            Result.success(res.subsonicResponse.topSongs?.song ?: emptyList())
        } catch (e: Exception) {
            try {
                val search = api.search3(artistName, 0, 0, count).subsonicResponse.searchResult3?.song ?: emptyList()
                Result.success(search)
            } catch (e2: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getEmbeddedLyrics(songId: String): Result<LyricsData> = withContext(Dispatchers.IO) {
        try {
            val res = api.getLyricsBySongId(songId)
            val sub = res.subsonicResponse
            val structured = sub.structuredLyrics ?: sub.lyricsList?.structuredLyrics
            if (!structured.isNullOrEmpty()) {
                val best = structured.firstOrNull { it.synced && it.line.isNotEmpty() } ?: structured.firstOrNull()
                if (best != null) {
                    val lines = best.line.map { LyricLineData(it.start, it.value) }
                    val plain = lines.joinToString("\n") { it.text }
                    return@withContext Result.success(LyricsData(plainLyrics = plain, syncedLines = lines, source = "embedded"))
                }
            }
            val lyrics = sub.lyrics
            if (lyrics != null) {
                val plain = lyrics.value
                val synced = lyrics.syncedStructure?.map { LyricLineData(it.start, it.value) } ?: emptyList()
                if (!plain.isNullOrBlank() || synced.isNotEmpty()) {
                    return@withContext Result.success(LyricsData(plainLyrics = plain, syncedLines = synced, source = "embedded"))
                }
            }
            Result.failure(Exception("No embedded lyrics"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLyricsByArtistTitle(artist: String, title: String): Result<LyricsData> = withContext(Dispatchers.IO) {
        try {
            val res = api.getLyrics(artist, title)
            val lyrics = res.subsonicResponse.lyrics
            if (lyrics != null && !lyrics.value.isNullOrBlank()) {
                val synced = lyrics.syncedStructure?.map { LyricLineData(it.start, it.value) } ?: emptyList()
                Result.success(LyricsData(plainLyrics = lyrics.value, syncedLines = synced, source = "server"))
            } else {
                Result.failure(Exception("No lyrics"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class LyricLineData(val timestampMs: Long, val text: String)
    data class LyricsData(val plainLyrics: String?, val syncedLines: List<LyricLineData>, val source: String)

    suspend fun star(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.star(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unstar(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.unstar(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun scrobble(id: String, submission: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            api.scrobble(id, submission)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Совместимость: старый вызов без параметра
    suspend fun scrobbleNowPlaying(id: String): Result<Unit> = scrobble(id, submission = false)

    suspend fun getPlayQueue(): Result<PlayQueueData> = withContext(Dispatchers.IO) {
        try {
            val res = api.getPlayQueue()
            res.subsonicResponse.playQueue?.let { Result.success(it) }
                ?: Result.failure(Exception("No playQueue"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePlayQueue(ids: List<String>, current: String?, position: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("rest/savePlayQueue.view?")
                if (ids.isNotEmpty()) {
                    append(ids.joinToString("&") { "id=${it}" })
                    if (current != null || position != null) append("&")
                }
                if (current != null) {
                    append("current=").append(current)
                    if (position != null) append("&")
                }
                if (position != null) {
                    append("position=").append(position)
                }
            }
            api.savePlayQueueDynamic(url)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
