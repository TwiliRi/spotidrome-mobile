package com.sonicspot.player.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Playlist
import com.sonicspot.player.ui.components.*
import com.sonicspot.player.ui.components.SpotifyPullToRefreshBox
import com.sonicspot.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit = {},
    onFavoritesClick: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var isGridView by remember { mutableStateOf(false) }
    val tabs = listOf("Плейлисты", "Исполнители", "Альбомы")

    var selectedAlbumForMenu by remember { mutableStateOf<Album?>(null) }
    var selectedPlaylistForMenu by remember { mutableStateOf<Playlist?>(null) }
    var showAlbumSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }

    SpotifyPullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Верхняя панель без надписи "Моя медиатека" - только чипы и управление
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Слева - чип библиотеки (открывает меню выбора)
                if (state.musicFolders.isNotEmpty()) {
                    LibraryChip(
                        folderName = state.selectedFolderName,
                        onClick = { showFolderSheet = true }
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = SpotifyColors.White, modifier = Modifier.size(24.dp))
                    Icon(Icons.Default.Add, null, tint = SpotifyColors.White, modifier = Modifier.size(26.dp))
                }
            }

            // Убран второй ряд с выбором библиотек в строчку (Все библиотеки + список папок)

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs.size) { idx ->
                    SpotifyFilterChip(text = tabs[idx], selected = selectedTab == idx, onClick = { selectedTab = idx })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { }) {
                    Icon(Icons.Default.SwapVert, null, tint = SpotifyColors.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = "Недавние", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold))
                }
                IconButton(onClick = { isGridView = !isGridView }, modifier = Modifier.size(32.dp)) {
                    Icon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
            }

            when (selectedTab) {
            0 -> {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.starredSongs.isNotEmpty()) {
                            item {
                                Column(modifier = Modifier.width(168.dp).clickable { onFavoritesClick() }) {
                                    Box(
                                        modifier = Modifier.size(168.dp).clip(RoundedCornerShape(8.dp)).background(
                                            Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Favorite, null, tint = SpotifyColors.White, modifier = Modifier.size(48.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Любимые треки", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White, maxLines = 1)
                                    Text("Системный • ${state.starredSongs.size}", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                                }
                            }
                        }
                        if (state.excludedPlaylist != null) {
                            item {
                                val pl = state.excludedPlaylist!!
                                Column(modifier = Modifier.width(168.dp).clickable { onPlaylistClick(pl.id) }) {
                                    Box(
                                        modifier = Modifier.size(168.dp).clip(RoundedCornerShape(8.dp)).background(
                                            Brush.linearGradient(colors = listOf(Color(0xFF8E0E0E), Color(0xFF2A2A2A)))
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.ThumbDown, null, tint = SpotifyColors.White, modifier = Modifier.size(48.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text(pl.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White, maxLines = 1)
                                    Text("Системный • ${pl.songCount}", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                                }
                            }
                        }
                        items(state.pinnedPlaylists, key = { it.id }) { pl ->
                            PlaylistCardModernWithPin(
                                playlist = pl,
                                coverUrl = viewModel.getCoverUrl(pl.coverArt, 336),
                                isPinned = true,
                                isPublic = pl.public,
                                onClick = { onPlaylistClick(pl.id) },
                                onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                            )
                        }
                        items(state.privatePlaylists, key = { it.id }) { pl ->
                            PlaylistCardModernWithPin(
                                playlist = pl,
                                coverUrl = viewModel.getCoverUrl(pl.coverArt, 336),
                                isPinned = false,
                                isPublic = false,
                                onClick = { onPlaylistClick(pl.id) },
                                onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                            )
                        }
                        items(state.publicPlaylists, key = { it.id }) { pl ->
                            PlaylistCardModernWithPin(
                                playlist = pl,
                                coverUrl = viewModel.getCoverUrl(pl.coverArt, 336),
                                isPinned = false,
                                isPublic = true,
                                onClick = { onPlaylistClick(pl.id) },
                                onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                            )
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (state.pinnedPlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Закрепленные") }
                            items(state.pinnedPlaylists, key = { it.id }) { pl ->
                                PlaylistRowWithPin(
                                    playlist = pl,
                                    coverUrl = viewModel.getCoverUrl(pl.coverArt, 96),
                                    isPinned = true,
                                    isPublic = pl.public,
                                    onClick = { onPlaylistClick(pl.id) },
                                    onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                                )
                            }
                        }

                        if (state.starredSongs.isNotEmpty() || state.excludedPlaylist != null) {
                            item { SectionHeaderSmall(title = "Системные") }

                            if (state.starredSongs.isNotEmpty()) {
                                item {
                                    SystemPlaylistRow(
                                        title = "Любимые треки",
                                        subtitle = "Системный • ${state.starredSongs.size} треков • нажми чтобы открыть",
                                        count = state.starredSongs.size,
                                        icon = Icons.Default.Favorite,
                                        gradient = Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))),
                                        onClick = { onFavoritesClick() }
                                    )
                                }
                            }

                            if (state.excludedPlaylist != null && !state.pinnedIds.contains(state.excludedPlaylist!!.id)) {
                                item {
                                    val pl = state.excludedPlaylist!!
                                    SystemPlaylistRow(
                                        title = pl.name,
                                        subtitle = "Системный • ${pl.songCount} треков • не в рекомендациях",
                                        count = pl.songCount,
                                        icon = Icons.Default.ThumbDown,
                                        gradient = Brush.linearGradient(colors = listOf(Color(0xFF8E0E0E), Color(0xFF2A2A2A))),
                                        onClick = { onPlaylistClick(pl.id) },
                                        onLongClick = { viewModel.cleanupDuplicates() }
                                    )
                                }
                            }
                        }

                        if (state.privatePlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Личные • ${state.privatePlaylists.size}") }
                            items(state.privatePlaylists, key = { it.id }) { pl ->
                                PlaylistRowWithPin(
                                    playlist = pl,
                                    coverUrl = viewModel.getCoverUrl(pl.coverArt, 96),
                                    isPinned = false,
                                    isPublic = false,
                                    onClick = { onPlaylistClick(pl.id) },
                                    onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                                )
                            }
                        }

                        if (state.publicPlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Общие • ${state.publicPlaylists.size}") }
                            items(state.publicPlaylists, key = { it.id }) { pl ->
                                PlaylistRowWithPin(
                                    playlist = pl,
                                    coverUrl = viewModel.getCoverUrl(pl.coverArt, 96),
                                    isPinned = false,
                                    isPublic = true,
                                    onClick = { onPlaylistClick(pl.id) },
                                    onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                                )
                            }
                        }

                        if (state.playlists.isEmpty() && !state.isLoading) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.QueueMusic, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("Нет плейлистов", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                                        Text("Создай плейлист в Navidrome", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(12.dp))
                                        if (state.playlists.count { it.name == "Исключённые треки" } > 1) {
                                            Button(onClick = { viewModel.cleanupDuplicates() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Red)) {
                                                Text("Удалить дубли 'Исключённые треки'")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.visibleArtists, key = { it.id }) { artist ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { onArtistClick(artist.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverArtImage(url = viewModel.getCoverUrl(artist.coverArt, 112), modifier = Modifier.size(56.dp).clip(CircleShape), cornerRadius = 28.dp, sizePx = 112)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(artist.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = SpotifyColors.White)
                                Text("Исполнитель", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                            }
                        }
                    }
                    if (state.hasMoreArtists) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Button(onClick = { viewModel.loadMoreArtists() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Gray)) {
                                    Text("Загрузить ещё ${state.artists.size - state.visibleArtistCount} • показано ${state.visibleArtistCount} из ${state.artists.size}")
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.visibleAlbums, key = { it.id }) { album ->
                        AlbumRowWithMenu(
                            album = album,
                            coverUrl = viewModel.getCoverUrl(album.coverArt, 112),
                            isPinned = state.pinnedAlbumIds.contains(album.id),
                            onClick = { onAlbumClick(album.id) },
                            onLongClick = { selectedAlbumForMenu = album; showAlbumSheet = true }
                        )
                    }
                    if (state.hasMoreAlbums) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Button(
                                    onClick = { viewModel.loadMoreAlbums() },
                                    enabled = !state.isLoadingMoreAlbums,
                                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Gray)
                                ) {
                                    Text(
                                        if (state.isLoadingMoreAlbums) "Загружаю…"
                                        else "Загрузить ещё • показано ${state.albums.size}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showAlbumSheet) {
        AlbumOptionsBottomSheet(
            album = selectedAlbumForMenu,
            coverUrl = viewModel.getCoverUrl(selectedAlbumForMenu?.coverArt, 112),
            isPinned = state.pinnedAlbumIds.contains(selectedAlbumForMenu?.id),
            onDismiss = { showAlbumSheet = false; selectedAlbumForMenu = null },
            onPlay = { selectedAlbumForMenu?.let { viewModel.playAlbum(it.id) } },
            onShuffle = { selectedAlbumForMenu?.let { viewModel.shuffleAlbum(it.id) } },
            onGoToArtist = { selectedAlbumForMenu?.artistId?.let { onArtistClick(it) } },
            onPinToggle = { selectedAlbumForMenu?.let { viewModel.toggleAlbumPin(it.id) } },
            onAddToQueue = {}
        )
    }

    if (showPlaylistSheet) {
        PlaylistOptionsBottomSheet(
            playlist = selectedPlaylistForMenu,
            coverUrl = viewModel.getCoverUrl(selectedPlaylistForMenu?.coverArt, 112),
            isPinned = state.pinnedIds.contains(selectedPlaylistForMenu?.id),
            onDismiss = { showPlaylistSheet = false; selectedPlaylistForMenu = null },
            onPlay = { selectedPlaylistForMenu?.let { onPlaylistClick(it.id) } },
            onShuffle = {},
            onPinToggle = { selectedPlaylistForMenu?.let { viewModel.togglePin(it.id) } }
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

@Composable
private fun SectionHeaderSmall(title: String) {
    Text(text = title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.5.sp), color = SpotifyColors.LightGray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumRowWithMenu(album: com.sonicspot.player.data.model.Album, coverUrl: String?, isPinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
            if (isPinned) {
                Box(modifier = Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(SpotifyColors.Green), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Default.PushPin, null, tint = SpotifyColors.Black, modifier = Modifier.size(10.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(Icons.Default.PushPin, null, tint = SpotifyColors.Green, modifier = Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                androidx.compose.material3.Text(album.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = SpotifyColors.White, maxLines = 1)
            }
            androidx.compose.material3.Text("Альбом • " + (album.artist ?: ""), style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray, maxLines = 1)
        }
        androidx.compose.material3.Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}
