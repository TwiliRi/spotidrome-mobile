package com.sonicspot.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicspot.player.ui.playlistadd.AddToPlaylistUiState
import com.sonicspot.player.ui.playlistadd.AddToPlaylistViewModel
import com.sonicspot.player.ui.playlistadd.PlaylistPickItem
import com.sonicspot.player.ui.theme.SpotifyColors

/**
 * Шторка «Добавить в плейлист» целиком: сам лист, диалог создания плейлиста и всплывающие
 * сообщения. Экрану достаточно одной строки:
 *
 * ```
 * val addToPlaylist: AddToPlaylistViewModel = hiltViewModel()
 * ...
 * AddToPlaylistHost(viewModel = addToPlaylist)
 * ```
 *
 * Лист показывается только когда есть открытый трек, поэтому хост можно вешать на каждый экран
 * без условий — он ничего не рисует, пока шторка не открыта.
 */
@Composable
fun AddToPlaylistHost(viewModel: AddToPlaylistViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Сообщения («Добавлено в …», ошибки) показываем снекбаром поверх контента.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(message)
    }

    if (state.isOpen) {
        AddToPlaylistSheet(
            state = state,
            onDismiss = viewModel::close,
            onQueryChange = viewModel::onQueryChange,
            onToggle = viewModel::toggle,
            onCreatePlaylist = viewModel::createAndAdd,
            onErrorDismiss = viewModel::dismissError,
            coverUrlProvider = { id, size -> viewModel.coverUrl(id, size) }
        )
    }

    // Снекбар рисуем последним слоем, поднятым над нижней навигацией.
    Box(modifier = Modifier.fillMaxWidth()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 8.dp, end = 8.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = SpotifyColors.Gray,
                contentColor = SpotifyColors.White,
                actionColor = SpotifyColors.Green,
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    state: AddToPlaylistUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (PlaylistPickItem) -> Unit,
    onCreatePlaylist: (String, Boolean) -> Unit,
    onErrorDismiss: () -> Unit,
    coverUrlProvider: (String?, Int) -> String?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // ---------- шапка: что именно добавляем ----------
            state.song?.let { song ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Добавить в плейлист",
                        color = SpotifyColors.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter)) {
                        val url = coverUrlProvider(song.coverArt, 88)
                        if (url != null) CoverArtImage(url = url, modifier = Modifier.size(40.dp), cornerRadius = 4.dp, sizePx = 88)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---------- поиск по плейлистам ----------
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SpotifySearchBar(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    placeholder = "Найти плейлист"
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---------- «Новый плейлист» + список ----------
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item(key = "create") {
                    NewPlaylistRow(
                        enabled = !state.isCreating,
                        onClick = { showCreateDialog = true }
                    )
                }
                if (state.isLoading) {
                    item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                        }
                    }
                } else if (state.visibleItems.isEmpty()) {
                    item(key = "empty") {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (state.query.isBlank()) "Плейлистов пока нет — создайте первый" else "Ничего не нашлось",
                                color = SpotifyColors.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (state.query.isBlank()) {
                    // Без поиска список разбит на секции: личные плейлисты выше общих
                    val personalList = state.personalItems
                    val publicList = state.publicItems
                    if (personalList.isNotEmpty()) {
                        item(key = "header-personal") { SheetSectionHeader("Личные • ${personalList.size}") }
                        items(personalList, key = { it.id }) { item ->
                            PlaylistPickRow(
                                item = item,
                                coverUrl = coverUrlProvider(item.coverArt, 112),
                                onClick = { onToggle(item) }
                            )
                        }
                    }
                    if (publicList.isNotEmpty()) {
                        item(key = "header-public") { SheetSectionHeader("Общие • ${publicList.size}") }
                        items(publicList, key = { it.id }) { item ->
                            PlaylistPickRow(
                                item = item,
                                coverUrl = coverUrlProvider(item.coverArt, 112),
                                onClick = { onToggle(item) }
                            )
                        }
                    }
                } else {
                    // При поиске секции не нужны: важен сам результат
                    items(state.visibleItems, key = { it.id }) { item ->
                        PlaylistPickRow(
                            item = item,
                            coverUrl = coverUrlProvider(item.coverArt, 112),
                            onClick = { onToggle(item) }
                        )
                    }
                }
                // Подпись про непроверенные плейлисты: видно, что галочки ещё доезжают.
                if (state.pendingChecks > 0) {
                    item(key = "pending") {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = "Проверяем, где трек уже есть…",
                                color = SpotifyColors.MediumGray,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        NewPlaylistDialog(
            isBusy = state.isCreating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, isPublic ->
                onCreatePlaylist(name, isPublic)
                showCreateDialog = false
            }
        )
    }

    // Ошибку загрузки списка показываем отдельным диалогом: без плейлистов шторка бессмысленна.
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            containerColor = SpotifyColors.Gray,
            title = { Text("Не удалось загрузить плейлисты", color = SpotifyColors.White) },
            text = { Text(error, color = SpotifyColors.LightGray) },
            confirmButton = { TextButton(onClick = onErrorDismiss) { Text("Понятно", color = SpotifyColors.Green) } }
        )
    }
}

/** Заголовок секции списка: «Личные • 3», «Общие • 2». */
@Composable
private fun SheetSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = SpotifyColors.LightGray,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** Верхняя строка списка — как «Create playlist» в Spotify: плитка с плюсом и подписью. */
@Composable
private fun NewPlaylistRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.linearGradient(listOf(SpotifyColors.GrayLighter, SpotifyColors.DarkGrayElevated))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = SpotifyColors.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Новый плейлист", color = SpotifyColors.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "Создать и добавить трек",
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 1
            )
        }
    }
}

/** Строка плейлиста: обложка, название, подпись, справа — галочка «трек уже здесь» или индикатор. */
@Composable
private fun PlaylistPickRow(item: PlaylistPickItem, coverUrl: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.busy) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl != null && !item.containsTrack) {
                CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
            } else if (coverUrl != null && item.containsTrack) {
                // Отмеченный плейлист слегка приглушаем — акцент на тех, куда трек ещё не попал
                Box {
                    CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
                    Box(modifier = Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.45f)))
                }
            } else {
                Icon(
                    Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = SpotifyColors.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = if (item.containsTrack) SpotifyColors.Green else SpotifyColors.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (item.containsTrack) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(if (item.isPublic) "Общий" else "Личный")
                    append(" • ")
                    append("${item.songCount} треков")
                    if (item.containsTrack) append(" • уже в плейлисте")
                },
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            item.busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
            item.containsTrack -> Icon(Icons.Default.Check, contentDescription = "Уже в плейлисте", tint = SpotifyColors.Green, modifier = Modifier.size(22.dp))
            !item.membershipKnown -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = SpotifyColors.MediumGray)
            else -> Icon(Icons.Default.Add, contentDescription = "Добавить", tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
        }
    }
}

/** Диалог «Новый плейлист»: имя + флаг «Общий», как в Spotify. */
@Composable
private fun NewPlaylistDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, isPublic: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        containerColor = SpotifyColors.Gray,
        title = { Text("Новый плейлист", color = SpotifyColors.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isPublic = !isPublic },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SpotifyColors.White,
                            checkedTrackColor = SpotifyColors.Green,
                            uncheckedThumbColor = SpotifyColors.LightGray,
                            uncheckedTrackColor = SpotifyColors.GrayLighter
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Общий плейлист", color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium)
                        Text("Виден другим пользователям сервера", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, isPublic) },
                enabled = name.isNotBlank() && !isBusy
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                } else {
                    Text("Создать", color = if (name.isNotBlank()) SpotifyColors.Green else SpotifyColors.MediumGray)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Отмена", color = SpotifyColors.LightGray) }
        }
    )
}
