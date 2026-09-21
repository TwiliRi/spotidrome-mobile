package com.sonicspot.player.ui.screens.artist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.components.AlbumCardModern
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.components.ProvidePauseImageLoadsDuringScroll
import com.sonicspot.player.ui.components.SectionHeaderModern
import com.sonicspot.player.ui.components.SongRowModern
import com.sonicspot.player.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistId: String,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var showAllTracks by remember { mutableStateOf(false) }
    var showArtistOptions by remember { mutableStateOf(false) }
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    var showSongOptions by remember { mutableStateOf(false) }
    var selectedAlbumForOptions by remember { mutableStateOf<Album?>(null) }
    var showAlbumOptions by remember { mutableStateOf(false) }
    var showAllAlbumsSheet by remember { mutableStateOf(false) }

    // Копирование ссылки на Navidrome в буфер обмена
    fun copyNavidromeLink(link: String, label: String = "Ссылка скопирована") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Navidrome link", link)
            clipboard.setPrimaryClip(clip)
            scope.launch {
                snackbarHostState.showSnackbar(label)
            }
        } catch (_: Exception) {}
    }

    fun copyArtistLink() {
        val artist = state.artist ?: return
        val link = viewModel.getArtistShareUrl(artist.id)
        copyNavidromeLink(link, "Ссылка на артиста скопирована")
    }
    fun copySongLink(song: Song) {
        val link = viewModel.getSongShareUrl(song.id)
        copyNavidromeLink(link, "Ссылка на трек скопирована")
    }
    fun copyAlbumLink(album: Album) {
        val link = viewModel.getAlbumShareUrl(album.id)
        copyNavidromeLink(link, "Ссылка на альбом скопирована")
    }

    BackHandler(enabled = true) {
        when {
            showSongOptions -> showSongOptions = false
            showAlbumOptions -> showAlbumOptions = false
            showArtistOptions -> showArtistOptions = false
            showAllAlbumsSheet -> showAllAlbumsSheet = false
            else -> onBack()
        }
    }

    LaunchedEffect(artistId) { viewModel.loadArtist(artistId) }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = SpotifyColors.White)
            return
        }
        val artist = state.artist
        if (artist == null) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Исполнитель не найден", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black)) {
                    Text("Назад")
                }
            }
            return
        }

        // Пауза загрузки обложек во время флинга = плавный скролл 60fps.
        ProvidePauseImageLoadsDuringScroll(listState) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // HEADER - 440dp в стиле Spotify. Используем CoverArtImage с HARDWARE-битмапом.
            item {
                Box(modifier = Modifier.fillMaxWidth().height(440.dp)) {
                    val headerCoverUrl = remember(artist.coverArt) { viewModel.getCoverUrl(artist.coverArt, 600) }
                    CoverArtImage(
                        url = headerCoverUrl,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 0.dp,
                        sizePx = 600
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp).background(
                            Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))
                        )
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.2f),
                                    Color.Black.copy(alpha = 0.6f),
                                    SpotifyColors.Black
                                ),
                                startY = 150f,
                                endY = 1000f
                            )
                        )
                    )

                    // Top bar
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { copyArtistLink() },
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Link, null, tint = SpotifyColors.White, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { showArtistOptions = true },
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Bottom info - без "Подтвержденный исполнитель"
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 42.sp,
                                lineHeight = 40.sp,
                                letterSpacing = (-1).sp
                            ),
                            color = SpotifyColors.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter.copy(alpha = 0.3f)).padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("${artist.albumCount} альбомов", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = SpotifyColors.White)
                            }
                            if (state.topSongs.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text("• ${state.topSongs.size} треков в топе", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                            }
                        }
                    }
                }
            }

            // ACTION ROW - убрана иконка радио, осталась только маленькая иконка шаринга (теперь копирует ссылку)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = { viewModel.playAll() },
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(SpotifyColors.Green)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = SpotifyColors.Black, modifier = Modifier.size(32.dp))
                        }
                        OutlinedIconButton(
                            onClick = { viewModel.shufflePlay() },
                            modifier = Modifier.size(36.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyColors.GrayLighter.copy(alpha = 0.4f)),
                            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = SpotifyColors.White)
                        ) {
                            Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                        }
                        OutlinedIconButton(
                            onClick = { copyArtistLink() },
                            modifier = Modifier.size(36.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyColors.GrayLighter.copy(alpha = 0.4f)),
                            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = SpotifyColors.White)
                        ) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // POPULAR TRACKS
            if (state.topSongs.isNotEmpty()) {
                item {
                    SectionHeaderModern(title = "Популярные треки")
                    Spacer(Modifier.height(4.dp))
                }
                val tracksToShow = if (showAllTracks) state.topSongs else state.topSongs.take(5)
                itemsIndexed(tracksToShow, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { index, song ->
                    val coverUrl = remember(song.coverArt) { viewModel.getCoverUrl(song.coverArt, 88) }
                    val isPlaying = currentSongId?.id == song.id
                    SongRowModern(
                        song = song,
                        coverUrl = coverUrl,
                        isPlaying = isPlaying,
                        isLiked = likedIds.contains(song.id) || song.isStarred,
                        isDisliked = dislikedIds.contains(song.id),
                        trackNumber = index + 1,
                        showCover = true,
                        onClick = { viewModel.playTopSongAt(if (showAllTracks) index else state.topSongs.indexOf(song)) },
                        onMore = {
                            selectedSong = song
                            showSongOptions = true
                        },
                        onLike = { viewModel.toggleLike(song.id) },
                        onDislike = { viewModel.toggleDislike(song.id) }
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    if (state.topSongs.size > 5) {
                        TextButton(
                            onClick = { showAllTracks = !showAllTracks },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                if (showAllTracks) "Свернуть" else "Показать еще ${state.topSongs.size - 5}",
                                color = SpotifyColors.LightGray,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (showAllTracks) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                null,
                                tint = SpotifyColors.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else if (state.isTopSongsLoading) {
                item {
                    SectionHeaderModern(title = "Популярные треки")
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SpotifyColors.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            // УБРАНА группа "Популярные релизы"

            // DISCOGRAPHY - единственная группа альбомов - теперь через PaginatedLazyRow 3 сразу
            item {
                SectionHeaderModern(title = "Дискография", onSeeAll = {
                    showAllAlbumsSheet = true
                })
                com.sonicspot.player.ui.components.PaginatedLazyRow(
                    items = artist.album,
                    initialVisible = 3,
                    pageSize = 4,
                    key = { it.id },
                    itemContent = { album ->
                        val albumCoverUrl = remember(album.coverArt) { viewModel.getCoverUrl(album.coverArt, 304) }
                        AlbumCardModern(
                            album = album,
                            coverUrl = albumCoverUrl,
                            onClick = { onAlbumClick(album.id) },
                            onLongClick = {
                                selectedAlbumForOptions = album
                                showAlbumOptions = true
                            }
                        )
                    }
                )
                Spacer(Modifier.height(24.dp))
            }

            // ABOUT
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Об исполнителе", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp), color = SpotifyColors.White)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Gray).padding(16.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val aboutCoverUrl = remember(artist.coverArt) { viewModel.getCoverUrl(artist.coverArt, 128) }
                                Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(SpotifyColors.GrayLighter)) {
                                    CoverArtImage(
                                        url = aboutCoverUrl,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 32.dp,
                                        sizePx = 128
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(artist.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White, maxLines = 1)
                                    Text("${artist.albumCount} альбомов • ${artist.album.size} релизов", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Слушай ${artist.name} на Spotidrome. Вся дискография и популярные треки.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                color = SpotifyColors.LightGray
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { copyArtistLink() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyColors.White),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Копировать ссылку", fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // Показываем ссылку для наглядности
                            Text(
                                text = viewModel.getArtistShareUrl(artist.id),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = SpotifyColors.MediumGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        } // ProvidePauseImageLoadsDuringScroll

        // Snackbar для копирования ссылки
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
            snackbar = { data ->
                Snackbar(
                    containerColor = SpotifyColors.Gray,
                    contentColor = SpotifyColors.White,
                    actionColor = SpotifyColors.Green,
                    snackbarData = data
                )
            }
        )

        // ===== BOTTOM SHEETS =====

        if (showArtistOptions) {
            ArtistOptionsBottomSheet(
                artistName = artist.name,
                coverUrl = viewModel.getCoverUrl(artist.coverArt, 112),
                shareUrl = viewModel.getArtistShareUrl(artist.id),
                onDismiss = { showArtistOptions = false },
                onPlayAll = { viewModel.playAll(); showArtistOptions = false },
                onShuffle = { viewModel.shufflePlay(); showArtistOptions = false },
                onCopyLink = { copyArtistLink(); showArtistOptions = false }
            )
        }

        if (showSongOptions && selectedSong != null) {
            ArtistSongOptionsBottomSheet(
                song = selectedSong!!,
                coverUrl = viewModel.getCoverUrl(selectedSong!!.coverArt, 112),
                isLiked = likedIds.contains(selectedSong!!.id),
                isDisliked = dislikedIds.contains(selectedSong!!.id),
                shareUrl = viewModel.getSongShareUrl(selectedSong!!.id),
                onDismiss = { showSongOptions = false; selectedSong = null },
                onPlayNext = { viewModel.addNext(selectedSong!!); showSongOptions = false },
                onAddToQueue = { viewModel.addToQueue(selectedSong!!); showSongOptions = false },
                onGoToAlbum = {
                    val albumId = selectedSong!!.albumId
                    if (albumId != null) onAlbumClick(albumId)
                    showSongOptions = false
                },
                onTrackRadio = { viewModel.startTrackRadio(selectedSong!!); showSongOptions = false },
                onToggleLike = { viewModel.toggleLike(selectedSong!!.id); showSongOptions = false },
                onToggleDislike = { viewModel.toggleDislike(selectedSong!!.id); showSongOptions = false },
                onCopyLink = { copySongLink(selectedSong!!); showSongOptions = false }
            )
        }

        if (showAlbumOptions && selectedAlbumForOptions != null) {
            ArtistAlbumOptionsBottomSheet(
                album = selectedAlbumForOptions!!,
                coverUrl = viewModel.getCoverUrl(selectedAlbumForOptions!!.coverArt, 112),
                shareUrl = viewModel.getAlbumShareUrl(selectedAlbumForOptions!!.id),
                onDismiss = { showAlbumOptions = false; selectedAlbumForOptions = null },
                onPlay = { viewModel.playAlbum(selectedAlbumForOptions!!.id); showAlbumOptions = false },
                onShuffle = { viewModel.shuffleAlbum(selectedAlbumForOptions!!.id); showAlbumOptions = false },
                onAddToQueue = { viewModel.addAlbumToQueue(selectedAlbumForOptions!!.id); showAlbumOptions = false },
                onGoToAlbum = { onAlbumClick(selectedAlbumForOptions!!.id); showAlbumOptions = false },
                onCopyLink = { copyAlbumLink(selectedAlbumForOptions!!); showAlbumOptions = false }
            )
        }

        if (showAllAlbumsSheet) {
            AllAlbumsBottomSheet(
                albums = artist.album,
                title = "Дискография",
                coverUrlProvider = { id -> viewModel.getCoverUrl(id, 304) },
                onDismiss = { showAllAlbumsSheet = false },
                onAlbumClick = { albumId ->
                    showAllAlbumsSheet = false
                    onAlbumClick(albumId)
                },
                onAlbumLongClick = { album ->
                    selectedAlbumForOptions = album
                    showAlbumOptions = true
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistOptionsBottomSheet(
    artistName: String,
    coverUrl: String?,
    shareUrl: String,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onCopyLink: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(SpotifyColors.GrayLighter)) {
                    if (coverUrl != null) com.sonicspot.player.ui.components.CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 28.dp, sizePx = 112)
                    else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(artistName.take(1).uppercase(), color = SpotifyColors.White, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(artistName, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shareUrl, color = SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            BottomSheetItem(icon = Icons.Default.PlayArrow, title = "Слушать", onClick = onPlayAll)
            BottomSheetItem(icon = Icons.Default.Shuffle, title = "Перемешать", onClick = onShuffle)
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetItem(icon = Icons.Default.Link, title = "Копировать ссылку", subtitle = shareUrl, onClick = onCopyLink)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistSongOptionsBottomSheet(
    song: Song,
    coverUrl: String?,
    isLiked: Boolean,
    isDisliked: Boolean = false,
    shareUrl: String,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToAlbum: () -> Unit,
    onTrackRadio: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit = {},
    onCopyLink: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter)) {
                    if (coverUrl != null) com.sonicspot.player.ui.components.CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 112)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shareUrl, color = SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            BottomSheetItem(icon = Icons.Default.SkipNext, title = "Играть следующим", onClick = onPlayNext)
            BottomSheetItem(icon = Icons.Default.QueueMusic, title = "Добавить в очередь", onClick = onAddToQueue)
            BottomSheetItem(icon = Icons.Default.Album, title = "Перейти к альбому", onClick = onGoToAlbum)
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetItem(icon = Icons.Default.Radio, title = "Радио по треку", subtitle = "Похожие на ${song.title}", onClick = onTrackRadio)
            BottomSheetItem(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                title = if (isLiked) "Удалить из Любимых" else "Добавить в Любимые",
                onClick = onToggleLike
            )
            BottomSheetItem(
                icon = if (isDisliked) Icons.Filled.ThumbDown else Icons.Default.ThumbDownOffAlt,
                title = if (isDisliked) "Убрать из исключённых" else "Исключить трек",
                subtitle = "Не будет в рекомендациях",
                isDestructive = isDisliked,
                onClick = onToggleDislike
            )
            BottomSheetItem(icon = Icons.Default.Link, title = "Копировать ссылку", subtitle = shareUrl, onClick = onCopyLink)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistAlbumOptionsBottomSheet(
    album: Album,
    coverUrl: String?,
    shareUrl: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToAlbum: () -> Unit,
    onCopyLink: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter)) {
                    if (coverUrl != null) com.sonicspot.player.ui.components.CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 112)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.name, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shareUrl, color = SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            BottomSheetItem(icon = Icons.Default.PlayArrow, title = "Слушать альбом", onClick = onPlay)
            BottomSheetItem(icon = Icons.Default.Shuffle, title = "Перемешать альбом", onClick = onShuffle)
            BottomSheetItem(icon = Icons.Default.QueueMusic, title = "Добавить альбом в очередь", onClick = onAddToQueue)
            BottomSheetItem(icon = Icons.Default.Album, title = "Открыть альбом", onClick = onGoToAlbum)
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetItem(icon = Icons.Default.Link, title = "Копировать ссылку", subtitle = shareUrl, onClick = onCopyLink)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllAlbumsBottomSheet(
    albums: List<Album>,
    title: String,
    coverUrlProvider: (String?) -> String?,
    onDismiss: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onAlbumLongClick: (Album) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White)
                Text("${albums.size} альбомов", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.heightIn(max = 500.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCardModern(
                        album = album,
                        coverUrl = coverUrlProvider(album.coverArt),
                        onClick = { onAlbumClick(album.id) },
                        onLongClick = { onAlbumLongClick(album) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
