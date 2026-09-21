package com.sonicspot.player.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
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
fun PlaylistDetailScreen(playlistId: String, onBack: () -> Unit, viewModel: PlaylistDetailViewModel = hiltViewModel()) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val downloadedMap by viewModel.downloadedMap.collectAsState()
    val downloadingIds by viewModel.downloadingIds.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showMoreDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    val gradient = remember { Brush.verticalGradient(colors = listOf(Color(0xFF5A5A5A), Color(0xFF2A2A2A), SpotifyColors.Black), startY = 0f, endY = 900f) }
    val listState = rememberLazyListState()
    var songToRemove by remember { mutableStateOf<Song?>(null) }
    // Шторка «Добавить в плейлист» и меню трека «…» — общие для всех экранов с треками
    val addToPlaylistViewModel: AddToPlaylistViewModel = hiltViewModel()
    var songMenu by remember { mutableStateOf<Song?>(null) }
    // Состав этого плейлиста менялся (например, трек убрали из шторки) — перечитываем
    val addToPlaylistState by addToPlaylistViewModel.uiState.collectAsState()
    LaunchedEffect(addToPlaylistState.changeToken) {
        if (addToPlaylistState.changeToken > 0) viewModel.loadPlaylist(playlistId, forceRefresh = true)
    }

    LaunchedEffect(playlistId) { viewModel.loadPlaylist(playlistId) }

    // Пагинация: distinctUntilChanged + фильтр нулов — не дублирует loadMore на
    // каждое движение скролла, только когда реально дошли до конца.
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
        val playlist = state.playlist
        if (playlist == null) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Плейлист не найден", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.error ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            return
        }

        val isExcludedPlaylist = playlist.name == "Исключённые треки"

        // При флинге приостанавливаем загрузку обложек — 60 fps.
        ProvidePauseImageLoadsDuringScroll(listState) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item(key = "header", contentType = "header") {
                Box(modifier = Modifier.fillMaxWidth().background(gradient)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).statusBarsPadding(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { onBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(if (state.isPinned) SpotifyColors.Green else Color.Black.copy(alpha = 0.4f)).clickable { viewModel.togglePin() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(if (state.isPinned) Icons.Default.PushPin else Icons.Filled.PushPin, null, tint = if (state.isPinned) SpotifyColors.Black else SpotifyColors.White, modifier = Modifier.size(20.dp))
                                }
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { showMoreDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(
                                    if (isExcludedPlaylist) Brush.linearGradient(colors = listOf(Color(0xFF8E0E0E), Color(0xFF2A2A2A)))
                                    else Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
                                )
                            ) {
                                val firstCover = playlist.entry.firstOrNull()?.coverArt
                                val firstCoverUrl = remember(firstCover) { firstCover?.let { viewModel.getCoverUrl(it, 500) } }
                                if (firstCoverUrl != null && !isExcludedPlaylist) {
                                    CoverArtImage(
                                        url = firstCoverUrl,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 8.dp,
                                        sizePx = 500
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (isExcludedPlaylist) Icons.Default.ThumbDown else Icons.Default.QueueMusic,
                                            null,
                                            tint = SpotifyColors.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(64.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = if (isExcludedPlaylist) "Системный плейлист" else "Плейлист", style = MaterialTheme.typography.labelSmall.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontSize = 10.sp), modifier = Modifier.padding(bottom = 4.dp))
                            Text(text = playlist.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 26.sp), color = SpotifyColors.White)
                            if (isExcludedPlaylist) {
                                Spacer(Modifier.height(4.dp))
                                Text(text = "Треки которые вам не понравились автоматически попадают сюда", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                            }
                            Spacer(Modifier.height(6.dp))
                            // Автор — владелец плейлиста с сервера (у каждого он свой).
                            // Если сервер владельца не отдал, показываем ник текущего пользователя;
                            // если и его нет — строку автора не рисуем вовсе, чтобы не подставлять чужое имя.
                            val ownerName = state.ownerName
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ownerName.isNotEmpty()) {
                                    Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(SpotifyColors.Purple), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = ownerName.take(1).uppercase(),
                                            color = SpotifyColors.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(text = ownerName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.width(6.dp))
                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(SpotifyColors.LightGray))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(text = "${playlist.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(text = formatDuration(playlist.duration), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                val downloadedInPlaylist = playlist.entry.count { downloadedMap.containsKey(it.id) }
                                val allDownloaded = playlist.entry.isNotEmpty() && downloadedInPlaylist == playlist.entry.size
                                Box(modifier = Modifier.size(28.dp).clickable { viewModel.downloadAll() }, contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.DownloadForOffline,
                                        if (allDownloaded) "Всё скачано" else "Скачать треки",
                                        tint = if (allDownloaded || downloadingIds.isNotEmpty()) SpotifyColors.Green else SpotifyColors.LightGray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Box(modifier = Modifier.size(28.dp).clickable {
                                    val text = viewModel.shareText()
                                    if (text.isNotEmpty()) {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Плейлист", text))
                                        android.widget.Toast.makeText(context, "Ссылка скопирована", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Share, "Поделиться", tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
                                }
                                Box(modifier = Modifier.size(28.dp).clickable { showMoreDialog = true }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MoreHoriz, "Ещё", tint = SpotifyColors.LightGray, modifier = Modifier.size(22.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(modifier = Modifier.size(40.dp).clip(CircleShape).clickable { viewModel.shufflePlay() }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Shuffle, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(26.dp))
                                }
                                Box(
                                    modifier = Modifier.size(56.dp).clip(CircleShape).background(SpotifyColors.Green).clickable { viewModel.playAll() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = SpotifyColors.Black, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            }

            item(key = "subheader", contentType = "subheader") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = SpotifyColors.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Показано ${state.visibleSongs.size} из ${playlist.songCount}", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.hasMore) Text("Ещё ${state.remainingCount}", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.Green, fontSize = 11.sp))
                        else Text("Все треки", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.LightGray, fontSize = 11.sp))
                        Icon(Icons.Default.Sort, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
                HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }

            itemsIndexed(state.visibleSongs, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
                val isPlaying = currentSongId?.id == song.id
                val isLiked = likedIds.contains(song.id) || song.isStarred
                val isDisliked = dislikedIds.contains(song.id)
                val coverUrl = remember(song.coverArt) { viewModel.getCoverUrl(song.coverArt, 88) }
                SongRowModern(
                    song = song,
                    coverUrl = coverUrl,
                    isPlaying = isPlaying,
                    isLiked = isLiked,
                    isDisliked = isDisliked,
                    trackNumber = null,
                    showCover = true,
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
                item(key = "loading", contentType = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                            Text("Загружаем ещё ${state.remainingCount.coerceAtMost(20)} треков...", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                        }
                    }
                }
            }

            item(key = "footer", contentType = "footer") {
                Spacer(Modifier.height(24.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(text = "${playlist.songCount} треков • ${formatDuration(playlist.duration)}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isExcludedPlaylist) "Все треки из этого плейлиста исключены из рекомендаций и плеера" else "Плейлист создан в Navidrome",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = SpotifyColors.MediumGray
                    )
                    if (!state.hasMore) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = "Показаны все ${playlist.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = SpotifyColors.MediumGray)
                    }
                }
            }
        }
        } // ProvidePauseImageLoadsDuringScroll

        if (songToRemove != null) {
            RemoveLikeBottomSheet(
                song = songToRemove,
                coverUrl = viewModel.getCoverUrl(songToRemove?.coverArt, 176),
                onDismiss = { songToRemove = null },
                onRemove = { viewModel.toggleLike(songToRemove?.id ?: "") }
            )
        }

        if (showMoreDialog) {
            AlertDialog(
                onDismissRequest = { showMoreDialog = false },
                containerColor = SpotifyColors.Gray,
                title = { Text("Действия", color = SpotifyColors.White) },
                text = {
                    Column {
                        TextButton(
                            onClick = { showMoreDialog = false; showRenameDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Edit, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Переименовать", color = SpotifyColors.White)
                            }
                        }
                        val downloadedInMenu = playlist.entry.count { downloadedMap.containsKey(it.id) }
                        if (downloadedInMenu > 0) {
                            TextButton(
                                onClick = { showMoreDialog = false; viewModel.removeDownloads() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.DeleteSweep, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("Удалить скачанное ($downloadedInMenu)", color = SpotifyColors.White)
                                }
                            }
                        }
                        if (!isExcludedPlaylist) {
                            TextButton(
                                onClick = { showMoreDialog = false; showDeletePlaylistDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Delete, null, tint = SpotifyColors.Red, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("Удалить плейлист", color = SpotifyColors.Red)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMoreDialog = false }) { Text("Закрыть", color = SpotifyColors.LightGray) }
                }
            )
        }

        if (showRenameDialog) {
            var newName by remember { mutableStateOf(playlist.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                containerColor = SpotifyColors.Gray,
                title = { Text("Переименовать", color = SpotifyColors.White) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            viewModel.renamePlaylist(newName.trim())
                            showRenameDialog = false
                        }
                    ) { Text("Сохранить", color = SpotifyColors.Green) }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Отмена", color = SpotifyColors.LightGray) }
                }
            )
        }

        if (showDeletePlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showDeletePlaylistDialog = false },
                containerColor = SpotifyColors.Gray,
                title = { Text("Удалить плейлист?", color = SpotifyColors.White) },
                text = {
                    Text(
                        "«${playlist.name}» будет удалён с сервера. Скачанные треки останутся в разделе «Скачанные».",
                        color = SpotifyColors.LightGray
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeletePlaylistDialog = false
                        viewModel.deletePlaylist { onBack() }
                    }) { Text("Удалить", color = SpotifyColors.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeletePlaylistDialog = false }) { Text("Отмена", color = SpotifyColors.LightGray) }
                }
            )
        }

        // Меню трека по «…»: добавить в плейлист, убрать из этого плейлиста, лайк, исключение
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
            onRemoveFromPlaylist = {
                val target = songMenu
                songMenu = null
                target?.let { viewModel.removeSongFromPlaylist(it.id) }
            },
            shareUrl = null
        )
        // Сама шторка выбора плейлиста (не рисует ничего, пока трек не выбран)
        AddToPlaylistHost(viewModel = addToPlaylistViewModel)
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}ч ${minutes}мин" else "${minutes}мин"
}
