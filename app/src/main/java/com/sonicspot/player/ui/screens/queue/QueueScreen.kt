package com.sonicspot.player.ui.screens.queue

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.sonicspot.player.ui.screens.player.PlayerViewModel
import com.sonicspot.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    BackHandler(onBack = onBack)
    val queue by viewModel.playerManager.queueFlow.collectAsState()
    val upcoming by viewModel.playerManager.upcomingQueue.collectAsState()
    val state by viewModel.playerManager.playerState.collectAsState()
    val currentSong = state.currentSong

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Очередь воспроизведения", color = SpotifyColors.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White) } },
                actions = {
                    IconButton(onClick = { viewModel.syncQueueFromServer() }) {
                        Icon(Icons.Default.Sync, null, tint = SpotifyColors.White)
                    }
                    if (queue.isNotEmpty()) TextButton(onClick = { viewModel.clearQueue() }) { Text("Очистить", color = SpotifyColors.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpotifyColors.Black),
                windowInsets = WindowInsets.statusBars
            )
        },
        containerColor = SpotifyColors.Black,
        contentWindowInsets = WindowInsets.navigationBars
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).background(SpotifyColors.Black), contentPadding = PaddingValues(bottom = 100.dp)) {
            // Sync info banner
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SpotifyColors.Gray.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudSync, null, tint = SpotifyColors.Green, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Синхронизация между устройствами", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        Text("getPlayQueue / savePlayQueue • очередь сохраняется на сервере Navidrome", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                    }
                }
            }

            if (currentSong != null) {
                item {
                    Text("Сейчас играет", color = SpotifyColors.Green, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(16.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Green.copy(alpha = 0.15f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Equalizer, null, tint = SpotifyColors.Green, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(currentSong.title, color = SpotifyColors.White, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(currentSong.artist ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }

            if (upcoming.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Далее • ${upcoming.size}", color = SpotifyColors.White, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = if (state.autoDjEnabled) SpotifyColors.Green else SpotifyColors.MediumGray, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.autoDjEnabled) "AutoDJ вкл" else "AutoDJ выкл", color = if (state.autoDjEnabled) SpotifyColors.Green else SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                        }
                    }
                }
                itemsIndexed(upcoming, key = { _, s -> s.id + s.hashCode() }) { idx, song ->
                    val actualIndex = state.currentIndex + 1 + idx
                    Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.playQueueIndex(actualIndex) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${actualIndex + 1}", color = SpotifyColors.MediumGray, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = SpotifyColors.White, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                            Text("${song.artist} • ${song.album}", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        IconButton(onClick = { viewModel.removeFromQueue(actualIndex) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.QueueMusic, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Очередь пуста", color = SpotifyColors.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("Включите AutoDJ чтобы автоматически добавлять похожие треки", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.toggleAutoDj() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Green)) {
                                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.autoDjEnabled) "AutoDJ включен" else "Включить AutoDJ")
                            }
                        }
                    }
                }
            }

            if (queue.isNotEmpty() && state.currentIndex > 0) {
                item {
                    Text("Ранее • ${state.currentIndex}", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), modifier = Modifier.padding(16.dp))
                }
                itemsIndexed(queue.take(state.currentIndex)) { idx, song ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${idx + 1}", color = SpotifyColors.MediumGray.copy(alpha = 0.5f), modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = SpotifyColors.MediumGray, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                            Text(song.artist ?: "", color = SpotifyColors.MediumGray.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
