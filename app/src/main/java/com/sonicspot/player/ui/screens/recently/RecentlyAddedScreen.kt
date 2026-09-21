package com.sonicspot.player.ui.screens.recently

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
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
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.components.ShimmerPlaceholder
import com.sonicspot.player.ui.components.SpotifyPullToRefreshBox
import com.sonicspot.player.ui.theme.*

enum class RecentlyDisplayMode {
    GRID_2,
    GRID_3,
    LIST
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentlyAddedScreen(
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: RecentlyAddedViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridState2 = rememberLazyGridState()
    val gridState3 = rememberLazyGridState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var displayMode by remember { mutableStateOf(RecentlyDisplayMode.GRID_2) }

    BackHandler(onBack = onBack)

    // Автоподгрузка при скролле для всех режимов
    LaunchedEffect(gridState2, displayMode) {
        if (displayMode != RecentlyDisplayMode.GRID_2) return@LaunchedEffect
        snapshotFlow { gridState2.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val total = gridState2.layoutInfo.totalItemsCount
                if (lastVisible != null && lastVisible >= total - 6 && state.hasMore && !state.isLoadingMore) {
                    viewModel.loadMore()
                }
            }
    }
    LaunchedEffect(gridState3, displayMode) {
        if (displayMode != RecentlyDisplayMode.GRID_3) return@LaunchedEffect
        snapshotFlow { gridState3.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val total = gridState3.layoutInfo.totalItemsCount
                if (lastVisible != null && lastVisible >= total - 8 && state.hasMore && !state.isLoadingMore) {
                    viewModel.loadMore()
                }
            }
    }
    LaunchedEffect(listState, displayMode) {
        if (displayMode != RecentlyDisplayMode.LIST) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (lastVisible != null && lastVisible >= total - 6 && state.hasMore && !state.isLoadingMore) {
                    viewModel.loadMore()
                }
            }
    }

    val gradient = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF1A1A1A), SpotifyColors.Black, SpotifyColors.Black),
            startY = 0f,
            endY = 800f
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        SpotifyPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().background(gradient)
        ) {
            when {
                state.isLoading -> {
                    // Shimmer grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(10, key = { "shimmer_$it" }, contentType = { "shimmer" }) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ShimmerPlaceholder(modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                                Spacer(Modifier.height(8.dp))
                                ShimmerPlaceholder(modifier = Modifier.fillMaxWidth().height(14.dp))
                                Spacer(Modifier.height(4.dp))
                                ShimmerPlaceholder(modifier = Modifier.fillMaxWidth(0.6f).height(10.dp))
                            }
                        }
                    }
                }
                state.albums.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Нет альбомов", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text("Обновить", color = SpotifyColors.Green)
                            }
                        }
                    }
                }
                else -> {
                    when (displayMode) {
                        RecentlyDisplayMode.GRID_2 -> {
                            LazyVerticalGrid(
                                state = gridState2,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(span = { GridItemSpan(2) }) {
                                    RecentlyHeader(
                                        count = state.albums.size,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it },
                                        onShuffle = { /* TODO shuffle all */ }
                                    )
                                }
                                items(state.albums, key = { it.id }) { album ->
                                    RecentlyAlbumGridItem(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 300),
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                                if (state.hasMore) {
                                    item(span = { GridItemSpan(2) }) {
                                        LoadingMoreFooter(isLoadingMore = state.isLoadingMore, total = state.albums.size)
                                    }
                                }
                            }
                        }
                        RecentlyDisplayMode.GRID_3 -> {
                            LazyVerticalGrid(
                                state = gridState3,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(span = { GridItemSpan(3) }) {
                                    RecentlyHeader(
                                        count = state.albums.size,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it },
                                        onShuffle = {}
                                    )
                                }
                                items(state.albums, key = { it.id }) { album ->
                                    RecentlyAlbumGridItemSmall(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 200),
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                                if (state.hasMore) {
                                    item(span = { GridItemSpan(3) }) {
                                        LoadingMoreFooter(isLoadingMore = state.isLoadingMore, total = state.albums.size)
                                    }
                                }
                            }
                        }
                        RecentlyDisplayMode.LIST -> {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item {
                                    RecentlyHeader(
                                        count = state.albums.size,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it },
                                        onShuffle = {}
                                    )
                                }
                                items(state.albums.size, key = { idx -> state.albums[idx].id }) { idx ->
                                    val album = state.albums[idx]
                                    RecentlyAlbumListItem(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 112),
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                                if (state.hasMore) {
                                    item {
                                        LoadingMoreFooter(isLoadingMore = state.isLoadingMore, total = state.albums.size)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.error != null) {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = SpotifyColors.Gray,
                    contentColor = SpotifyColors.White
                ) {
                    Text(state.error ?: "Ошибка загрузки")
                }
            }
        }
    }
}

@Composable
private fun RecentlyHeader(
    count: Int,
    displayMode: RecentlyDisplayMode,
    onBack: () -> Unit,
    onModeChange: (RecentlyDisplayMode) -> Unit,
    onShuffle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Недавно добавленные", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp))
                    Text("$count альбомов", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Shuffle, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        // Режимы отображения - как в Spotify, 3 кнопки
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Вид", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayModeChip(
                    icon = Icons.Default.GridView,
                    selected = displayMode == RecentlyDisplayMode.GRID_2,
                    onClick = { onModeChange(RecentlyDisplayMode.GRID_2) }
                )
                DisplayModeChip(
                    icon = Icons.Default.GridOn,
                    selected = displayMode == RecentlyDisplayMode.GRID_3,
                    onClick = { onModeChange(RecentlyDisplayMode.GRID_3) }
                )
                DisplayModeChip(
                    icon = Icons.Default.ViewList,
                    selected = displayMode == RecentlyDisplayMode.LIST,
                    onClick = { onModeChange(RecentlyDisplayMode.LIST) }
                )
            }
        }
    }
}

@Composable
private fun DisplayModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape)
            .background(if (selected) SpotifyColors.White else SpotifyColors.Gray)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (selected) SpotifyColors.Black else SpotifyColors.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RecentlyAlbumGridItem(
    album: com.sonicspot.player.data.model.Album,
    coverUrl: String?,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 6.dp, sizePx = 300)
        Spacer(Modifier.height(8.dp))
        Text(text = album.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = SpotifyColors.White, maxLines = 1)
        Text(text = album.artist ?: "Unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1)
    }
}

@Composable
private fun RecentlyAlbumGridItemSmall(
    album: com.sonicspot.player.data.model.Album,
    coverUrl: String?,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 4.dp, sizePx = 200)
        Spacer(Modifier.height(6.dp))
        Text(text = album.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp), color = SpotifyColors.White, maxLines = 1)
        Text(text = album.artist ?: "Unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = SpotifyColors.LightGray, maxLines = 1)
    }
}

@Composable
private fun RecentlyAlbumListItem(
    album: com.sonicspot.player.data.model.Album,
    coverUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = SpotifyColors.White, maxLines = 1)
            Text(album.artist ?: "Unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1)
            Text("${album.songCount} треков • ${album.year ?: ""}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = SpotifyColors.MediumGray, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LoadingMoreFooter(isLoadingMore: Boolean, total: Int) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (isLoadingMore) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                Text("Загружаем ещё...", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
            }
        } else {
            Text("Ещё $total загружено • листай дальше", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
        }
    }
}
