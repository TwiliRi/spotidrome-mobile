package com.sonicspot.player.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.data.local.CacheType
import com.sonicspot.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val cacheItems by viewModel.cacheItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showClearDialog by remember { mutableStateOf<CacheType?>(null) }

    val totalSize = remember(cacheItems) { cacheItems.sumOf { it.sizeBytes } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Память", color = SpotifyColors.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text(viewModel.formatTotal(), color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                    }
                },
                navigationIcon = {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { onBack() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, null, tint = SpotifyColors.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpotifyColors.Black),
                windowInsets = WindowInsets.statusBars
            )
        },
        containerColor = SpotifyColors.Black,
        contentWindowInsets = WindowInsets.navigationBars
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).background(SpotifyColors.Black),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            // Header с общим размером - как в Spotify
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp))
                        .background(SpotifyColors.Gray).padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Storage, null, tint = SpotifyColors.Green, modifier = Modifier.size(24.dp))
                            }
                            Column {
                                Text("Использовано", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall)
                                Text(viewModel.formatTotal(), color = SpotifyColors.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        // Прогресс бар как в Spotify storage
                        val maxStorage = 500L * 1024 * 1024 // 500 MB для визуализации
                        val progress = (totalSize.toFloat() / maxStorage).coerceIn(0f, 1f)
                        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SpotifyColors.GrayLighter)) {
                            Box(modifier = Modifier.fillMaxWidth(progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(SpotifyColors.Green))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${cacheItems.size} категорий • кэшируется как в Twitch для мгновенной загрузки", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                    }
                }
            }

            item {
                Text("КЭШ ПРИЛОЖЕНИЯ", color = SpotifyColors.LightGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            items(cacheItems, key = { it.id }) { item ->
                MemoryCacheRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    size = viewModel.formatSize(item.sizeBytes),
                    sizeBytes = item.sizeBytes,
                    icon = getIconForType(item.icon),
                    iconTint = getColorForType(item.icon),
                    onClear = { showClearDialog = item.icon },
                    modifier = Modifier
                )
                HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
            }

            item {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Red.copy(alpha = 0.1f)).clickable { showClearDialog = CacheType.ALL }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Red.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DeleteForever, null, tint = SpotifyColors.Red, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Очистить всё", color = SpotifyColors.Red, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Text("Удалить весь кэш • ${viewModel.formatTotal()}", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.Red.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray.copy(alpha = 0.5f)).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Lightbulb, null, tint = SpotifyColors.Green, modifier = Modifier.size(16.dp))
                            Text("Как работает кэш как в Twitch", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp))
                        }
                        Text("• Главная страница кэшируется на 24ч и открывается мгновенно без прогрузки", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
                        Text("• Тексты песен кэшируются навсегда на диске + в памяти — без лишних запросов к API", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
                        Text("• Обложки кэшируются Coil и не перезагружаются при скролле", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
                        Text("• Pull-to-refresh насильно обновляет данные с анимацией Spotify", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showClearDialog != null) {
        val type = showClearDialog!!
        AlertDialog(
            onDismissRequest = { showClearDialog = null },
            containerColor = SpotifyColors.Gray,
            titleContentColor = SpotifyColors.White,
            textContentColor = SpotifyColors.LightGray,
            title = { Text(if (type == CacheType.ALL) "Очистить весь кэш?" else "Очистить ${getTitleForType(type)}?") },
            text = {
                Text(
                    when (type) {
                        CacheType.LYRICS -> "Все сохраненные тексты будут удалены. При следующем воспроизведении они загрузятся заново с LRCLIB/встроенных."
                        CacheType.COVERS -> "Кэш обложек будет очищен. Картинки перезагрузятся при скролле."
                        CacheType.HOME -> "Кэш главной страницы будет удален. При следующем открытии главная загрузится с сервера."
                        CacheType.ALL -> "Весь кэш приложения будет удален (${viewModel.formatTotal()}). Это действие нельзя отменить."
                        else -> "Кэш будет очищен."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearCache(type); showClearDialog = null }) {
                    Text("Очистить", color = SpotifyColors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = null }) { Text("Отмена", color = SpotifyColors.White) }
            }
        )
    }
}

@Composable
private fun MemoryCacheRow(
    title: String,
    subtitle: String,
    size: String,
    sizeBytes: Long,
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().clickable { }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(size, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp))
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(24.dp)) {
                Text("Очистить", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
            }
        }
    }
}

private fun getIconForType(type: CacheType): ImageVector = when (type) {
    CacheType.LYRICS -> Icons.Default.MusicNote
    CacheType.COVERS -> Icons.Default.Image
    CacheType.HOME -> Icons.Default.Home
    CacheType.SEARCH -> Icons.Default.Search
    CacheType.TEMP -> Icons.Default.Folder
    CacheType.ALL -> Icons.Default.DeleteForever
}

private fun getColorForType(type: CacheType): androidx.compose.ui.graphics.Color = when (type) {
    CacheType.LYRICS -> SpotifyColors.Green
    CacheType.COVERS -> androidx.compose.ui.graphics.Color(0xFF8E8EE5)
    CacheType.HOME -> androidx.compose.ui.graphics.Color(0xFF1DB954)
    CacheType.SEARCH -> androidx.compose.ui.graphics.Color(0xFFE8115B)
    CacheType.TEMP -> SpotifyColors.MediumGray
    CacheType.ALL -> SpotifyColors.Red
}

private fun getTitleForType(type: CacheType): String = when (type) {
    CacheType.LYRICS -> "тексты песен"
    CacheType.COVERS -> "обложки"
    CacheType.HOME -> "главную"
    CacheType.SEARCH -> "поиск"
    CacheType.TEMP -> "временные файлы"
    CacheType.ALL -> "весь кэш"
}
