package com.sonicspot.player.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// FIX: @Immutable для Compose - помогает компилятору понять что объекты стабильны
// Spotify использует immutable модели чтобы избежать лишних рекомпозиций
// Логи показали GC freed 10MB - много аллокаций из-за unstable моделей

// Subsonic API wrapper - Navidrome отдает "subsonic-response" с дефисом!
@Serializable
data class SubsonicResponse(
    @SerialName("subsonic-response")
    val subsonicResponse: SubsonicData
)

@Serializable
data class SubsonicData(
    val status: String = "ok",
    val version: String = "",
    val type: String? = null,
    val serverVersion: String? = null,
    val musicFolders: MusicFoldersContainer? = null,
    val artists: ArtistsContainer? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val albumList2: AlbumList2? = null,
    val searchResult3: SearchResult3? = null,
    val playlists: PlaylistsContainer? = null,
    val playlist: PlaylistDetail? = null,
    val randomSongs: RandomSongs? = null,
    val starred: StarredContainer? = null,
    val similarSongs: SimilarSongsContainer? = null,
    val similarSongs2: SimilarSongsContainer? = null,
    val topSongs: TopSongsContainer? = null,
    val lyrics: LyricsSubsonic? = null,
    val lyricsList: LyricsListContainer? = null,
    val structuredLyrics: List<StructuredLyrics>? = null,
    val playQueue: PlayQueueData? = null,
    val error: SubsonicError? = null,
    @SerialName("openSubsonic")
    val openSubsonic: Boolean? = null
)

@Serializable
data class SubsonicError(
    val code: Int = 0,
    val message: String = ""
)

@Serializable
data class ArtistsContainer(
    val index: List<ArtistIndex> = emptyList(),
    val ignoredArticles: String? = null
)

@Serializable
data class ArtistIndex(
    val name: String,
    val artist: List<Artist> = emptyList()
)

@Immutable
@Serializable
data class Artist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null
)

@Immutable
@Serializable
data class ArtistDetail(
    val id: String,
    val name: String,
    val album: List<Album> = emptyList(),
    val coverArt: String? = null,
    val albumCount: Int = 0
)

@Serializable
data class AlbumList2(
    val album: List<Album> = emptyList()
)

@Immutable
@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val playCount: Int = 0,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null
)

@Immutable
@Serializable
data class AlbumDetail(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<Song> = emptyList(),
    val playCount: Int = 0
)

@Immutable
@Serializable
data class Song(
    val id: String,
    val parent: String? = null,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val size: Long = 0,
    val bitRate: Int = 0,
    val path: String? = null,
    val playCount: Int = 0,
    val starred: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val isDir: Boolean = false,
    val discNumber: Int? = null
) {
    val isStarred: Boolean get() = starred != null
}

@Serializable
data class SearchResult3(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

@Serializable
data class PlaylistsContainer(
    val playlist: List<Playlist> = emptyList()
)

@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val public: Boolean = false,
    val owner: String? = null,
    val created: String? = null,
    val coverArt: String? = null
)

@Immutable
@Serializable
data class PlaylistDetail(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Int = 0,
    val entry: List<Song> = emptyList()
)

@Serializable
data class RandomSongs(
    val song: List<Song> = emptyList()
)

@Serializable
data class StarredContainer(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

@Serializable
data class SimilarSongsContainer(
    val song: List<Song> = emptyList()
)

@Serializable
data class TopSongsContainer(
    val song: List<Song> = emptyList()
)

@Immutable
@Serializable
data class MusicFolder(
    val id: Int,
    val name: String
)

@Serializable
data class MusicFoldersContainer(
    val musicFolder: List<MusicFolder> = emptyList()
)

@Serializable
data class LyricsSubsonic(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null,
    val offset: Int? = null,
    val syncedStructure: List<SyncedLyricStructure>? = null
)

@Serializable
data class SyncedLyricStructure(
    val start: Long = 0,
    val value: String = ""
)

@Serializable
data class LyricsListContainer(
    val structuredLyrics: List<StructuredLyrics> = emptyList()
)

@Serializable
data class StructuredLyrics(
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val lang: String? = null,
    val synced: Boolean = false,
    val line: List<StructuredLyricLine> = emptyList(),
    val offset: Int? = null
)

@Serializable
data class StructuredLyricLine(
    val start: Long = 0,
    val value: String = ""
)

// PlayQueue - синхронизация очереди между устройствами (Subsonic API)
@Serializable
data class PlayQueueData(
    val current: String? = null,
    val position: Long = 0L,
    val username: String? = null,
    val changed: String? = null,
    val changedBy: String? = null,
    val entry: List<Song> = emptyList()
)

// UI Models - оптимизированные для Compose
@JvmInline
value class CoverArtId(val value: String)

data class PlayerQueueItem(
    val song: Song,
    val index: Int
)
