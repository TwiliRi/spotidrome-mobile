package com.sonicspot.player.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.sonicspot.player.util.CoverArt
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.Artist
import com.sonicspot.player.data.model.Playlist
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged

// ==================== Глобальный флаг "приостанови загрузку обложек" ====================
//
// Проблема: во время быстрого флинга LazyColumn/LazyRow в зоне видимости на мгновение
// появляются десятки ячеек. Coil немедленно начинает для всех фетч + декод, забивает
// все ядра декодами и роняет fps. Spotify/Instagram/Google Photos на флинге просто
// приостанавливают новые загрузки и показывают плейсхолдер; как только скролл
// останавливается — начинают подгружать видимые. Это даёт 60fps на флинге.
//
// Использование: оборачиваем список в ProvidePauseImageLoadsDuringScroll(listState) { ... }
// и флаг сам становится true во время скролла.
val LocalPauseImageLoads = compositionLocalOf { false }

/**
 * Ставит LocalPauseImageLoads в true, пока список находится в движении (флинг или скролл).
 * Когда скролл заканчивается — становится false, и Coil начинает грузить то что видно.
 */
@Composable
fun ProvidePauseImageLoadsDuringScroll(
    listState: androidx.compose.foundation.lazy.LazyListState,
    content: @Composable () -> Unit
) {
    val isScrolling by remember {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
    }.collectAsState(initial = listState.isScrollInProgress)

    CompositionLocalProvider(LocalPauseImageLoads provides isScrolling) {
        content()
    }
}

@Composable
fun ProvidePauseImageLoadsDuringScroll(
    listState: androidx.compose.foundation.lazy.grid.LazyGridState,
    content: @Composable () -> Unit
) {
    val isScrolling by remember {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
    }.collectAsState(initial = listState.isScrollInProgress)

    CompositionLocalProvider(LocalPauseImageLoads provides isScrolling) {
        content()
    }
}

// ==================== COVER ART - 60 FPS, HARDWARE БИТМАПЫ, НЕТ ПЕРЕЗАГРУЗКИ ====================
//
// Что было не так в прошлой версии:
//   1) RGB_565 + глобальный allowHardware(false) → software-битмапы в куче, вытесняются,
//      аплоад на GPU каждый кадр, фреймдроп при скролле и "перезагрузка обложек".
//   2) filterQuality=Low — мылит картинки на всех современных устройствах.
//   3) Нет паузы загрузок на флинге — 47 обложек одновременно декодятся.
//   4) На каждый рекомпоз строились новые объекты (key parsing) хоть и в remember.
//
// Что теперь:
//   • HARDWARE битмапы (ARGB_8888) — в графической памяти, не GC, не аплоад.
//   • Ключи диска/памяти построены один раз через remember(url, sizePx).
//   • При флинге новые загрузки ставятся на паузу (пока только видимые после остановки).
//   • Плейсхолдер серый всегда есть → нет скачка лейаута.
@Composable
fun CoverArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    cornerRadius: Dp = 6.dp,
    sizePx: Int = 300
) {
    val context = LocalContext.current
    val pauseLoads = LocalPauseImageLoads.current

    // Ключ диска — по ID обложки + квантованному размеру (LIST/LARGE), а не по всему URL
    // с солью (и тогда одна и та же обложка, скачанная до и после перелогина, лежит в
    // одном файле на диске). Память — с учётом отображаемого sizePx, но HARDWARE
    // битмапы разделимы по размеру только на уровне декодера, так что memory-key
    // всё ещё должен учитывать sizePx.
    val cacheKeys = remember(url, sizePx) {
        if (url == null) null
        else try {
            val id = url.substringAfter("id=").substringBefore("&").ifEmpty { url.hashCode().toString() }
            val bucket = url.substringAfter("size=", "").substringBefore("&")
                .ifEmpty { CoverArt.LIST.toString() }
            val diskKey = "$id-$bucket"
            val memoryKey = "$diskKey@$sizePx"
            diskKey to memoryKey
        } catch (_: Exception) {
            val key = "${url}-${CoverArt.LIST}"
            key to "$key@$sizePx"
        }
    }

    val imageRequest = remember(url, sizePx, cacheKeys, pauseLoads) {
        val (diskKey, memoryKey) = cacheKeys ?: return@remember null
        ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .crossfade(false)
            // ========== ГЛАВНЫЙ ФИКС: HARDWARE битмапы ==========
            // Не ставим .bitmapConfig и не запрещаем hardware — Coil сам выбирает
            // оптимальный HARDWARE/ARGB_8888. Это убирает аплоад на GPU каждый кадр
            // и снимает давление с Java heap — основная причина и лагов, и
            // "перезагрузки обложек" при скролле.
            .memoryCacheKey(memoryKey)
            .diskCacheKey(diskKey)
            // Во время флинга — только кэш (память + диск), не ходить в сеть и
            // не декодить не закэшированное. Как только скролл останавливается,
            // флаг снимается, и новые видимые картинки догружаются в спокойном
            // режиме. При этом попадания в кэш работают мгновенно, так что уже
            // загруженные обложки не исчезают и не перезагружаются.
            .networkCachePolicy(if (pauseLoads) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .build()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(SpotifyColors.Gray)
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // FilterQuality.None на HARDWARE битмапах даёт самое быстрое
                // масштабирование (nearest-neighbor). При HARDWARE пиксели не
                // читаются, так что bilinear всё равно бы упал в программную
                // перерисовку.
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
            )
        }
    }
}

// ==================== QUICK ACCESS CARD ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickAccessCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SpotifyColors.Gray)
            .let {
                if (onLongClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else it.clickable { onClick() }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 0.dp, sizePx = 112)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
            color = SpotifyColors.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
    }
}

// ==================== ALBUM CARD - без пружинных анимаций ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCardModern(
    album: Album,
    coverUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // FIX: remember gradient чтобы не создавать каждый рекомпоз
    val gradientBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f)),
            startY = 80f
        )
    }
    Column(
        modifier = modifier
            .width(152.dp)
            .let {
                if (onLongClick != null) it.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickLabel = "Open album"
                )
                else it.clickable { onClick() }
            }
    ) {
        Box(modifier = Modifier.size(152.dp)) {
            CoverArtImage(url = coverUrl, modifier = Modifier.size(152.dp), cornerRadius = 6.dp, sizePx = 304)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(gradientBrush)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = album.name, style = SpotifyTextStyles.CardTitle, color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = album.artist ?: "Unknown", style = SpotifyTextStyles.CardSubtitle, color = SpotifyColors.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCardSmall(
    album: Album,
    coverUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(128.dp).let {
            if (onLongClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            else it.clickable { onClick() }
        }
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(128.dp), cornerRadius = 6.dp, sizePx = 256)
        Spacer(Modifier.height(6.dp))
        Text(text = album.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp), color = SpotifyColors.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
    }
}

// ==================== ARTIST CARD ====================
@Composable
fun ArtistCardModern(
    artist: Artist,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.width(120.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(120.dp).clip(CircleShape), cornerRadius = 60.dp, sizePx = 240)
        Spacer(Modifier.height(8.dp))
        Text(text = artist.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = "Исполнитель", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
    }
}

// ==================== SONG ROW - ЛАЙК + ДИЗЛАЙК (ИСКЛЮЧЁННЫЕ), БЕЗ АНИМАЦИЙ ====================
@Composable
fun SongRowModern(
    song: Song,
    coverUrl: String?,
    isPlaying: Boolean,
    isLiked: Boolean = false,
    isDisliked: Boolean = false,
    trackNumber: Int? = null,
    showCover: Boolean = true,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onLike: () -> Unit = {},
    onDislike: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                when {
                    isDisliked -> SpotifyColors.Red.copy(alpha = 0.06f)
                    isPlaying -> SpotifyColors.Green.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }, RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (trackNumber != null && !showCover) {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) Icon(Icons.Filled.BarChart, null, tint = SpotifyColors.Green, modifier = Modifier.size(16.dp))
                else Text(text = "$trackNumber", style = MaterialTheme.typography.bodySmall, color = SpotifyColors.LightGray, fontSize = 14.sp)
            }
        }

        if (showCover) {
            Box {
                CoverArtImage(url = coverUrl, modifier = Modifier.size(44.dp), cornerRadius = 4.dp, sizePx = 88)
                if (isPlaying) {
                    Box(modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.BarChart, null, tint = SpotifyColors.Green, modifier = Modifier.size(18.dp))
                    }
                }
                if (isDisliked) {
                    Box(modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Block, null, tint = SpotifyColors.Red.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isDisliked -> SpotifyColors.MediumGray
                    isPlaying -> SpotifyColors.Green
                    else -> SpotifyColors.White
                }
            )
            Text(text = song.artist ?: "Unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = SpotifyColors.LightGray)
        }

        // Кнопка лайка - зеленое сердце когда в избранном
        IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isLiked) "Удалить из избранного" else "Добавить в избранное",
                tint = if (isLiked) SpotifyColors.Green else SpotifyColors.LightGray.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
        }

        // Кнопка дизлайка - исключённые треки (красный когда исключён)
        IconButton(onClick = onDislike, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (isDisliked) Icons.Filled.ThumbDown else Icons.Default.ThumbDownOffAlt,
                contentDescription = if (isDisliked) "Убрать из исключённых" else "Исключить трек",
                tint = if (isDisliked) SpotifyColors.Red else SpotifyColors.LightGray.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.MoreVert, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(18.dp))
        }
    }
}

// Совместимость со старым API
@Composable
fun SongRowModernCompat(
    song: Song,
    coverUrl: String?,
    isPlaying: Boolean,
    isDisliked: Boolean = false,
    trackNumber: Int? = null,
    showCover: Boolean = true,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onDislike: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SongRowModern(
        song = song,
        coverUrl = coverUrl,
        isPlaying = isPlaying,
        isLiked = false,
        isDisliked = isDisliked,
        trackNumber = trackNumber,
        showCover = showCover,
        onClick = onClick,
        onMore = onMore,
        onLike = {},
        onDislike = onDislike,
        modifier = modifier
    )
}

// ==================== SPOTIFY STYLE REMOVE LIKE BOTTOM SHEET ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveLikeBottomSheet(
    song: Song?,
    coverUrl: String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    if (song == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.GrayLighter.copy(alpha = 0.5f)))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Обложка + инфо как в Spotify - FIX: 88dp -> 176px, было raw AsyncImage без размера (грузило 300px)
            Box(
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.GrayLighter),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 8.dp, sizePx = 176)
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = SpotifyColors.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.artist ?: "Unknown",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = SpotifyColors.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Удалить из Любимых треков?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 20.sp),
                color = SpotifyColors.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Трек будет удален из твоей медиатеки. Ты сможешь добавить его снова в любой момент, и он не пропадет из плейлистов.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SpotifyColors.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(28.dp))

            // Кнопки в стиле Spotify
            Button(
                onClick = {
                    onRemove()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.White, contentColor = SpotifyColors.Black)
            ) {
                Text("Удалить", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.GrayLighter, contentColor = SpotifyColors.White)
            ) {
                Text("Оставить", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ==================== SECTION HEADER ====================
@Composable
fun SectionHeaderModern(title: String, onSeeAll: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (onSeeAll != null) m.clickable { onSeeAll() } else m }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = SpotifyTextStyles.SectionTitle.copy(fontSize = 20.sp), color = SpotifyColors.White)
        if (onSeeAll != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSeeAll() }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Показать все", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowForward, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ==================== SEARCH BAR - Spotify style с фокусом ====================
@Composable
fun SpotifySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Что хочешь послушать?",
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onSearchSubmit: ((String) -> Unit)? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    val focusModifier = if (onFocusChanged != null) {
        Modifier.onFocusChanged { state -> onFocusChanged(state.isFocused) }
    } else Modifier

    val combinedModifier = if (focusRequester != null) {
        modifier.then(Modifier.focusRequester(focusRequester)).then(focusModifier)
    } else {
        modifier.then(focusModifier)
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = combinedModifier.fillMaxWidth().height(48.dp),
        placeholder = { Text(text = placeholder, color = SpotifyColors.Black.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = SpotifyColors.Black, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, null, tint = SpotifyColors.Black, modifier = Modifier.size(18.dp))
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(6.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
            onSearchSubmit?.invoke(query)
        }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SpotifyColors.White,
            unfocusedContainerColor = SpotifyColors.White,
            focusedTextColor = SpotifyColors.Black,
            unfocusedTextColor = SpotifyColors.Black,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

// Совместимость: старая сигнатура без фокуса
@Composable
fun SpotifySearchBarCompat(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Что хочешь послушать?") {
    SpotifySearchBar(query = query, onQueryChange = onQueryChange, modifier = modifier, placeholder = placeholder)
}

// ==================== CATEGORY CARD ====================
@Composable
fun CategoryCard(title: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val brush = remember(color) { Brush.linearGradient(colors = listOf(color, color.copy(alpha = 0.8f))) }
    Box(modifier = modifier.height(84.dp).clip(RoundedCornerShape(6.dp)).background(brush).clickable { onClick() }) {
        Text(text = title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), color = SpotifyColors.White, modifier = Modifier.padding(10.dp))
        Icon(Icons.Default.MusicNote, null, tint = Color.Black.copy(alpha = 0.25f), modifier = Modifier.size(48.dp).align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp))
    }
}

// ==================== FILTER CHIPS ====================
@Composable
fun SpotifyFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)) },
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = SpotifyColors.White,
            selectedLabelColor = SpotifyColors.Black,
            containerColor = SpotifyColors.Gray,
            labelColor = SpotifyColors.White
        ),
        border = null
    )
}

// ==================== MINI PLAYER ====================
@Composable
fun MiniPlayerModern(
    song: Song?,
    coverUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyColors.Gray).clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArtImage(url = coverUrl, modifier = Modifier.size(40.dp), cornerRadius = 4.dp, sizePx = 80)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = SpotifyColors.White)
                Text(text = song.artist ?: "Unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = SpotifyColors.LightGray)
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(32.dp)) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = SpotifyColors.White, modifier = Modifier.size(24.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(SpotifyColors.GrayLighter)) {
            if (progress > 0) Box(modifier = Modifier.fillMaxWidth(progress).height(2.dp).background(SpotifyColors.White))
        }
    }
}

// ==================== BOTTOM NAV ====================
@Composable
fun SpotifyBottomNavModern(currentRoute: String, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier, containerColor = Color.Black, contentColor = SpotifyColors.White, tonalElevation = 0.dp) {
        val items = listOf(Triple("home", "Главная", Icons.Filled.Home), Triple("search", "Поиск", Icons.Filled.Search), Triple("library", "Медиатека", Icons.Filled.LibraryMusic))
        items.forEach { (route, label, icon) ->
            val selected = currentRoute == route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(route) },
                icon = { Icon(icon, null, modifier = Modifier.size(24.dp)) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 9.sp)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SpotifyColors.White,
                    selectedTextColor = SpotifyColors.White,
                    unselectedIconColor = SpotifyColors.LightGray,
                    unselectedTextColor = SpotifyColors.LightGray,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

// ==================== PLAYLIST CARD ====================
@Composable
fun PlaylistCardModern(playlist: Playlist, coverUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val gradient = remember { Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))) }
    Column(modifier = modifier.width(152.dp).clickable { onClick() }) {
        Box(modifier = Modifier.size(152.dp).clip(RoundedCornerShape(6.dp)).background(gradient)) {
            if (coverUrl != null) CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 6.dp, sizePx = 304)
            else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(48.dp)) }
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text("${playlist.songCount} треков", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = Color.White))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, style = SpotifyTextStyles.CardTitle.copy(fontSize = 13.sp), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Плейлист • ${playlist.owner ?: "TwiliRi"}", style = SpotifyTextStyles.CardSubtitle.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistRowModern(playlist: Playlist, coverUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(colors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))), contentAlignment = Alignment.Center) {
            if (coverUrl != null) {
                // FIX: убран size(96) и allowHardware(true) - вызывали лаги
                CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 96)
            } else Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Плейлист • ${playlist.songCount} треков", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}

// ==================== PLAYLIST WITH PIN ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRowWithPin(playlist: Playlist, coverUrl: String?, isPinned: Boolean, isPublic: Boolean, onClick: () -> Unit, onPinClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onPinClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(
                Brush.linearGradient(colors = if (isPublic) listOf(Color(0xFF1E3264), Color(0xFF8D67AB)) else listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
            ),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl != null) {
                // FIX: убран builder с allowHardware(true) - вызывал DiskLruCache contention
                CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 96)
            } else Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(24.dp))
            if (isPinned) {
                Box(modifier = Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(SpotifyColors.Green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PushPin, null, tint = SpotifyColors.Black, modifier = Modifier.size(10.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PushPin, null, tint = SpotifyColors.Green, modifier = Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(playlist.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal), color = SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isPublic) Icons.Default.Public else Icons.Default.Lock, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("${if (isPublic) "Общий" else "Личный"} • ${playlist.owner ?: "TwiliRi"} • ${playlist.songCount}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCardModernWithPin(playlist: Playlist, coverUrl: String?, isPinned: Boolean, isPublic: Boolean, onClick: () -> Unit, onPinClick: () -> Unit) {
    Column(modifier = Modifier.width(152.dp).combinedClickable(onClick = onClick, onLongClick = onPinClick)) {
        Box(modifier = Modifier.size(152.dp).clip(RoundedCornerShape(6.dp)).background(
            Brush.linearGradient(colors = if (isPublic) listOf(Color(0xFF1E3264), Color(0xFF8D67AB)) else listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
        )) {
            // FIX: Используем CoverArtImage с size 304px и RGB_565 вместо голого AsyncImage без size
            // Логи показывали Image decoding logging dropped из-за загрузки оригинала без даунсемпла
            if (coverUrl != null) CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 6.dp, sizePx = 304)
            else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(48.dp)) }
            if (isPinned) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(SpotifyColors.Green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PushPin, null, tint = SpotifyColors.Black, modifier = Modifier.size(14.dp))
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isPublic) Icons.Default.Public else Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${playlist.songCount}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = Color.White))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = if (isPinned) FontWeight.Bold else FontWeight.SemiBold), color = if (isPinned) SpotifyColors.Green else SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${if (isPublic) "Общий" else "Личный"} • ${playlist.owner ?: "TwiliRi"}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Системный плейлист
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SystemPlaylistRow(title: String, subtitle: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, gradient: Brush, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(gradient), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = SpotifyColors.White, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1)
        }
        Text("$count", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}

// ==================== ALBUM OPTIONS BOTTOM SHEET ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumOptionsBottomSheet(
    album: Album?,
    coverUrl: String?,
    isPinned: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onGoToArtist: () -> Unit,
    onPinToggle: () -> Unit,
    onAddToQueue: () -> Unit
) {
    if (album == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.name, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist ?: "Unknown", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            BottomSheetOption(icon = Icons.Default.PlayArrow, title = "Слушать", onClick = { onPlay(); onDismiss() })
            BottomSheetOption(icon = Icons.Default.Shuffle, title = "Перемешать", onClick = { onShuffle(); onDismiss() })
            BottomSheetOption(icon = Icons.Default.QueueMusic, title = "Добавить в очередь", onClick = { onAddToQueue(); onDismiss() })
            BottomSheetOption(icon = Icons.Default.Person, title = "Перейти к исполнителю", onClick = { onGoToArtist(); onDismiss() })
            BottomSheetOption(
                icon = if (isPinned) Icons.Default.PushPin else Icons.Filled.PushPin,
                title = if (isPinned) "Открепить альбом" else "Закрепить альбом",
                onClick = { onPinToggle(); onDismiss() }
            )
            BottomSheetOption(icon = Icons.Default.Share, title = "Поделиться", onClick = { onDismiss() })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistOptionsBottomSheet(
    playlist: Playlist?,
    coverUrl: String?,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    if (playlist == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(
                        Brush.linearGradient(colors = if (playlist.public) listOf(Color(0xFF1E3264), Color(0xFF8D67AB)) else listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUrl != null) CoverArtImage(url = coverUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp, sizePx = 112)
                    else Icon(Icons.Default.QueueMusic, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (playlist.public) "Общий" else "Личный") + " • " + (playlist.owner ?: "") + " • ${playlist.songCount}", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            BottomSheetOption(icon = Icons.Default.PlayArrow, title = "Слушать", onClick = { onPlay(); onDismiss() })
            BottomSheetOption(icon = Icons.Default.Shuffle, title = "Перемешать", onClick = { onShuffle(); onDismiss() })
            BottomSheetOption(
                icon = if (isPinned) Icons.Default.PushPin else Icons.Filled.PushPin,
                title = if (isPinned) "Открепить" else "Закрепить",
                onClick = { onPinToggle(); onDismiss() }
            )
            BottomSheetOption(icon = Icons.Default.Share, title = "Поделиться", onClick = { onDismiss() })
            if (onDelete != null) {
                BottomSheetOption(icon = Icons.Default.Delete, title = "Удалить", isDestructive = true, onClick = { onDelete(); onDismiss() })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BottomSheetOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, color = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
    }
}

// ==================== MUSIC FOLDER SELECTOR - как в Navidrome, Spotify style ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFolderSelectorBottomSheet(
    folders: List<com.sonicspot.player.data.model.MusicFolder>,
    selectedFolderId: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.GrayLighter.copy(alpha = 0.5f)))
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LibraryMusic, null, tint = SpotifyColors.Green, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Библиотеки", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp), color = SpotifyColors.White)
                    Text("Выбери библиотеку как в Navidrome", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

            // Все библиотеки
            MusicFolderRow(
                title = "Все библиотеки",
                subtitle = "Все треки из всех папок",
                isSelected = selectedFolderId == null,
                icon = Icons.Default.AllInclusive,
                onClick = { onSelect(null); onDismiss() }
            )

            folders.forEach { folder ->
                MusicFolderRow(
                    title = folder.name,
                    subtitle = "Библиотека • ID ${folder.id}",
                    isSelected = selectedFolderId == folder.id,
                    icon = Icons.Default.Folder,
                    onClick = { onSelect(folder.id); onDismiss() }
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Переключение библиотеки фильтрует альбомы, артистов и поиск как в Navidrome. Текущая библиотека сохраняется.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SpotifyColors.MediumGray,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MusicFolderRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(
                if (isSelected) SpotifyColors.Green.copy(alpha = 0.15f) else SpotifyColors.GrayLighter.copy(alpha = 0.5f)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (isSelected) SpotifyColors.Green else SpotifyColors.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold, fontSize = 15.sp), color = if (isSelected) SpotifyColors.Green else SpotifyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray, maxLines = 1)
        }
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, null, tint = SpotifyColors.Green, modifier = Modifier.size(22.dp))
        } else {
            Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun LibraryChip(
    folderName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(SpotifyColors.Gray).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = SpotifyColors.Green, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = folderName ?: "Все библиотеки",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
            color = SpotifyColors.White,
            maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.ArrowDropDown, null, tint = SpotifyColors.LightGray, modifier = Modifier.size(18.dp))
    }
}

// Надпись под блоком текста в треке - к какой музыкальной библиотеке Navidrome принадлежит трек
@Composable
fun LibraryLabelUnderLyrics(
    libraryName: String?,
    songPath: String?,
    libraryId: Int? = null,
    libraryPath: String? = null,
    isExact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(colors = listOf(SpotifyColors.Gray.copy(alpha = 0.6f), SpotifyColors.Gray.copy(alpha = 0.3f))))
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyColors.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LibraryMusic, null, tint = SpotifyColors.Green, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = libraryName ?: "Все библиотеки",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                color = SpotifyColors.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isExact) {
                                Spacer(Modifier.width(6.dp))
                                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(SpotifyColors.Green.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Navidrome", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold), color = SpotifyColors.Green)
                                }
                            }
                        }
                        Text(
                            text = if (isExact) "Точная библиотека из Navidrome API" else "Библиотека из кэша / пути",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = SpotifyColors.LightGray
                        )
                    }
                }
                if (libraryId != null) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpotifyColors.GrayLighter.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("ID $libraryId", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = SpotifyColors.White)
                    }
                }
            }

            if (!libraryPath.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(SpotifyColors.Black.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Folder, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = libraryPath,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = SpotifyColors.MediumGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!songPath.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = SpotifyColors.MediumGray.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = songPath,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = SpotifyColors.MediumGray.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!isExact) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Если включена нативная авторизация Navidrome, показывается точная библиотека трека из /api/song. Иначе — из выбранного фильтра или пути.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = SpotifyColors.MediumGray.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// Для обратной совместимости вызывается с default параметрами libraryId/libraryPath/isExact

// ==================== SLEEP TIMER - красивая дизайнерская кнопка сна до 2 часов ====================
@Composable
fun SleepTimerButton(
    sleepState: com.sonicspot.player.player.SleepTimerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = sleepState is com.sonicspot.player.player.SleepTimerState.Active
    val remainingText = if (sleepState is com.sonicspot.player.player.SleepTimerState.Active) {
        formatSleepRemaining(sleepState.remainingMillis)
    } else null

    val backgroundBrush = if (isActive) {
        Brush.linearGradient(colors = listOf(Color(0xFF1E3264), Color(0xFF8D67AB)))
    } else {
        Brush.linearGradient(colors = listOf(SpotifyColors.Gray, SpotifyColors.GrayLighter.copy(alpha = 0.6f)))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundBrush)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(
                    if (isActive) Color.White.copy(alpha = 0.2f) else SpotifyColors.GrayLighter.copy(alpha = 0.5f)
                ),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    // Circular progress when active
                    val progress = if (sleepState is com.sonicspot.player.player.SleepTimerState.Active) {
                        1f - (sleepState.remainingMillis.toFloat() / sleepState.totalMillis.coerceAtLeast(1).toFloat())
                    } else 0f
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(28.dp),
                        color = SpotifyColors.White,
                        strokeWidth = 2.dp,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                    Icon(Icons.Default.Bedtime, null, tint = SpotifyColors.White, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Default.Bedtime, null, tint = SpotifyColors.White, modifier = Modifier.size(16.dp))
                }
            }
            Column {
                Text(
                    text = if (isActive) "Сон: $remainingText" else "Таймер сна",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    color = SpotifyColors.White
                )
                if (!isActive) {
                    Text(
                        text = "Выкл. через время",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                        color = SpotifyColors.LightGray
                    )
                } else {
                    Text(
                        text = "Нажми чтобы изменить",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                        color = SpotifyColors.White.copy(alpha = 0.7f)
                    )
                }
            }
            if (isActive) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SpotifyColors.Green))
            }
        }
    }
}

@Composable
fun SleepTimerCompactIconButton(
    sleepState: com.sonicspot.player.player.SleepTimerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = sleepState is com.sonicspot.player.player.SleepTimerState.Active
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape)
            .background(
                if (isActive) Brush.linearGradient(listOf(Color(0xFF1E3264), Color(0xFF8D67AB)))
                else Brush.linearGradient(listOf(SpotifyColors.Gray, SpotifyColors.Gray))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            val progress = (sleepState as com.sonicspot.player.player.SleepTimerState.Active).let {
                1f - (it.remainingMillis.toFloat() / it.totalMillis.coerceAtLeast(1).toFloat())
            }
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(40.dp),
                color = SpotifyColors.White,
                strokeWidth = 2.dp,
                trackColor = Color.White.copy(alpha = 0.15f)
            )
        }
        Icon(
            Icons.Default.Bedtime,
            null,
            tint = if (isActive) SpotifyColors.White else SpotifyColors.LightGray,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    sleepState: com.sonicspot.player.player.SleepTimerState,
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val presets = listOf(5, 10, 15, 30, 45, 60, 90, 120)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.GrayLighter.copy(alpha = 0.5f)))
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            // Header with beautiful gradient moon
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(colors = listOf(Color(0xFF1E3264), Color(0xFF2A4B8D), Color(0xFF8D67AB))))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bedtime, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Таймер сна",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                            color = SpotifyColors.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Музыка выключится автоматически",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 14.sp),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    if (sleepState is com.sonicspot.player.player.SleepTimerState.Active) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                formatSleepRemaining(sleepState.remainingMillis),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            if (sleepState is com.sonicspot.player.player.SleepTimerState.Active) {
                // Active timer card
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SpotifyColors.GrayLighter.copy(alpha = 0.5f))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Timer, null, tint = SpotifyColors.Green, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Таймер активен", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), color = SpotifyColors.White)
                            }
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(SpotifyColors.Green))
                        }
                        Spacer(Modifier.height(12.dp))
                        // Progress bar
                        val progress = 1f - (sleepState.remainingMillis.toFloat() / sleepState.totalMillis.coerceAtLeast(1).toFloat())
                        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                            Box(modifier = Modifier.fillMaxWidth(progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(Color(0xFF8D67AB), SpotifyColors.Green))))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Осталось ${formatSleepRemaining(sleepState.remainingMillis)}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.LightGray)
                            Text("из ${formatSleepRemaining(sleepState.totalMillis)}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = SpotifyColors.MediumGray)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onCancel(); onDismiss() },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Red.copy(alpha = 0.9f), contentColor = SpotifyColors.White)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Отменить таймер", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }

            Text(
                "Выбери время",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp),
                color = SpotifyColors.LightGray,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )

            // Grid of presets - beautiful designer chips
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in presets.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { minutes ->
                            val isSelected = sleepState is com.sonicspot.player.player.SleepTimerState.Active && sleepState.totalMillis == minutes * 60 * 1000L
                            Box(
                                modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) Brush.linearGradient(listOf(Color(0xFF1E3264), Color(0xFF8D67AB)))
                                        else Brush.linearGradient(listOf(SpotifyColors.GrayLighter.copy(alpha = 0.6f), SpotifyColors.Gray))
                                    )
                                    .clickable { onSetTimer(minutes); onDismiss() }
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (minutes < 60) "$minutes" else "${minutes / 60}ч${if (minutes % 60 != 0) " ${minutes % 60}м" else ""}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
                                        color = if (isSelected) Color.White else SpotifyColors.White
                                    )
                                    Text(
                                        if (minutes < 60) "минут" else if (minutes == 60) "час" else "часа",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (isSelected) Color.White.copy(alpha = 0.8f) else SpotifyColors.LightGray
                                    )
                                }
                            }
                        }
                        // Fill empty if last row not full
                        repeat(4 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Info footer
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Black.copy(alpha = 0.3f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Максимум 2 часа • Плавное затухание громкости • Работает в фоне",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = SpotifyColors.MediumGray
                )
            }
        }
    }
}

private fun formatSleepRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}

// ==================== SHIMMER - статичный без бесконечной анимации ====================
@Composable
fun ShimmerPlaceholder(modifier: Modifier = Modifier, cornerRadius: Dp = 6.dp) {
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius)).background(SpotifyColors.Gray.copy(alpha = 0.4f)))
}
