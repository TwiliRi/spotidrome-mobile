package com.sonicspot.player.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Playlist
import com.sonicspot.player.ui.components.*
import com.sonicspot.player.ui.theme.*
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRecentlyAddedClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()

    var selectedAlbumForMenu by remember { mutableStateOf<Album?>(null) }
    var selectedPlaylistForMenu by remember { mutableStateOf<Playlist?>(null) }
    var showAlbumSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var songToRemove by remember { mutableStateOf<com.sonicspot.player.data.model.Song?>(null) }

    BackHandler(enabled = showAlbumSheet || showPlaylistSheet) {
        if (showAlbumSheet) {
            showAlbumSheet = false
            selectedAlbumForMenu = null
        } else if (showPlaylistSheet) {
            showPlaylistSheet = false
            selectedPlaylistForMenu = null
        }
    }

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..22 -> "Добрый вечер"
            else -> "Доброй ночи"
        }
    }

    val quickAlbums by remember(state.recentAlbums, state.newestAlbums) {
        derivedStateOf {
            (state.recentAlbums.take(4) + state.newestAlbums.take(2)).distinctBy { it.id }.take(6)
        }
    }

    // FIX: derivedStateOf для плейлистов чтобы не фильтровать каждый рекомпоз
    // Логи показали критичный путь 734ms на MAIN с extra 650ms из-за фильтрации + Coil
    val pinnedPlaylists by remember(state.playlists, state.pinnedIds) {
        derivedStateOf { state.playlists.filter { state.pinnedIds.contains(it.id) } }
    }
    val publicPlaylists by remember(state.playlists, state.pinnedIds) {
        derivedStateOf { state.playlists.filter { it.public && !state.pinnedIds.contains(it.id) && it.name != com.sonicspot.player.data.repository.DislikedRepository.EXCLUDED_PLAYLIST_NAME } }
    }
    val privatePlaylists by remember(state.playlists, state.pinnedIds) {
        derivedStateOf { state.playlists.filter { !it.public && !state.pinnedIds.contains(it.id) && it.name != com.sonicspot.player.data.repository.DislikedRepository.EXCLUDED_PLAYLIST_NAME } }
    }

    val pullState = rememberPullToRefreshState()
    val backgroundBrush = remember {
        Brush.verticalGradient(colors = listOf(Color(0xFF2A2A2A), SpotifyColors.Black), startY = 0f, endY = 600f)
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(
        modifier = Modifier.fillMaxSize().background(backgroundBrush)
    ) {
        SpotifyPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() }
        ) {
            // Пауза загрузки обложек во время флинга = 60 fps на скролле.
            ProvidePauseImageLoadsDuringScroll(listState) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp),
            ) {
                item(key = "header", contentType = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).statusBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(SpotifyColors.Purple), contentAlignment = Alignment.Center) {
                                Text("T", color = SpotifyColors.Black, fontWeight = FontWeight.Bold)
                            }
                            if (state.isFromCache) {
                                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Bolt, null, tint = SpotifyColors.Green, modifier = Modifier.size(12.dp))
                                        Text("из кэша", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                                    }
                                }
                            }
                            if (state.musicFolders.isNotEmpty()) {
                                LibraryChip(folderName = state.selectedFolderName, onClick = { showFolderSheet = true })
                            }
                        }
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { onSettingsClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, null, tint = SpotifyColors.White, modifier = Modifier.size(22.dp))
                        }
                    }

                    if (state.musicFolders.size > 1) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all_libs") {
                                SpotifyFilterChip(text = "Все библиотеки", selected = state.selectedFolderId == null, onClick = { viewModel.selectMusicFolder(null) })
                            }
                            items(state.musicFolders, key = { it.id }) { folder ->
                                SpotifyFilterChip(text = folder.name, selected = state.selectedFolderId == folder.id, onClick = { viewModel.selectMusicFolder(folder.id) })
                            }
                        }
                    }
                }

                item(key = "greeting", contentType = "greeting") {
                    Spacer(Modifier.height(8.dp))
                    Text(text = greeting, style = SpotifyTextStyles.Greeting, color = SpotifyColors.White, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(16.dp))
                }

                // FIX: Плавная загрузка секциями как в Spotify - каждая секция появляется отдельно с шиммером
                // Было: все секции одной пачкой -> одна большая рекомпозиция 64 элемента -> лаг
                // Стало: секция за секцией, шиммер пока грузится

                // Quick access - появляется первым (из recent+newest)
                if (quickAlbums.isNotEmpty()) {
                    item(key = "quick", contentType = "quick") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (i in quickAlbums.indices step 2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    val first = quickAlbums.getOrNull(i)
                                    val second = quickAlbums.getOrNull(i + 1)
                                    if (first != null) {
                                        val coverUrl1 = remember(first.coverArt) { viewModel.getCoverUrl(first.coverArt, 112) }
                                        QuickAccessCard(
                                            title = first.name,
                                            coverUrl = coverUrl1,
                                            onClick = { onAlbumClick(first.id) },
                                            onLongClick = { selectedAlbumForMenu = first; showAlbumSheet = true },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (second != null) {
                                        val coverUrl2 = remember(second.coverArt) { viewModel.getCoverUrl(second.coverArt, 112) }
                                        QuickAccessCard(
                                            title = second.name,
                                            coverUrl = coverUrl2,
                                            onClick = { onAlbumClick(second.id) },
                                            onLongClick = { selectedAlbumForMenu = second; showAlbumSheet = true },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                } else if (state.isLoading) {
                    item(key = "quick_shimmer", contentType = "shimmer") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    ShimmerPlaceholder(modifier = Modifier.weight(1f).height(56.dp), cornerRadius = 4.dp)
                                    ShimmerPlaceholder(modifier = Modifier.weight(1f).height(56.dp), cornerRadius = 4.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // Плейлисты - появляются вторыми (35ms) - теперь через очередь PaginatedLazyRow
                // FIX: Показываем только первые 3 чтобы нормально выглядело, остальные по мере скролла
                if (pinnedPlaylists.isNotEmpty()) {
                    item(key = "pinned", contentType = "playlists") {
                        SectionHeaderModern(title = "Закрепленные")
                        PaginatedLazyRow(
                            items = pinnedPlaylists,
                            initialVisible = 3,
                            pageSize = 3,
                            key = { it.id },
                            itemContent = { playlist ->
                                val coverUrl = remember(playlist.coverArt) { viewModel.getCoverUrl(playlist.coverArt, 304) }
                                PlaylistCardModernWithPin(
                                    playlist = playlist,
                                    coverUrl = coverUrl,
                                    isPinned = true,
                                    isPublic = playlist.public,
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onPinClick = { selectedPlaylistForMenu = playlist; showPlaylistSheet = true }
                                )
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                if (privatePlaylists.isNotEmpty()) {
                    item(key = "private_pl", contentType = "playlists") {
                        SectionHeaderModern(title = "Твои плейлисты")
                        PaginatedLazyRow(
                            items = privatePlaylists,
                            initialVisible = 3,
                            pageSize = 3,
                            key = { it.id },
                            itemContent = { playlist ->
                                val coverUrl = remember(playlist.coverArt) { viewModel.getCoverUrl(playlist.coverArt, 304) }
                                PlaylistCardModernWithPin(
                                    playlist = playlist,
                                    coverUrl = coverUrl,
                                    isPinned = false,
                                    isPublic = false,
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onPinClick = { selectedPlaylistForMenu = playlist; showPlaylistSheet = true }
                                )
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                } else if (state.playlists.isEmpty() && state.isLoading) {
                    item(key = "private_pl_shimmer", contentType = "shimmer") {
                        SectionHeaderModern(title = "Твои плейлисты")
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(4, key = { "pl_shimmer_$it" }, contentType = { "shimmer" }) {
                                Column(modifier = Modifier.width(152.dp)) {
                                    ShimmerPlaceholder(modifier = Modifier.size(152.dp))
                                    Spacer(Modifier.height(8.dp))
                                    ShimmerPlaceholder(modifier = Modifier.fillMaxWidth().height(14.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // Общие плейлисты - главный пример из задачи: только 3 сразу, остальные по скроллу
                if (publicPlaylists.isNotEmpty()) {
                    item(key = "public_pl", contentType = "playlists") {
                        SectionHeaderModern(title = "Общие плейлисты")
                        PaginatedLazyRow(
                            items = publicPlaylists,
                            initialVisible = 3,
                            pageSize = 4,
                            key = { it.id },
                            itemContent = { playlist ->
                                val coverUrl = remember(playlist.coverArt) { viewModel.getCoverUrl(playlist.coverArt, 304) }
                                PlaylistCardModernWithPin(
                                    playlist = playlist,
                                    coverUrl = coverUrl,
                                    isPinned = false,
                                    isPublic = true,
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onPinClick = { selectedPlaylistForMenu = playlist; showPlaylistSheet = true }
                                )
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // Недавно добавленные - третья секция (53-54ms) - тоже через очередь, 3 сразу
                if (state.newestAlbums.isNotEmpty()) {
                    item(key = "newest", contentType = "albums") {
                        SectionHeaderModern(title = "Недавно добавленные", onSeeAll = onRecentlyAddedClick)
                        PaginatedLazyRow(
                            items = state.newestAlbums,
                            initialVisible = 3,
                            pageSize = 3,
                            key = { it.id },
                            itemContent = { album ->
                                val coverUrl = remember(album.coverArt) { viewModel.getCoverUrl(album.coverArt, 304) }
                                AlbumCardModern(
                                    album = album,
                                    coverUrl = coverUrl,
                                    onClick = { onAlbumClick(album.id) },
                                    onLongClick = { selectedAlbumForMenu = album; showAlbumSheet = true }
                                )
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                } else if (state.isLoading) {
                    item(key = "newest_shimmer", contentType = "shimmer") {
                        SectionHeaderModern(title = "Недавно добавленные")
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(5, key = { "newest_shimmer_$it" }, contentType = { "shimmer" }) {
                                Column(modifier = Modifier.width(152.dp)) {
                                    ShimmerPlaceholder(modifier = Modifier.size(152.dp))
                                    Spacer(Modifier.height(8.dp))
                                    ShimmerPlaceholder(modifier = Modifier.fillMaxWidth().height(14.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // Чтобы вернуться - появляется с задержкой 300ms, теперь тоже 3 сразу + очередь
                if (state.randomSongs.isNotEmpty()) {
                    item(key = "random", contentType = "songs") {
                        SectionHeaderModern(title = "Чтобы вернуться")
                        // Показываем только 3 сразу, остальные по кнопке "Показать еще" как в Spotify
                        var visibleRandom by remember(state.randomSongs.size) { mutableIntStateOf(3) }
                        val randomVisible by remember(state.randomSongs, visibleRandom) {
                            derivedStateOf { state.randomSongs.take(visibleRandom) }
                        }
                        Column {
                            randomVisible.forEachIndexed { idx, song ->
                                val isPlaying by remember(currentSongId, song.id) {
                                    derivedStateOf { currentSongId?.id == song.id }
                                }
                                val isLiked by remember(likedIds, song.id, song.isStarred) {
                                    derivedStateOf { likedIds.contains(song.id) || song.isStarred }
                                }
                                val isDisliked by remember(dislikedIds, song.id) {
                                    derivedStateOf { dislikedIds.contains(song.id) }
                                }
                                val coverUrl = remember(song.coverArt) { viewModel.getCoverUrl(song.coverArt, 88) }
                                SongRowModern(
                                    song = song,
                                    coverUrl = coverUrl,
                                    isPlaying = isPlaying,
                                    isLiked = isLiked,
                                    isDisliked = isDisliked,
                                    showCover = true,
                                    onClick = { viewModel.playSongs(state.randomSongs, idx) },
                                    onMore = {},
                                    onLike = {
                                        if (isLiked) songToRemove = song
                                        else viewModel.toggleLike(song.id)
                                    },
                                    onDislike = { viewModel.toggleDislike(song.id) }
                                )
                            }
                            if (visibleRandom < state.randomSongs.size) {
                                TextButton(
                                    onClick = {
                                        visibleRandom = (visibleRandom + 3).coerceAtMost(state.randomSongs.size)
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text("Показать еще ${state.randomSongs.size - visibleRandom}", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                } else if (!state.isLoading) {
                    item(key = "random_shimmer", contentType = "shimmer") {
                        SectionHeaderModern(title = "Чтобы вернуться")
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ShimmerPlaceholder(modifier = Modifier.size(44.dp), cornerRadius = 4.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ShimmerPlaceholder(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp))
                                        ShimmerPlaceholder(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // Любимые исполнители - появляется последним с задержкой 600ms, тоже 3 сразу
                if (state.artists.isNotEmpty()) {
                    item(key = "artists", contentType = "artists") {
                        SectionHeaderModern(title = "Любимые исполнители")
                        PaginatedLazyRow(
                            items = state.artists.take(15),
                            initialVisible = 3,
                            pageSize = 3,
                            key = { it.id },
                            itemContent = { artist ->
                                val coverUrl = remember(artist.coverArt) { viewModel.getCoverUrl(artist.coverArt, 240) }
                                ArtistCardModern(artist = artist, coverUrl = coverUrl, onClick = { onArtistClick(artist.id) })
                            }
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                } else if (!state.isLoading) {
                    item(key = "artists_shimmer", contentType = "shimmer") {
                        SectionHeaderModern(title = "Любимые исполнители")
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(3, key = { "artist_shimmer_$it" }, contentType = { "shimmer" }) {
                                Column(modifier = Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ShimmerPlaceholder(modifier = Modifier.size(120.dp), cornerRadius = 60.dp)
                                    Spacer(Modifier.height(8.dp))
                                    ShimmerPlaceholder(modifier = Modifier.width(80.dp).height(12.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
            } // ProvidePauseImageLoadsDuringScroll
        }

        if (showAlbumSheet) {
            AlbumOptionsBottomSheet(
                album = selectedAlbumForMenu,
                coverUrl = viewModel.getCoverUrl(selectedAlbumForMenu?.coverArt, 112),
                isPinned = pinnedAlbumIds.contains(selectedAlbumForMenu?.id),
                onDismiss = { showAlbumSheet = false; selectedAlbumForMenu = null },
                onPlay = { selectedAlbumForMenu?.let { viewModel.playAlbum(it.id) } },
                onShuffle = { selectedAlbumForMenu?.let { viewModel.shuffleAlbum(it.id) } },
                onGoToArtist = { selectedAlbumForMenu?.artistId?.let { onArtistClick(it) } },
                onPinToggle = { selectedAlbumForMenu?.let { viewModel.toggleAlbumPin(it.id) } },
                onAddToQueue = { }
            )
        }

        if (showPlaylistSheet) {
            PlaylistOptionsBottomSheet(
                playlist = selectedPlaylistForMenu,
                coverUrl = viewModel.getCoverUrl(selectedPlaylistForMenu?.coverArt, 112),
                isPinned = state.pinnedIds.contains(selectedPlaylistForMenu?.id),
                onDismiss = { showPlaylistSheet = false; selectedPlaylistForMenu = null },
                onPlay = { selectedPlaylistForMenu?.let { onPlaylistClick(it.id) } },
                onShuffle = { },
                onPinToggle = { selectedPlaylistForMenu?.let { viewModel.togglePin(it.id) } }
            )
        }

        if (songToRemove != null) {
            RemoveLikeBottomSheet(
                song = songToRemove,
                coverUrl = viewModel.getCoverUrl(songToRemove?.coverArt, 176),
                onDismiss = { songToRemove = null },
                onRemove = { viewModel.toggleLike(songToRemove?.id ?: "") }
            )
        }

        if (showFolderSheet) {
            MusicFolderSelectorBottomSheet(
                folders = state.musicFolders,
                selectedFolderId = state.selectedFolderId,
                onDismiss = { showFolderSheet = false },
                onSelect = { folderId -> viewModel.selectMusicFolder(folderId) }
            )
        }
    }
}
