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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.sonicspot.player.data.local.ActiveDownload
import com.sonicspot.player.data.local.DownloadQueueItem
import com.sonicspot.player.data.local.DownloadStore
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.ui.components.CoverArtImage
import com.sonicspot.player.ui.theme.SpotifyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ActiveDownloadsViewModel @Inject constructor(
    private val downloadStore: DownloadStore,
    private val repository: MusicRepository
) : ViewModel() {

    val activeDownloads = downloadStore.activeDownloads
    val queue = downloadStore.queue

    fun cancel(songId: String) = downloadStore.cancelDownload(songId)

    fun removeFromQueue(songId: String) = downloadStore.removeFromQueue(songId)

    fun cancelAll() = downloadStore.cancelAll()

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
}

/** Страница активных загрузок: что качается прямо сейчас, с прогрессом и отменой. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDownloadsScreen(
    onBack: () -> Unit,
    viewModel: ActiveDownloadsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)

    val active by viewModel.activeDownloads.collectAsState()
    val queued by viewModel.queue.collectAsState()
    val entries = remember(active) { active.values.sortedBy { it.startedAt } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Скачивается", color = SpotifyColors.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpotifyColors.Black),
                windowInsets = WindowInsets.statusBars
            )
        },
        containerColor = SpotifyColors.Black
    ) { padding ->
        if (entries.isEmpty() && queued.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.DownloadForOffline, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Сейчас ничего не скачивается", color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Нажми иконку скачивания на странице плейлиста, альбома или в избранном — треки встанут в очередь и будут скачиваться по одному.",
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Качается: ${entries.size}" + if (queued.isNotEmpty()) " • в очереди: ${queued.size}" else "",
                            color = SpotifyColors.LightGray,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                        )
                        OutlinedButton(
                            onClick = { viewModel.cancelAll() },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = SpotifyColors.Red)
                            Spacer(Modifier.width(6.dp))
                            Text("Отменить все", color = SpotifyColors.White)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(entries, key = { it.song.id }) { entry ->
                ActiveDownloadRow(
                    entry = entry,
                    coverUrl = viewModel.getCoverUrl(entry.song.coverArt, 96),
                    onCancel = { viewModel.cancel(entry.song.id) }
                )
            }
            if (queued.isNotEmpty()) {
                item(key = "queue_header") {
                    Text(
                        "В очереди",
                        color = SpotifyColors.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(queued, key = { "queue_${it.song.id}" }) { item ->
                    QueuedDownloadRow(
                        item = item,
                        coverUrl = viewModel.getCoverUrl(item.song.coverArt, 96),
                        onRemove = { viewModel.removeFromQueue(item.song.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadRow(
    entry: ActiveDownload,
    coverUrl: String?,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(48.dp), cornerRadius = 4.dp, sizePx = 96)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.song.title,
                color = SpotifyColors.White,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                maxLines = 1
            )
            val bytesInfo = when {
                entry.totalBytes > 0 -> "${formatBytes(entry.receivedBytes)} из ${formatBytes(entry.totalBytes)}"
                entry.receivedBytes > 0 -> formatBytes(entry.receivedBytes)
                else -> "Ожидание…"
            }
            Text(
                listOfNotNull(entry.song.artist, bytesInfo).joinToString(" • "),
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            if (entry.progress != null) {
                LinearProgressIndicator(
                    progress = { entry.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = SpotifyColors.Green,
                    trackColor = SpotifyColors.Gray
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = SpotifyColors.Green,
                    trackColor = SpotifyColors.Gray
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (entry.progress != null) {
            Text(
                "${(entry.progress!! * 100).toInt()}%",
                color = SpotifyColors.Green,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp)
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, "Отменить скачивание", tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun QueuedDownloadRow(
    item: DownloadQueueItem,
    coverUrl: String?,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArtImage(url = coverUrl, modifier = Modifier.size(48.dp), cornerRadius = 4.dp, sizePx = 96)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.song.title,
                color = SpotifyColors.White,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                maxLines = 1
            )
            Text(
                listOfNotNull(item.song.artist, "ожидает очереди").joinToString(" • "),
                color = SpotifyColors.LightGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, "Убрать из очереди", tint = SpotifyColors.LightGray, modifier = Modifier.size(20.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f КБ".format(bytes / 1_024.0)
    else -> "$bytes Б"
}
