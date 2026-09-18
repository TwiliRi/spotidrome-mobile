package com.sonicspot.player.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.model.SearchHistoryEntry
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.components.*
import com.sonicspot.player.ui.theme.*

@Composable
fun SearchScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val history by viewModel.searchHistory.collectAsState()
    val grouped by viewModel.groupedHistory.collectAsState()
    val listState = rememberLazyListState()
    var songToRemove by remember { mutableStateOf<Song?>(null) }

    // Spotify-style: история только при фокусе поля поиска
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // BackHandler: если поле в фокусе - сначала убираем фокус, а не выходим
    BackHandler(enabled = isSearchFocused) {
        focusManager.clearFocus()
        isSearchFocused = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (lastVisible != null && lastVisible >= total - 5) {
                    if (state.hasMoreSongs) viewModel.loadMoreSongs()
                    if (state.hasMoreAlbums) viewModel.loadMoreAlbums()
                    if (state.hasMoreArtists) viewModel.loadMoreArtists()
                }
            }
    }

    val browseCategories = remember {
        listOf(
            "Подкасты" to SpotifyColors.CategoryBlue,
            "Аудиокниги" to SpotifyColors.CategoryRed,
            "Made For You" to SpotifyColors.CategoryGreen,
            "Новинки" to SpotifyColors.CategoryOrange,
            "Чарты" to SpotifyColors.CategoryPink,
            "Рок" to Color(0xFF477D95),
            "Поп" to Color(0xFF8D67AB),
            "Хип-хоп" to Color(0xFFBA5D07),
            "Инди" to Color(0xFF148A08),
            "Электроника" to Color(0xFFD84000),
            "Джаз" to Color(0xFF8C1932),
            "Классика" to Color(0xFF777777)
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).statusBarsPadding()) {
                // Заголовок меняется как в Spotify: когда фокус - показываем "Поиск" меньше или кнопку назад
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isSearchFocused) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            focusManager.clearFocus()
                            isSearchFocused = false
                        }) {
                            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(SpotifyColors.Gray), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(text = "Поиск", style = SpotifyTextStyles.Greeting.copy(fontSize = 20.sp), color = SpotifyColors.White)
                        }
                    } else {
                        Text(text = "Поиск", style = SpotifyTextStyles.Greeting, color = SpotifyColors.White)
                    }
                }

                SpotifySearchBar(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    placeholder = "Что хочешь послушать?",
                    onFocusChanged = { focused -> isSearchFocused = focused },
                    onSearchSubmit = { q -> viewModel.onSearchSubmitted(q) },
                    focusRequester = focusRequester
                )
            }

            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SpotifyColors.White, trackColor = SpotifyColors.Gray)

            // ЛОГИКА КАК В SPOTIFY:
            // - Если запрос пустой и поле НЕ в фокусе -> показываем только Обзор (категории)
            // - Если запрос пустой и поле В ФОКУСЕ -> показываем историю поиска
            // - Если запрос не пустой -> показываем подсказки + результаты

            if (state.query.isEmpty()) {
                if (isSearchFocused) {
                    // История поиска только при нажатии на поле - как в Spotify
                    if (history.isEmpty()) {
                        // Пустая история - красивый placeholder
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
                                Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(SpotifyColors.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.History, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(40.dp))
                                }
                                Spacer(Modifier.height(16.dp))
                                Text("История пуста", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SpotifyColors.White)
                                Spacer(Modifier.height(8.dp))
                                Text("Твои недавние запросы появятся здесь", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray, modifier = Modifier.padding(horizontal = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Spacer(Modifier.height(24.dp))
                                Text("Начни вводить, и мы покажем подсказки из истории", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { /* поглощает клики */ },
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp)
                        ) {
                            item {
                                SmartSearchHistorySection(
                                    groupedHistory = grouped,
                                    onQueryClick = { q ->
                                        viewModel.onQueryChange(q)
                                    },
                                    onDelete = { q -> viewModel.removeFromHistory(q) },
                                    onClearAll = { viewModel.clearHistory() }
                                )
                            }
                        }
                    }
                } else {
                    // Обычный обзор - когда поле не в фокусе
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                            SectionHeaderModern(title = "Обзор")
                        }
                        items(browseCategories) { (title, color) ->
                            CategoryCard(title = title, color = color, onClick = {
                                viewModel.onQueryChange(title)
                                // При клике на категорию сразу фокусируем? Нет, просто ищем
                                focusRequester.requestFocus()
                            })
                        }
                    }
                }
            } else {
                // Когда есть запрос - показываем подсказки из истории + результаты (фокус не важен)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp)) {

                    if (state.suggestions.isNotEmpty()) {
                        item {
                            SmartSuggestionsSection(
                                suggestions = state.suggestions,
                                onSuggestionClick = { q -> viewModel.onQueryChange(q) },
                                onDelete = { q -> viewModel.removeFromHistory(q) }
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (state.artists.isNotEmpty()) {
                        item { SectionHeaderModern(title = "Исполнители • ${state.visibleArtists.size} из ${state.artists.size}") }
                        items(state.visibleArtists.chunked(2)) { row ->
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { artist ->
                                    ArtistCardModern(artist = artist, coverUrl = viewModel.getCoverUrl(artist.coverArt, 240), onClick = { onArtistClick(artist.id) }, modifier = Modifier.weight(1f))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (state.hasMoreArtists) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    Text("Загружаем ещё... • осталось ${state.artists.size - state.visibleArtistCount}", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                                }
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            SectionHeaderModern(title = "Альбомы • ${state.visibleAlbums.size} из ${state.albums.size}")
                        }
                        items(state.visibleAlbums.chunked(2)) { row ->
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { album ->
                                    AlbumCardModern(album = album, coverUrl = viewModel.getCoverUrl(album.coverArt, 304), onClick = { onAlbumClick(album.id) }, modifier = Modifier.weight(1f))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (state.hasMoreAlbums) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    Text("Ещё альбомов: ${state.albums.size - state.visibleAlbumCount}", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                                }
                            }
                        }
                    }
                    if (state.songs.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(16.dp))
                            SectionHeaderModern(title = "Треки • ${state.visibleSongs.size} из ${state.songs.size}")
                        }
                        items(state.visibleSongs, key = { it.id }) { song ->
                            val isPlaying = currentSongId?.id == song.id
                            val isLiked = likedIds.contains(song.id) || song.isStarred
                            val isDisliked = dislikedIds.contains(song.id)
                            SongRowModern(
                                song = song,
                                coverUrl = viewModel.getCoverUrl(song.coverArt, 88),
                                isPlaying = isPlaying,
                                isLiked = isLiked,
                                isDisliked = isDisliked,
                                showCover = true,
                                onClick = { val idx = state.songs.indexOf(song); viewModel.playSongs(state.songs, idx) },
                                onMore = {},
                                onLike = {
                                    if (isLiked) songToRemove = song
                                    else viewModel.toggleLike(song.id)
                                },
                                onDislike = { viewModel.toggleDislike(song.id) }
                            )
                        }
                        if (state.hasMoreSongs) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                                        Text("Загружаем ещё ${state.songs.size - state.visibleSongCount} треков...", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray)
                                    }
                                }
                            }
                        }
                    }
                    if (state.query.isNotEmpty() && !state.isLoading && state.isEmpty) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(48.dp)) {
                                Column {
                                    Text(text = "Ничего не найдено по запросу \"${state.query}\"\nПопробуй другое ключевое слово", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(16.dp))
                                    if (history.isNotEmpty()) {
                                        Text("Попробуй из недавних:", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp))
                                        Spacer(Modifier.height(8.dp))
                                        history.take(3).forEach { entry ->
                                            TextButton(onClick = { viewModel.onQueryChange(entry.query) }) {
                                                Icon(Icons.Default.History, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(entry.query, color = SpotifyColors.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

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

@Composable
private fun SmartSearchHistorySection(
    groupedHistory: Map<String, List<SearchHistoryEntry>>,
    onQueryClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.History, null, tint = SpotifyColors.Green, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Недавние запросы", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), color = SpotifyColors.White)
                    Text("${groupedHistory.values.sumOf { it.size }} запросов • умная сортировка", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = SpotifyColors.LightGray)
                }
            }
            TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Очистить", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
        }

        groupedHistory.forEach { (groupTitle, entries) ->
            Text(
                text = groupTitle.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                color = SpotifyColors.MediumGray,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.4f))
            ) {
                entries.forEachIndexed { idx, entry ->
                    SearchHistoryRow(
                        entry = entry,
                        onClick = { onQueryClick(entry.query) },
                        onDelete = { onDelete(entry.query) }
                    )
                    if (idx < entries.size - 1) {
                        HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SearchHistoryRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyColors.GrayLighter.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.History, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.query, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.timeAgoText(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
                if (entry.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(SpotifyColors.MediumGray))
                    Spacer(Modifier.width(6.dp))
                    Text("${entry.count} раза", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
                }
                Spacer(Modifier.width(6.dp))
                val score = entry.smartScore()
                if (score > 0.8) {
                    Icon(Icons.Default.Star, null, tint = SpotifyColors.Green.copy(alpha = 0.6f), modifier = Modifier.size(10.dp))
                }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(16.dp))
        }
        Icon(Icons.Default.NorthWest, null, tint = SpotifyColors.LightGray.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SmartSuggestionsSection(
    suggestions: List<SearchHistoryEntry>,
    onSuggestionClick: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lightbulb, null, tint = SpotifyColors.Green, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Подсказки из истории", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), color = SpotifyColors.White)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(SpotifyColors.Green.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("УМНО", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold), color = SpotifyColors.Green)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.4f))
        ) {
            suggestions.forEachIndexed { idx, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSuggestionClick(entry.query) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.query, color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.timeAgoText()} • ${entry.count} раза", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                    }
                    IconButton(onClick = { onDelete(entry.query) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(14.dp))
                    }
                }
                if (idx < suggestions.size - 1) {
                    HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 48.dp))
                }
            }
        }
    }
}
