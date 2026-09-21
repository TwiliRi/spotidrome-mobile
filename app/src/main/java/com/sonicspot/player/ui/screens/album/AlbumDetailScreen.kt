package com.sonicspot.player.ui.screens.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.components.ProvidePauseImageLoadsDuringScroll
import com.sonicspot.player.ui.components.RemoveLikeBottomSheet
import com.sonicspot.player.ui.components.AddToPlaylistHost
import com.sonicspot.player.ui.components.SongOptionsSheet
import com.sonicspot.player.ui.components.SongRowModern
import com.sonicspot.player.ui.playlistadd.AddToPlaylistViewModel
import com.sonicspot.player.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun AlbumDetailScreen(albumId: String, onBack: () -> Unit, viewModel: AlbumDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val gradient = remember { Brush.verticalGradient(colors = listOf(Color(0xFF535353), Color(0xFF3A3A3A), SpotifyColors.Black), startY = 0f, endY = 800f) }
    // Шторка «Добавить в плейлист» и меню трека «…» — общие для всех экранов с треками
    val addToPlaylistViewModel: AddToPlaylistViewModel = hiltViewModel()
    var songMenu by remember { mutableStateOf<Song?>(null) }
    val listState = rememberLazyListState()
    var songToRemove by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(albumId) { viewModel.loadAlbum(albumId) }

    // Пагнация с правильными ключами: не перезапускается при каждом изменении state,
    // срабатывает только когда lastVisible реально изменился и дошли до предпоследних
    // элементов. distinctUntilChanged убирает дубли вызовов.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filter { it != null }
            .map { it!! }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 5 && state.hasMore) {
                    viewModel.loadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = SpotifyColors.White)
            return
        }
        val album = state.album
        if (album == null) {
            Text("Альбом не найден", modifier = Modifier.align(Alignment.Center), color = SpotifyColors.White)
            return
        }

        // Оборачиваем список в провайдер, который на время флинга ставит загрузку
        // обложек на паузу: декоды не сжирают ядра, скролл ровный 60fps.
        ProvidePauseImageLoadsDuringScroll(listState) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().background(gradient)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                                Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            // FIX: 0.8f ширины ~280dp -> 560px нужно, было 300px + hardware true -> лаги + мыло на 2x
                            val coverUrl = remember(album.coverArt) { viewModel.getCoverUrl(album.coverArt, 500) }
                            CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f), cornerRadius = 8.dp, sizePx = 500)
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = album.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = SpotifyColors.White)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val artistCover = remember(album.artistId) { viewModel.getCoverUrl(album.artistId ?: "", 40) }
                                CoverArtImage(url = artistCover, modifier = Modifier.size(20.dp).clip(CircleShape), cornerRadius = 10.dp, sizePx = 40)
                                Spacer(Modifier.width(6.dp))
                                Text(text = album.artist ?: "Unknown", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), color = SpotifyColors.White)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(text = "Альбом • " + (album.year?.toString() ?: "") + " • ${album.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                IconButton(onClick = { viewModel.toggleStar() }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (state.isStarred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (state.isStarred) SpotifyColors.Green else SpotifyColors.LightGray, modifier = Modifier.size(24.dp))
                                }
                                Icon(Icons.Default.DownloadForOffline, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(22.dp))
                                Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = { viewModel.playAll() }, modifier = Modifier.size(52.dp).clip(CircleShape).background(SpotifyColors.Green)) {
                                Icon(Icons.Default.PlayArrow, null, tint = SpotifyColors.Black, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "#  Название • ${state.visibleSongs.size} из ${album.songCount}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
                    if (state.hasMore) Text(text = "Ещё ${state.remainingCount}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.Green)
                }
                HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }

            itemsIndexed(state.visibleSongs, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
                val isPlaying = currentSongId?.id == song.id
                val isLiked = likedIds.contains(song.id) || song.isStarred
                val isDisliked = dislikedIds.contains(song.id)
                SongRowModern(
                    song = song,
                    coverUrl = null,
                    isPlaying = isPlaying,
                    isLiked = isLiked,
                    isDisliked = isDisliked,
                    trackNumber = index + 1,
                    showCover = false,
                    onClick = { viewModel.playSongs(index) },
                    onMore = { songMenu = song },
                    onLike = {
                        if (isLiked) songToRemove = song
                        else viewModel.toggleLike(song.id)
                    },
                    onDislike = { viewModel.toggleDislike(song.id) }
                )
            }

            if (state.hasMore) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                            Text("Загружаем ещё ${state.remainingCount.coerceAtMost(20)} треков...", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(text = (album.year?.toString() ?: "") + " • ${album.artist} • ${album.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                if (!state.hasMore) {
                    Text(text = "Показаны все ${album.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        } // ProvidePauseImageLoadsDuringScroll

        // Spotify style bottom sheet для удаления лайка - FIX: 88dp = 176px, было 200
        if (songToRemove != null) {
            RemoveLikeBottomSheet(
                song = songToRemove,
                coverUrl = viewModel.getCoverUrl(songToRemove?.coverArt, 176),
                onDismiss = { songToRemove = null },
                onRemove = { viewModel.toggleLike(songToRemove?.id ?: "") }
            )
        }

        // Меню трека по «…»: добавление в плейлист, лайк, исключение
        SongOptionsSheet(
            song = songMenu,
            coverUrl = viewModel.getCoverUrl(songMenu?.coverArt, 112),
            isLiked = songMenu?.let { likedIds.contains(it.id) || it.isStarred } == true,
            isDisliked = songMenu?.let { dislikedIds.contains(it.id) } == true,
            onDismiss = { songMenu = null },
            onAddToPlaylist = {
                val target = songMenu
                songMenu = null
                target?.let { addToPlaylistViewModel.open(it) }
            },
            onToggleLike = { songMenu?.let { viewModel.toggleLike(it.id) }; songMenu = null },
            onToggleDislike = { songMenu?.let { viewModel.toggleDislike(it.id) }; songMenu = null },
            shareUrl = null
        )
        // Сама шторка выбора плейлиста (не рисует ничего, пока трек не выбран)
        AddToPlaylistHost(viewModel = addToPlaylistViewModel)
    }
}
