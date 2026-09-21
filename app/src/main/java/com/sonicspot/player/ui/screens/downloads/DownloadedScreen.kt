package com.sonicspot.player.ui.screens.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.local.DownloadedEntry
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    onBack: () -> Unit,
    viewModel: DownloadedViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val downloadedMap by viewModel.downloaded.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val currentSong by viewModel.playerManager.currentSongFlow.collectAsState()

    val entries = remember(downloadedMap) { downloadedMap.values.sortedByDescending { it.addedAt } }
    val totalBytes = remember(downloadedMap) { downloadedMap.values.sumOf { it.sizeBytes } }
    var entryToDelete by remember { mutableStateOf<DownloadedEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Скачанные", color = SpotifyColors.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { onBack() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpotifyColors.Black),
                windowInsets = WindowInsets.statusBars
            )
        },
        containerColor = SpotifyColors.Black
    ) { padding ->
        if (entries.isEmpty() && inProgress.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.DownloadForOffline, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Пока ничего не скачано", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Нажми иконку скачивания на странице плейлиста — треки сохранятся здесь и будут играть без интернета.",
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 100.dp)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${entries.size} треков • ${formatBytes(totalBytes)} • играют офлайн",
                        color = SpotifyColors.LightGray,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.playAll() },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Green),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = SpotifyColors.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Играть", color = SpotifyColors.Black, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.shufflePlay() },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyColors.White)
                        ) {
                            Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Перемешать")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(entries, key = { it.id }) { entry ->
                DownloadedRow(
                    entry = entry,
                    isPlaying = currentSong?.id == entry.id,
                    coverUrl = viewModel.getCoverUrl(entry.coverArt, 96),
                    onClick = { viewModel.playAt(entry.id) },
                    onDelete = { entryToDelete = entry }
                )
            }
            if (inProgress.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SpotifyColors.Green)
                        Text("Скачивается ещё ${inProgress.size}…", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (entryToDelete != null) {
        val entry = entryToDelete!!
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            containerColor = SpotifyColors.Gray,
            title = { Text("Удалить из скачанных?", color = SpotifyColors.White) },
            text = { Text("Файл «${entry.title}» будет стёрт с устройства, память освободится.", color = SpotifyColors.LightGray) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(entry.id); entryToDelete = null }) { Text("Удалить", color = SpotifyColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Отмена", color = SpotifyColors.LightGray) }
            }
        )
    }
}

@Composable
private fun DownloadedRow(
    entry: DownloadedEntry,
    isPlaying: Boolean,
    coverUrl: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(48.dp), cornerRadius = 4.dp, sizePx = 96)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = if (isPlaying) SpotifyColors.Green else SpotifyColors.White,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                maxLines = 1
            )
            Text(
                listOfNotNull(entry.artist, entry.album).joinToString(" • "),
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1
            )
        }
        Icon(Icons.Default.DownloadDone, "Скачано", tint = SpotifyColors.Green, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Удалить", tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f КБ".format(bytes / 1_024.0)
    else -> "$bytes Б"
}
