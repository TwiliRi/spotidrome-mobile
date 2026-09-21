package com.sonicspot.player.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sonicspot.player.data.repository.LyricLine
import com.sonicspot.player.ui.components.AddToPlaylistHost
import com.sonicspot.player.ui.components.LibraryLabelUnderLyrics
import com.sonicspot.player.ui.random.PlayerCoverBounds
import com.sonicspot.player.ui.components.RemoveLikeBottomSheet
import com.sonicspot.player.ui.components.SleepTimerBottomSheet
import com.sonicspot.player.ui.components.SleepTimerButton
import com.sonicspot.player.ui.components.SleepTimerCompactIconButton
import com.sonicspot.player.ui.playlistadd.AddToPlaylistViewModel
import com.sonicspot.player.ui.theme.*
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(onClose: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val currentSong by viewModel.playerManager.currentSongFlow.collectAsState()
    val isPlaying by viewModel.playerManager.isPlayingFlow.collectAsState()
    val playerState by viewModel.playerManager.playerState.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val dislikedIds by viewModel.dislikedIds.collectAsState()
    val queue by viewModel.playerManager.queueFlow.collectAsState()
    val upcoming by viewModel.playerManager.upcomingQueue.collectAsState()
    val lyricsState by viewModel.lyricsState.collectAsState()
    val artistInfo by viewModel.artistInfo.collectAsState()
    // ВАЖНО: fullPlayerPositionFlow тикает на 10 Гц. Раньше он собирался ЗДЕСЬ, в корне
    // экрана — весь плеер (градиенты, обложка, слайдер, очередь) пересобирался 20 раз в
    // секунду и грел телефон до системного троттлинга. Теперь позицию собирают только
    // мелкие изолированные композиции (слайдер, блоки текстов).
    val libraryInfo by viewModel.libraryInfo.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()

    // Шторка «Добавить в плейлист» — общая для всех экранов с треками
    val addToPlaylistViewModel: AddToPlaylistViewModel = hiltViewModel()
    var showTrackOptions by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showFullscreenLyrics by remember { mutableStateOf(false) }
    var showRemoveLikeSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showFullscreenLyrics -> showFullscreenLyrics = false
            showRemoveLikeSheet -> showRemoveLikeSheet = false
            showTrackOptions -> showTrackOptions = false
            showQueueSheet -> showQueueSheet = false
            showSleepTimerSheet -> showSleepTimerSheet = false
            else -> onClose()
        }
    }

    val song = currentSong
    if (song == null) {
        Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black), contentAlignment = Alignment.Center) {
            Text("Ничего не играет", color = SpotifyColors.White)
        }
        return
    }

    val isLiked = likedIds.contains(song.id) || song.isStarred
    val isDisliked = dislikedIds.contains(song.id)
    val context = LocalContext.current
    // FIX: 600px для большого плеера (0.85f ширины ~300dp = 600px 2x).
    // ВАЖНО: allowHardware(false)+RGB_565 — это SOFTWARE-битмап, который графический
    // конвейер вынужден заливать в GL-текстуру на КАЖДОМ кадре. Прежний комментарий про
    // «избежать GPU upload» был ошибочным: ровно наоборот. HARDWARE-битмап живёт в
    // графической памяти и рисуется без аплоада — это и есть плавность.
    val coverUrlLarge = remember(song.coverArt) { viewModel.getCoverUrl(song.coverArt, 600) }
    val coverRequest = remember(coverUrlLarge, song.coverArt) {
        ImageRequest.Builder(context)
            .data(coverUrlLarge)
            .size(600)
            .crossfade(false)
            .memoryCacheKey("${song.coverArt}-600")
            .diskCacheKey("${song.coverArt}-600")
            .build()
    }
    val gradient = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A), SpotifyColors.Black, SpotifyColors.Black),
            startY = 0f, endY = 1200f
        )
    }

    val mainListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        Box(modifier = Modifier.fillMaxSize().background(gradient))

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = SpotifyColors.White, modifier = Modifier.size(28.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        when {
                            playerState.radioMode == com.sonicspot.player.player.RadioMode.TRACK -> "РАДИО ПО ТРЕКУ"
                            playerState.radioMode == com.sonicspot.player.player.RadioMode.ARTIST -> "РАДИО ПО ИСПОЛНИТЕЛЮ"
                            playerState.autoDjEnabled -> "AUTODJ • ${playerState.radioSource ?: "Авто"}"
                            else -> "ИГРАЕТ ИЗ ОЧЕРЕДИ"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(color = SpotifyColors.Green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 10.sp)
                    )
                    Text(song.album ?: "Unknown", style = MaterialTheme.typography.labelMedium.copy(color = SpotifyColors.White, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { showTrackOptions = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
            }

            LazyColumn(state = mainListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    SwipeableCover(
                        model = coverRequest,
                        onPrevious = { viewModel.playPrevious() },
                        onNext = { viewModel.playNext() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = SpotifyTextStyles.PlayerTitle.copy(fontSize = 22.sp), color = SpotifyColors.White, maxLines = 1)
                            Spacer(Modifier.height(4.dp))
                            Text(song.artist ?: "Unknown", style = MaterialTheme.typography.bodyMedium.copy(color = SpotifyColors.LightGray, fontSize = 15.sp), maxLines = 1)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Добавление в плейлист — та же шторка выбора, что и в списках треков
                            IconButton(
                                onClick = { addToPlaylistViewModel.open(song) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlaylistAdd,
                                    "Добавить в плейлист",
                                    tint = SpotifyColors.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.toggleDislike(song.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    if (isDisliked) Icons.Filled.ThumbDown else Icons.Default.ThumbDownOffAlt,
                                    null,
                                    tint = if (isDisliked) SpotifyColors.Red else SpotifyColors.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isLiked) showRemoveLikeSheet = true
                                    else viewModel.toggleLike(song.id)
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    if (isLiked) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                                    null,
                                    tint = if (isLiked) SpotifyColors.Green else SpotifyColors.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { IsolatedProgressSlider(viewModel = viewModel) }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).clickable { viewModel.toggleShuffle() }.background(if (playerState.shuffleEnabled) SpotifyColors.Green.copy(alpha = 0.2f) else Color.Transparent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Shuffle, null, tint = if (playerState.shuffleEnabled) SpotifyColors.Green else SpotifyColors.White, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.playPrevious() }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = SpotifyColors.White, modifier = Modifier.size(36.dp)) }
                        val playInteractionSource = remember { MutableInteractionSource() }
                        val isPlayPressed by playInteractionSource.collectIsPressedAsState()
                        val playButtonScale by animateFloatAsState(
                            targetValue = if (isPlayPressed) 0.86f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
                            label = "playPress"
                        )
                        var playIconScale by remember { mutableFloatStateOf(1f) }
                        var playIconFirstLaunch by remember { mutableStateOf(true) }
                        LaunchedEffect(isPlaying) {
                            if (playIconFirstLaunch) {
                                playIconFirstLaunch = false
                            } else {
                                playIconScale = 0.5f
                                animate(
                                    initialValue = 0.5f,
                                    targetValue = 1f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                ) { value, _ -> playIconScale = value }
                            }
                        }
                        IconButton(
                            onClick = { viewModel.togglePlayPause() },
                            interactionSource = playInteractionSource,
                            modifier = Modifier
                                .size(64.dp)
                                .graphicsLayer { scaleX = playButtonScale; scaleY = playButtonScale }
                                .clip(CircleShape)
                                .background(SpotifyColors.White)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint = SpotifyColors.Black,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { scaleX = playIconScale; scaleY = playIconScale }
                            )
                        }
                        IconButton(onClick = { viewModel.playNext() }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.SkipNext, null, tint = SpotifyColors.White, modifier = Modifier.size(36.dp)) }
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).clickable { viewModel.toggleRepeat() }.background(if (playerState.repeatMode != 0) SpotifyColors.Green.copy(alpha = 0.2f) else Color.Transparent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Repeat, null, tint = if (playerState.repeatMode != 0) SpotifyColors.Green else SpotifyColors.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (playerState.autoDjEnabled) SpotifyColors.Green.copy(alpha = 0.2f) else SpotifyColors.Gray).clickable { viewModel.toggleAutoDj() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Icon(Icons.Filled.AutoAwesome, null, tint = if (playerState.autoDjEnabled) SpotifyColors.Green else SpotifyColors.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("AutoDJ", color = if (playerState.autoDjEnabled) SpotifyColors.Green else SpotifyColors.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                SleepTimerCompactIconButton(sleepState = sleepTimerState, onClick = { showSleepTimerSheet = true })
                                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { showQueueSheet = true }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QueueMusic, null, tint = SpotifyColors.White, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        SleepTimerButton(sleepState = sleepTimerState, onClick = { showSleepTimerSheet = true }, modifier = Modifier.fillMaxWidth())
                    }
                    if (upcoming.isNotEmpty()) {
                        Text("Далее: ${upcoming.first().title} • ${upcoming.size} треков", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).clickable { showQueueSheet = true })
                    }
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                }
                item {
                    LyricsSection(
                        lyricsState = lyricsState,
                        positionFlow = viewModel.playerManager.fullPlayerPositionFlow,
                        libraryName = libraryInfo.libraryName ?: libraryInfo.selectedFolderName,
                        songPath = libraryInfo.songPath,
                        libraryId = libraryInfo.libraryId,
                        libraryPath = libraryInfo.libraryPath,
                        isExact = libraryInfo.isExact,
                        onRetry = { viewModel.retryLyrics() },
                        onOpenFullscreen = { showFullscreenLyrics = true },
                        onSeekTo = { ms -> viewModel.seekTo(ms) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp))
                }
                item {
                    ArtistInfoSection(
                        artistName = song.artist ?: "Unknown",
                        artistInfo = artistInfo,
                        // FIX: было 300px для всех, теперь размеры внутри секции: avatar 128px, topSongs 80px
                        coverUrlProvider = { id -> viewModel.getCoverUrl(id, 128) },
                        onSongClick = { _, idx -> viewModel.playerManager.playSongs(artistInfo.topSongs, idx) },
                        onArtistRadio = { viewModel.startArtistRadio(song.artist ?: "") },
                        artistShareUrl = viewModel.getArtistShareUrl(song.artistId ?: artistInfo.artistDetail?.id),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        AnimatedVisibility(
            visible = showFullscreenLyrics,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350)) + fadeOut(tween(250))
        ) {
            FullscreenLyricsScreen(
                songTitle = song.title,
                artistName = song.artist ?: "Unknown",
                coverUrl = coverUrlLarge,
                lyricsState = lyricsState,
                positionFlow = viewModel.playerManager.fullPlayerPositionFlow,
                isPlaying = isPlaying,
                onClose = { showFullscreenLyrics = false },
                onSeekTo = { ms -> viewModel.seekTo(ms) },
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.playNext() },
                onPrev = { viewModel.playPrevious() },
                onRetry = { viewModel.retryLyrics() }
            )
        }

        if (showTrackOptions) {
            TrackOptionsBottomSheet(
                song = song,
                // FIX: 56dp = 112px, было 200px -> waste 1.8x
                coverUrl = viewModel.getCoverUrl(song.coverArt, 112),
                isAutoDjEnabled = playerState.autoDjEnabled,
                isLiked = isLiked,
                isDisliked = isDisliked,
                onDismiss = { showTrackOptions = false },
                onAddToPlaylist = { showTrackOptions = false; addToPlaylistViewModel.open(song) },
                onAddToQueue = { viewModel.addToQueue(song); showTrackOptions = false },
                onAddNext = { viewModel.addNext(song); showTrackOptions = false },
                onGoToAlbum = { showTrackOptions = false },
                onGoToArtist = { showTrackOptions = false },
                onStartTrackRadio = { viewModel.startTrackRadio(song); showTrackOptions = false },
                onStartArtistRadio = { viewModel.startArtistRadio(song.artist ?: ""); showTrackOptions = false },
                onToggleAutoDj = { viewModel.toggleAutoDj() },
                onToggleLike = {
                    if (isLiked) {
                        showTrackOptions = false
                        showRemoveLikeSheet = true
                    } else {
                        viewModel.toggleLike(song.id)
                        showTrackOptions = false
                    }
                },
                onToggleDislike = { viewModel.toggleDislike(song.id); showTrackOptions = false }
            )
        }
        // Шторка выбора плейлиста: не рисует ничего, пока трек не выбран
        AddToPlaylistHost(viewModel = addToPlaylistViewModel)
        if (showQueueSheet) {
            QueueBottomSheet(currentSong = song, queue = queue, upcoming = upcoming, currentIndex = playerState.currentIndex, onDismiss = { showQueueSheet = false }, onPlayIndex = { idx -> viewModel.playQueueIndex(idx); showQueueSheet = false }, onRemoveIndex = { idx -> viewModel.removeFromQueue(idx) }, onClearQueue = { viewModel.clearQueue() }, onMove = { from, to -> viewModel.moveQueueItem(from, to) })
        }
        if (showRemoveLikeSheet) {
            RemoveLikeBottomSheet(
                song = song,
                // FIX: 88dp = 176px, было 200px
                coverUrl = viewModel.getCoverUrl(song.coverArt, 176),
                onDismiss = { showRemoveLikeSheet = false },
                onRemove = { viewModel.toggleLike(song.id) }
            )
        }
        if (showSleepTimerSheet) {
            SleepTimerBottomSheet(
                sleepState = sleepTimerState,
                onDismiss = { showSleepTimerSheet = false },
                onSetTimer = { minutes -> viewModel.setSleepTimer(minutes) },
                onCancel = { viewModel.cancelSleepTimer() }
            )
        }
    }
}

@Composable
private fun LyricsSection(
    lyricsState: LyricsUiState,
    positionFlow: StateFlow<Long>,
    libraryName: String? = null,
    songPath: String? = null,
    libraryId: Int? = null,
    libraryPath: String? = null,
    isExact: Boolean = false,
    onRetry: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Текст песни", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val sourceLabel = when ((lyricsState as? LyricsUiState.Success)?.result?.source) {
                    "embedded" -> "Встроенный"
                    "server" -> "Navidrome"
                    else -> "LRCLIB"
                }
                val sourceColor = when ((lyricsState as? LyricsUiState.Success)?.result?.source) {
                    "embedded" -> SpotifyColors.Green
                    "server" -> SpotifyColors.Purple
                    else -> SpotifyColors.LightGray
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(sourceLabel, color = sourceColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold))
                }
                // Кнопка повторной загрузки - всегда видна кроме Loading
                if (lyricsState !is LyricsUiState.Loading) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(SpotifyColors.Gray.copy(alpha = 0.8f)).clickable { onRetry() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = SpotifyColors.White, modifier = Modifier.size(18.dp))
                    }
                }
                if (lyricsState is LyricsUiState.Success) {
                    Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(SpotifyColors.White).clickable { onOpenFullscreen() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Fullscreen, null, tint = SpotifyColors.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        when (lyricsState) {
            is LyricsUiState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SpotifyColors.Green, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Ищем текст...", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is LyricsUiState.Empty -> {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.5f)).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Текст не найден", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Text("Попробуй поискать снова — иногда текст появляется позже", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Попробовать ещё раз", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp))
                        }
                    }
                }
            }
            is LyricsUiState.Error -> {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.5f)).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, tint = SpotifyColors.Red.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(lyricsState.message, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Повторить", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            is LyricsUiState.Success -> {
                val result = lyricsState.result
                if (result.isSynced && result.syncedLines.isNotEmpty()) {
                    SyncedLyricsPreview(lines = result.syncedLines, positionFlow = positionFlow, onSeekTo = onSeekTo, onOpenFullscreen = onOpenFullscreen)
                } else {
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.6f)).padding(16.dp).clickable { onOpenFullscreen() }) {
                        Column {
                            Text(result.plainLyrics ?: "Нет текста", color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), maxLines = 8)
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onRetry) {
                                    Icon(Icons.Default.Refresh, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Обновить", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // Надпись под блоком текста - точная музыкальная библиотека Navidrome из /api/song/{id} -> libraryId
        Spacer(Modifier.height(8.dp))
        LibraryLabelUnderLyrics(
            libraryName = libraryName,
            songPath = songPath,
            libraryId = libraryId,
            libraryPath = libraryPath,
            isExact = isExact
        )
    }
}

private fun findLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timestampMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/**
 * Обёртка превью текста. Подписка на позицию (тик 10 Гц) живёт ТОЛЬКО в LaunchedEffect
 * и пишет лишь номер текущей строки. Контент — отдельная скippable-композиция:
 * она пересобирается исключительно при смене строки, а не на каждый тик.
 */
@Composable
private fun SyncedLyricsPreview(
    lines: List<LyricLine>,
    positionFlow: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines) {
        positionFlow.collect { pos ->
            val idx = findLyricIndex(lines, pos)
            if (idx != currentIndex) currentIndex = idx
        }
    }
    SyncedLyricsPreviewContent(
        lines = lines,
        currentIndex = currentIndex,
        onSeekTo = onSeekTo,
        onOpenFullscreen = onOpenFullscreen,
        modifier = modifier
    )
}

@Composable
private fun SyncedLyricsPreviewContent(
    lines: List<LyricLine>,
    currentIndex: Int,
    onSeekTo: (Long) -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            try {
                listState.animateScrollToItem(
                    (currentIndex - 1).coerceAtLeast(0),
                    scrollOffset = -20
                )
            } catch (_: Exception) {}
        }
    }
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.6f)).clickable { onOpenFullscreen() }) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), userScrollEnabled = false) {
            itemsIndexed(lines, key = { idx, line -> "${line.timestampMs}-${idx}" }) { idx, line ->
                val isCurrent = idx == currentIndex
                val isPast = idx < currentIndex
                val textColor by animateColorAsState(
                    targetValue = when {
                        isCurrent -> SpotifyColors.White
                        isPast -> SpotifyColors.LightGray.copy(alpha = 0.6f)
                        else -> SpotifyColors.MediumGray
                    },
                    animationSpec = tween(200), label = "color"
                )
                val fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                val fontSize = if (isCurrent) 15.sp else 13.sp
                Text(
                    line.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = fontWeight, fontSize = fontSize),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .then(if (isCurrent) Modifier.background(SpotifyColors.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)).padding(6.dp) else Modifier)
                        .clickable { onSeekTo(line.timestampMs) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(SpotifyColors.Gray.copy(alpha = 0.8f), Color.Transparent))))
        Box(modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, SpotifyColors.Gray.copy(alpha = 0.8f)))))
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Fullscreen, null, tint = SpotifyColors.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Полный экран", color = SpotifyColors.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
            }
        }
    }
}

@Composable
private fun FullscreenLyricsScreen(
    songTitle: String,
    artistName: String,
    coverUrl: String?,
    lyricsState: LyricsUiState,
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    onClose: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    // FIX: фон с «размытием» без Modifier.blur. Полноэкранный blur(20.dp) — один из
    // самых дорогих эффектов на Android (полный проход RenderEffect по экрану на каждом
    // кадре; на API < 31 вообще software). Плюс фон был SOFTWARE-битмапом.
    // Приём как у Spotify: декодируем КРОШЕЧНУЮ копию (64px, HARDWARE) и растягиваем
    // билинейно (FilterQuality.Medium) — визуально тяжёлый blur стоит почти ноль.
    val coverRequestSmall = remember(coverUrl) {
        ImageRequest.Builder(context)
            .data(coverUrl)
            .size(64)
            .crossfade(false)
            .memoryCacheKey("${coverUrl}-blur64")
            .diskCacheKey("${coverUrl}-blur64")
            .build()
    }

    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
        AsyncImage(
            model = coverRequestSmall,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            filterQuality = FilterQuality.Medium
        )
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.8f), SpotifyColors.Black))))

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Icon(Icons.Default.Close, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text("ТЕКСТ", color = SpotifyColors.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    Text(songTitle, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(artistName, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), maxLines = 1)
                }
                // Кнопка повторной загрузки текста - Spotify style
                IconButton(onClick = onRetry, modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Icon(Icons.Default.Refresh, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (lyricsState) {
                    is LyricsUiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = SpotifyColors.White, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Загружаем текст...", color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    is LyricsUiState.Empty -> {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Lyrics, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Текст не найден", color = SpotifyColors.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(8.dp))
                                Text("Для этого трека нет текста в базе. Попробуй обновить — иногда текст появляется позже.", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Попробовать ещё раз", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    is LyricsUiState.Error -> {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ErrorOutline, null, tint = SpotifyColors.Red.copy(alpha = 0.8f), modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(lyricsState.message, color = SpotifyColors.LightGray, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Попробовать ещё раз", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    is LyricsUiState.Success -> {
                        val result = lyricsState.result
                        if (result.isSynced) {
                            FullscreenSyncedLyrics(lines = result.syncedLines, positionFlow = positionFlow, onSeekTo = onSeekTo)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                                item {
                                    Text(result.plainLyrics ?: "", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }

            FullscreenBottomControls(
                positionFlow = positionFlow,
                lyricsState = lyricsState,
                isPlaying = isPlaying,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext
            )
        }
    }
}

/**
 * Нижняя панель полноэкранного текста: единственное место на этом экране, где позиция
 * (10 Гц) вызывает рекомпозицию — и только этой крошечной панели с тремя кнопками.
 */
@Composable
private fun FullscreenBottomControls(
    positionFlow: StateFlow<Long>,
    lyricsState: LyricsUiState,
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val positionMs by positionFlow.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 24.dp, vertical = 16.dp)) {
        val progress = if (lyricsState is LyricsUiState.Success) {
            val total = (lyricsState.result.syncedLines.lastOrNull()?.timestampMs ?: 0L).coerceAtLeast(1L)
            (positionMs.toFloat() / total).coerceIn(0f, 1f)
        } else 0f
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.White.copy(alpha = 0.2f))) {
            Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.White))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = SpotifyColors.White, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp).clip(CircleShape).background(SpotifyColors.White)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = SpotifyColors.Black, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.SkipNext, null, tint = SpotifyColors.White, modifier = Modifier.size(28.dp)) }
        }
    }
}

/**
 * Обёртка полноэкранного текста: тик позиции обновляет только номер строки
 * (LaunchedEffect без рекомпозиции), контент пересобирается при смене строки.
 */
@Composable
private fun FullscreenSyncedLyrics(
    lines: List<LyricLine>,
    positionFlow: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines) {
        positionFlow.collect { pos ->
            val idx = findLyricIndex(lines, pos)
            if (idx != currentIndex) currentIndex = idx
        }
    }
    FullscreenSyncedLyricsContent(lines = lines, currentIndex = currentIndex, onSeekTo = onSeekTo, modifier = modifier)
}

@Composable
private fun FullscreenSyncedLyricsContent(
    lines: List<LyricLine>,
    currentIndex: Int,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            try {
                listState.animateScrollToItem(
                    (currentIndex - 2).coerceAtLeast(0),
                    scrollOffset = -100
                )
            } catch (_: Exception) {}
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(lines, key = { idx, l -> "${l.timestampMs}-${idx}" }) { idx, line ->
            val isCurrent = idx == currentIndex
            val isPast = idx < currentIndex

            val color by animateColorAsState(
                targetValue = when {
                    isCurrent -> Color.White
                    isPast -> Color.White.copy(alpha = 0.6f)
                    else -> Color.White.copy(alpha = 0.35f)
                },
                animationSpec = tween(250), label = "color"
            )
            val fontSize by animateFloatAsState(
                targetValue = if (isCurrent) 26f else 20f,
                animationSpec = tween(250), label = "fontSize"
            )
            val alpha by animateFloatAsState(
                targetValue = if (isCurrent) 1f else if (isPast) 0.6f else 0.4f,
                animationSpec = tween(250), label = "alpha"
            )

            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSeekTo(line.timestampMs) }
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .then(
                        if (isCurrent) Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(16.dp)
                        else Modifier.padding(12.dp)
                    )
            ) {
                Text(
                    text = line.text,
                    color = color.copy(alpha = alpha),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = fontSize.sp,
                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                        lineHeight = (fontSize + 6).sp,
                        letterSpacing = 0.2.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun ArtistInfoSection(
    artistName: String,
    artistInfo: ArtistInfoState,
    coverUrlProvider: (String?) -> String?,
    onSongClick: (com.sonicspot.player.data.model.Song, Int) -> Unit,
    onArtistRadio: () -> Unit,
    artistShareUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun shareArtist() {
        // Копируем в буфер обмена: имя автора + ссылка на его страницу в Navidrome. И всё.
        val text = if (artistShareUrl.isNullOrBlank()) artistName else "$artistName\n$artistShareUrl"
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Исполнитель", text))
        android.widget.Toast.makeText(context, "Ссылка скопирована", android.widget.Toast.LENGTH_SHORT).show()
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text("Об исполнителе", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray).padding(16.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))), contentAlignment = Alignment.Center) {
                        val cover = artistInfo.artistDetail?.coverArt?.let { coverUrlProvider(it) }
                        if (cover != null) com.sonicspot.player.ui.components.CoverArtImage(url = cover, modifier = Modifier.fillMaxSize(), cornerRadius = 32.dp, sizePx = 128)
                        else Text(artistName.take(1).uppercase(), color = SpotifyColors.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artistName, color = SpotifyColors.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        if (artistInfo.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                        } else {
                            val albumCount = artistInfo.artistDetail?.albumCount ?: artistInfo.albums.size
                            Text(if (albumCount > 0) "$albumCount альбомов • ${artistInfo.topSongs.size} треков" else "Исполнитель", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onArtistRadio, colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black), shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Radio, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Радио", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { shareArtist() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyColors.White), shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Поделиться")
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (artistInfo.topSongs.isNotEmpty()) {
            Text("Ещё треки $artistName", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp), modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                artistInfo.topSongs.take(5).forEachIndexed { idx, song ->
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Gray.copy(alpha = 0.3f)).clickable { onSongClick(song, idx) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter), contentAlignment = Alignment.Center) {
                            val cover = coverUrlProvider(song.coverArt)
                            if (cover != null) com.sonicspot.player.ui.components.CoverArtImage(url = cover, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 80)
                            else Icon(Icons.Default.MusicNote, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), maxLines = 1)
                            Text(song.album ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1)
                        }
                        Icon(Icons.Default.PlayArrow, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableCover(
    model: Any?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // Радиус скругления цели для перелёта обложки — в пикселях, поэтому нужна плотность
    val coverCornerPx = with(LocalDensity.current) { 8.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var coverWidthPx by remember { mutableFloatStateOf(1f) }
    var animating by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = "Обложка трека. Свайп влево — следующий трек, вправо — предыдущий",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1f)
                .onSizeChanged { coverWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                // «Финиш» перелёта обложки случайного трека: сцена чёрной дыры целится сюда
                .onGloballyPositioned { coords ->
                    PlayerCoverBounds.update(coords.boundsInWindow(), coverCornerPx)
                }
                .graphicsLayer {
                    translationX = offsetX
                    rotationZ = (offsetX / coverWidthPx) * 7f
                    alpha = 1f - (abs(offsetX) / coverWidthPx).coerceIn(0f, 1f) * 0.4f
                }
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (!animating) velocityTracker.resetTracking()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!animating) {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                offsetX += dragAmount
                            }
                        },
                        onDragEnd = {
                            if (animating) return@detectHorizontalDragGestures
                            val velocityX = velocityTracker.calculateVelocity().x
                            val distanceThreshold = coverWidthPx * 0.22f
                            val flingThreshold = 1200f
                            val goNext = offsetX <= -distanceThreshold || velocityX <= -flingThreshold
                            val goPrevious = offsetX >= distanceThreshold || velocityX >= flingThreshold
                            if (goNext || goPrevious) {
                                animating = true
                                scope.launch {
                                    // Шаг 1: уводим обложку за край экрана
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = if (goNext) -coverWidthPx * 1.2f else coverWidthPx * 1.2f,
                                        animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing)
                                    ) { value, _ -> offsetX = value }
                                    // Шаг 2: переключаем трек
                                    if (goNext) onNext() else onPrevious()
                                    // Шаг 3: новая обложка «приезжает» с небольшим заходом с противоположной стороны
                                    offsetX = if (goNext) coverWidthPx * 0.35f else -coverWidthPx * 0.35f
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ) { value, _ -> offsetX = value }
                                    animating = false
                                }
                            } else {
                                animating = true
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) { value, _ -> offsetX = value }
                                    animating = false
                                }
                            }
                        },
                        onDragCancel = {
                            if (!animating) {
                                animating = true
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) { value, _ -> offsetX = value }
                                    animating = false
                                }
                            }
                        }
                    )
                }
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackOptionsBottomSheet(
    song: com.sonicspot.player.data.model.Song,
    coverUrl: String?,
    isAutoDjEnabled: Boolean,
    isLiked: Boolean,
    isDisliked: Boolean = false,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNext: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onStartTrackRadio: () -> Unit,
    onStartArtistRadio: () -> Unit,
    onToggleAutoDj: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SpotifyColors.Gray, contentColor = SpotifyColors.White, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter)) { if (coverUrl != null) com.sonicspot.player.ui.components.CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 112) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(song.artist ?: "Unknown", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            // Главное действие: тот же лист выбора плейлиста, что и в списках треков
            BottomSheetItem(
                icon = Icons.Default.PlaylistAdd,
                title = "Добавить в плейлист",
                subtitle = "Выбрать плейлист или создать новый",
                onClick = onAddToPlaylist
            )
            BottomSheetItem(icon = Icons.Default.QueueMusic, title = "Добавить в очередь", onClick = onAddToQueue)
            BottomSheetItem(icon = Icons.Default.SkipNext, title = "Играть следующим", onClick = onAddNext)
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetItem(icon = Icons.Filled.Radio, title = "Радио по треку", subtitle = "Похожие треки", onClick = onStartTrackRadio)
            BottomSheetItem(icon = Icons.Filled.Podcasts, title = "Радио по исполнителю", subtitle = "Треки ${song.artist}", onClick = onStartArtistRadio)
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            BottomSheetItem(icon = Icons.Filled.AutoAwesome, title = if (isAutoDjEnabled) "Выключить AutoDJ" else "Включить AutoDJ", subtitle = "Авто добавление похожих", onClick = onToggleAutoDj)
            BottomSheetItem(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                title = if (isLiked) "Удалить из Любимых треков" else "Добавить в Любимые треки",
                isDestructive = false,
                onClick = onToggleLike
            )
            BottomSheetItem(
                icon = if (isDisliked) Icons.Filled.ThumbDown else Icons.Default.ThumbDownOffAlt,
                title = if (isDisliked) "Убрать из исключённых" else "Исключить трек",
                subtitle = "Не будет в рекомендациях • плейлист «Исключённые треки»",
                isDestructive = isDisliked,
                onClick = onToggleDislike
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(currentSong: com.sonicspot.player.data.model.Song, queue: List<com.sonicspot.player.data.model.Song>, upcoming: List<com.sonicspot.player.data.model.Song>, currentIndex: Int, onDismiss: () -> Unit, onPlayIndex: (Int) -> Unit, onRemoveIndex: (Int) -> Unit, onClearQueue: () -> Unit, onMove: (Int, Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SpotifyColors.Gray, contentColor = SpotifyColors.White, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Очередь", color = SpotifyColors.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Text("${queue.size} треков • сейчас ${currentIndex + 1}", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClearQueue) { Text("Очистить", color = SpotifyColors.LightGray) }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(upcoming, key = { _, s -> s.id }) { idx, s ->
                    val actualIndex = currentIndex + 1 + idx
                    Row(modifier = Modifier.fillMaxWidth().clickable { onPlayIndex(actualIndex) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${actualIndex + 1}", color = SpotifyColors.MediumGray, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = SpotifyColors.White, maxLines = 1); Text(s.artist ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        IconButton(onClick = { onRemoveIndex(actualIndex) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, isDestructive: Boolean = false, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1)
        }
    }
}

@Composable
private fun IsolatedProgressSlider(viewModel: PlayerViewModel) {
    val progress by viewModel.playerManager.fullPlayerProgressFlow.collectAsState()
    val position by viewModel.playerManager.fullPlayerPositionFlow.collectAsState()
    val playerState by viewModel.playerManager.playerState.collectAsState()
    Column {
        Slider(value = progress, onValueChange = { newProgress -> viewModel.seekTo((newProgress * playerState.duration).toLong()) }, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = SpotifyColors.White, activeTrackColor = SpotifyColors.White, inactiveTrackColor = SpotifyColors.White.copy(alpha = 0.3f)))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
            Text(formatTime(playerState.duration), color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
