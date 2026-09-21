package com.sonicspot.player.ui.screens.favorites

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.components.ProvidePauseImageLoadsDuringScroll
import com.sonicspot.player.ui.components.RemoveLikeBottomSheet
import com.sonicspot.player.ui.components.SongRowModern
import com.sonicspot.player.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val listState = rememberLazyListState()
    val gradient = remember { Brush.verticalGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF2A2A2A), SpotifyColors.Black), startY = 0f, endY = 600f) }
    var songToRemove by remember { mutableStateOf<Song?>(null) }

    // Правильная пагинация: только когда доскроллили, distinctUntilChanged
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

        ProvidePauseImageLoadsDuringScroll(listState) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
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
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(
                                    Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Favorite, null, tint = SpotifyColors.White.copy(alpha = 0.9f), modifier = Modifier.size(64.dp))
                            }
                        }

                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = "Системный плейлист", style = MaterialTheme.typography.labelSmall.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontSize = 10.sp), modifier = Modifier.padding(bottom = 4.dp))
                            Text(text = "Любимые треки", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp), color = SpotifyColors.White)
                            Spacer(Modifier.height(6.dp))
                            Text(text = "${state.totalCount} треков • твоя коллекция избранного", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = SpotifyColors.LightGray)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(modifier = Modifier.size(28.dp).clickable { }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.DownloadForOffline, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(24.dp))
                                }
                                Box(modifier = Modifier.size(28.dp).clickable { }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Share, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
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

            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Треки • ${state.visibleSongs.size} из ${state.totalCount}", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    if (state.hasMore) Text("Ещё ${state.remainingCount}", style = MaterialTheme.typography.bodySmall.copy(color = SpotifyColors.Green, fontSize = 11.sp))
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
                    showCover = true,
                    onClick = { viewModel.playSongs(index) },
                    onMore = {},
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
                            Text("Загружаем ещё...", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                        }
                    }
                }
            }

            if (state.totalCount == 0) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FavoriteBorder, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Нет любимых треков", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                            Text("Ставь лайк на треки чтобы они появились здесь", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
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
    }
}
