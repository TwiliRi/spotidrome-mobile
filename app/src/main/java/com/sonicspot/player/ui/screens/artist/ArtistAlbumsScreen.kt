package com.sonicspot.player.ui.screens.artist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.components.ShimmerPlaceholder
import com.sonicspot.player.ui.components.SpotifyPullToRefreshBox
import com.sonicspot.player.ui.theme.SpotifyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Режим отображения — как на странице «Недавно добавленные». */
private enum class ArtistAlbumsDisplayMode {
    GRID_2,
    GRID_3,
    LIST
}

@Immutable
data class ArtistAlbumsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val title: String = "",
    val artistName: String = "",
    val albums: List<Album> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ArtistAlbumsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistAlbumsUiState())
    val uiState: StateFlow<ArtistAlbumsUiState> = _uiState.asStateFlow()

    private var lastArtistId: String? = null
    private var lastReleaseType: String = ReleaseTypes.ALBUMS

    fun load(artistId: String, releaseType: String) {
        lastArtistId = artistId
        lastReleaseType = releaseType
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetch(artistId, releaseType, forceRefresh = false)
        }
    }

    /** Pull-to-refresh и кнопка «Обновить»: перезагрузка с сервера. */
    fun refresh() {
        val id = lastArtistId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            fetch(id, lastReleaseType, forceRefresh = true)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    private suspend fun fetch(artistId: String, releaseType: String, forceRefresh: Boolean) {
        repository.getArtist(artistId, forceRefresh)
            .onSuccess { artist ->
                val discography = DiscographyClassifier.group(artist)
                val (title, albums) = when (releaseType) {
                    ReleaseTypes.EPS -> "EP" to discography.eps
                    ReleaseTypes.SINGLES -> "Синглы" to discography.singles
                    ReleaseTypes.APPEARS_ON -> "Участие" to discography.appearsOn
                    else -> "Альбомы" to discography.albums
                }
                _uiState.value = ArtistAlbumsUiState(
                    isLoading = false,
                    isRefreshing = _uiState.value.isRefreshing,
                    title = title,
                    artistName = artist.name,
                    albums = albums
                )
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
}

/**
 * Страница со всеми релизами одного типа из профиля артиста
 * (открывается по кнопке «Показать все» у группы дискографии).
 * Оформление повторяет страницу «Недавно добавленные»: та же шапка,
 * переключатель вида (сетка 2 / сетка 3 / список), те же карточки.
 */
@Composable
fun ArtistAlbumsScreen(
    artistId: String,
    releaseType: String,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    viewModel: ArtistAlbumsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridState2 = rememberLazyGridState()
    val gridState3 = rememberLazyGridState()
    val listState = rememberLazyListState()
    var displayMode by remember { mutableStateOf(ArtistAlbumsDisplayMode.GRID_2) }

    BackHandler(onBack = onBack)

    LaunchedEffect(artistId, releaseType) { viewModel.load(artistId, releaseType) }

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
                    // Shimmer grid — как на «Недавно добавленные»
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
                            Text(
                                if (state.error != null) "Не удалось загрузить релизы" else "Здесь пока ничего нет",
                                color = SpotifyColors.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text("Обновить", color = SpotifyColors.Green)
                            }
                        }
                    }
                }
                else -> {
                    val subtitle = listOfNotNull(
                        state.artistName.takeIf { it.isNotBlank() },
                        pluralRu(state.albums.size, "релиз", "релиза", "релизов")
                    ).joinToString(" • ")

                    when (displayMode) {
                        ArtistAlbumsDisplayMode.GRID_2 -> {
                            LazyVerticalGrid(
                                state = gridState2,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(span = { GridItemSpan(2) }, key = "header") {
                                    ArtistAlbumsHeader(
                                        title = state.title,
                                        subtitle = subtitle,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it }
                                    )
                                }
                                items(state.albums, key = { it.id }) { album ->
                                    ArtistAlbumGridItem(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 300),
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                            }
                        }
                        ArtistAlbumsDisplayMode.GRID_3 -> {
                            LazyVerticalGrid(
                                state = gridState3,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(span = { GridItemSpan(3) }, key = "header") {
                                    ArtistAlbumsHeader(
                                        title = state.title,
                                        subtitle = subtitle,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it }
                                    )
                                }
                                items(state.albums, key = { it.id }) { album ->
                                    ArtistAlbumGridItemSmall(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 200),
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                            }
                        }
                        ArtistAlbumsDisplayMode.LIST -> {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "header") {
                                    ArtistAlbumsHeader(
                                        title = state.title,
                                        subtitle = subtitle,
                                        displayMode = displayMode,
                                        onBack = onBack,
                                        onModeChange = { displayMode = it }
                                    )
                                }
                                items(state.albums, key = { it.id }) { album ->
                                    ArtistAlbumListItem(
                                        album = album,
                                        coverUrl = viewModel.getCoverUrl(album.coverArt, 112),
                                        onClick = { onAlbumClick(album.id) }
                                    )
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

/** Шапка страницы — как на «Недавно добавленные»: круглый назад, заголовок и переключатель вида. */
@Composable
private fun ArtistAlbumsHeader(
    title: String,
    subtitle: String,
    displayMode: ArtistAlbumsDisplayMode,
    onBack: () -> Unit,
    onModeChange: (ArtistAlbumsDisplayMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title.ifEmpty { "Релизы" },
                    color = SpotifyColors.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    maxLines = 1
                )
                Text(
                    subtitle,
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1
                )
            }
        }
        // Режимы отображения — как в Spotify, 3 кнопки
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Вид",
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayModeChip(
                    icon = Icons.Default.GridView,
                    selected = displayMode == ArtistAlbumsDisplayMode.GRID_2,
                    onClick = { onModeChange(ArtistAlbumsDisplayMode.GRID_2) }
                )
                DisplayModeChip(
                    icon = Icons.Default.GridOn,
                    selected = displayMode == ArtistAlbumsDisplayMode.GRID_3,
                    onClick = { onModeChange(ArtistAlbumsDisplayMode.GRID_3) }
                )
                DisplayModeChip(
                    icon = Icons.Default.ViewList,
                    selected = displayMode == ArtistAlbumsDisplayMode.LIST,
                    onClick = { onModeChange(ArtistAlbumsDisplayMode.LIST) }
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
private fun ArtistAlbumGridItem(
    album: Album,
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
private fun ArtistAlbumGridItemSmall(
    album: Album,
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
private fun ArtistAlbumListItem(
    album: Album,
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
