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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.sonicspot.player.data.model.Song
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
    onDownloadsClick: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val downloadedCount by viewModel.downloadedCount.collectAsState()
    // Геттеры LibraryUiState (pinnedPlaylists и пр.) фильтруют весь список на КАЖДОЕ
    // обращение. Мемоизируем: пересчёт только при реальном изменении данных.
    val pinnedPlaylists = remember(state.playlists, state.pinnedIds) { state.pinnedPlaylists }
    val privatePlaylists = remember(state.playlists, state.pinnedIds) { state.privatePlaylists }
    val publicPlaylists = remember(state.playlists, state.pinnedIds) { state.publicPlaylists }
    val excludedPlaylist = remember(state.playlists) { state.excludedPlaylist }
    var selectedTab by remember { mutableStateOf(0) }
    var isGridView by remember { mutableStateOf(false) }
    val tabs = listOf("Плейлисты", "Исполнители", "Альбомы")

    var selectedAlbumForMenu by remember { mutableStateOf<Album?>(null) }
    var selectedPlaylistForMenu by remember { mutableStateOf<Playlist?>(null) }
    var showAlbumSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    SpotifyPullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)
    ) {
        if (searchState.isActive) {
            LibrarySearchPanel(
                searchState = searchState,
                onQueryChange = { viewModel.onSearchQueryChange(it) },
                onClose = { viewModel.setSearchActive(false) },
                onAlbumClick = onAlbumClick,
                onPlaySong = { songs, idx -> viewModel.playSongs(songs, idx) },
                coverUrlProvider = { id -> viewModel.getCoverUrl(id, 112) }
            )
        } else {
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
                    Icon(
                        Icons.Default.Search, "Поиск по медиатеке", tint = SpotifyColors.White,
                        modifier = Modifier.size(24.dp).clickable { viewModel.setSearchActive(true) }
                    )
                    Icon(
                        Icons.Default.Add, "Создать плейлист", tint = SpotifyColors.White,
                        modifier = Modifier.size(26.dp).clickable { showCreatePlaylistDialog = true }
                    )
                }
            }

            // Убран второй ряд с выбором библиотек в строчку (Все библиотеки + список папок)

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs.size, key = { "tab_$it" }, contentType = { "tab" }) { idx ->
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
                        if (excludedPlaylist != null) {
                            item {
                                val pl = excludedPlaylist!!
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
                        item {
                            Column(modifier = Modifier.width(168.dp).clickable { onDownloadsClick() }) {
                                Box(
                                    modifier = Modifier.size(168.dp).clip(RoundedCornerShape(8.dp)).background(
                                        Brush.linearGradient(colors = listOf(Color(0xFF0E6E5A), Color(0xFF2A2A2A)))
                                    ),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.DownloadForOffline, null, tint = SpotifyColors.White, modifier = Modifier.size(48.dp)) }
                                Spacer(Modifier.height(8.dp))
                                Text("Скачанные", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White, maxLines = 1)
                                Text("Системный • $downloadedCount", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                            }
                        }
                        items(pinnedPlaylists, key = { it.id }) { pl ->
                            PlaylistCardModernWithPin(
                                playlist = pl,
                                coverUrl = viewModel.getCoverUrl(pl.coverArt, 336),
                                isPinned = true,
                                isPublic = pl.public,
                                onClick = { onPlaylistClick(pl.id) },
                                onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                            )
                        }
                        items(privatePlaylists, key = { it.id }) { pl ->
                            PlaylistCardModernWithPin(
                                playlist = pl,
                                coverUrl = viewModel.getCoverUrl(pl.coverArt, 336),
                                isPinned = false,
                                isPublic = false,
                                onClick = { onPlaylistClick(pl.id) },
                                onPinClick = { selectedPlaylistForMenu = pl; showPlaylistSheet = true }
                            )
                        }
                        items(publicPlaylists, key = { it.id }) { pl ->
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
                        if (pinnedPlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Закрепленные") }
                            items(pinnedPlaylists, key = { it.id }) { pl ->
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

                        if (state.starredSongs.isNotEmpty() || excludedPlaylist != null || downloadedCount >= 0) {
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

                            if (excludedPlaylist != null && !state.pinnedIds.contains(excludedPlaylist!!.id)) {
                                item {
                                    val pl = excludedPlaylist!!
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

                            item {
                                SystemPlaylistRow(
                                    title = "Скачанные",
                                    subtitle = "Системный • $downloadedCount треков • офлайн, в памяти устройства",
                                    count = downloadedCount,
                                    icon = Icons.Default.DownloadForOffline,
                                    gradient = Brush.linearGradient(colors = listOf(Color(0xFF0E6E5A), Color(0xFF2A2A2A))),
                                    onClick = { onDownloadsClick() }
                                )
                            }
                        }

                        if (privatePlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Личные • ${privatePlaylists.size}") }
                            items(privatePlaylists, key = { it.id }) { pl ->
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

                        if (publicPlaylists.isNotEmpty()) {
                            item { SectionHeaderSmall(title = "Общие • ${publicPlaylists.size}") }
                            items(publicPlaylists, key = { it.id }) { pl ->
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
        } // конец режима библиотеки (else от searchState.isActive)
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

    if (showCreatePlaylistDialog) {
        var newName by remember { mutableStateOf("") }
        var newIsPublic by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = SpotifyColors.Gray,
            title = { Text("Новый плейлист", color = SpotifyColors.White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { newIsPublic = !newIsPublic },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = newIsPublic,
                            onCheckedChange = { newIsPublic = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SpotifyColors.White,
                                checkedTrackColor = SpotifyColors.Green,
                                uncheckedThumbColor = SpotifyColors.LightGray,
                                uncheckedTrackColor = SpotifyColors.GrayLighter
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Публичный",
                                color = SpotifyColors.White,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                if (newIsPublic) "Виден другим пользователям сервера" else "Личный — видите только вы",
                                color = SpotifyColors.LightGray,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val name = newName.trim()
                        val isPublic = newIsPublic
                        showCreatePlaylistDialog = false
                        viewModel.createPlaylist(name, isPublic) { created ->
                            if (created != null) onPlaylistClick(created.id)
                        }
                    }
                ) { Text("Создать", color = SpotifyColors.Green, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Отмена", color = SpotifyColors.LightGray) }
            }
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

@Composable
private fun LibrarySearchPanel(
    searchState: LibrarySearchState,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaySong: (List<Song>, Int) -> Unit,
    coverUrlProvider: (String?) -> String?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Трек или альбом", color = SpotifyColors.LightGray) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = SpotifyColors.LightGray) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(onClick = onClose) { Text("Отмена", color = SpotifyColors.White) }
        }

        when {
            searchState.isSearching -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SpotifyColors.Green, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            searchState.query.isBlank() -> {
                Text(
                    "Ищите треки и альбомы своей медиатеки",
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp)
                )
            }
            searchState.songs.isEmpty() && searchState.albums.isEmpty() -> {
                Text(
                    "Ничего не найдено",
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp)
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                    if (searchState.songs.isNotEmpty()) {
                        item { SectionHeaderSmall("Треки") }
                        itemsIndexed(searchState.songs, key = { idx, s -> "song_${s.id}_$idx" }) { idx, song ->
                            SearchRow(
                                title = song.title,
                                subtitle = listOfNotNull(song.artist, song.album).joinToString(" • "),
                                coverUrl = coverUrlProvider(song.coverArt),
                                onClick = { onPlaySong(searchState.songs, idx) }
                            )
                        }
                    }
                    if (searchState.albums.isNotEmpty()) {
                        item { SectionHeaderSmall("Альбомы") }
                        items(searchState.albums, key = { "album_${it.id}" }) { album ->
                            SearchRow(
                                title = album.name,
                                subtitle = "Альбом • " + (album.artist ?: ""),
                                coverUrl = coverUrlProvider(album.coverArt),
                                onClick = { onAlbumClick(album.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(title: String, subtitle: String, coverUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(48.dp), cornerRadius = 4.dp, sizePx = 96)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), maxLines = 1)
            Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1)
        }
    }
}
