package com.sonicspot.player.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onMemoryClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    var showClearDislikedDialog by remember { mutableStateOf(false) }
    var showClearPinnedDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showNotifButtonsDialog by remember { mutableStateOf(false) }
    var showLibrarySwitcherDialog by remember { mutableStateOf(false) }
    var showRandomAnimDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = SpotifyColors.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Gray).clickable { onBack() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
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
            // Аккаунт
            item {
                SettingsSectionHeader("Аккаунт")
                SettingsCard {
                    SettingsRow(icon = Icons.Default.Person, title = "Пользователь", subtitle = state.username.ifEmpty { "Неизвестно" })
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(icon = Icons.Default.Cloud, title = "Сервер", subtitle = state.serverUrl.ifEmpty { "Не указан" })
                }
            }

            // Воспроизведение
            item {
                SettingsSectionHeader("Воспроизведение")
                SettingsCard {
                    SettingsSwitchRow(
                        icon = Icons.Default.Block,
                        title = "Пропускать не понравившиеся",
                        subtitle = "Автоматически скипать треки из Исключённых",
                        checked = state.skipDisliked,
                        onCheckedChange = { viewModel.setSkipDisliked(it) }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchRow(
                        icon = Icons.Default.Shuffle,
                        title = "Перемешивание по умолчанию",
                        subtitle = "Включать шаффл при старте плейлиста",
                        checked = state.shuffleEnabled,
                        onCheckedChange = { viewModel.setShuffle(it) }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Кроссфейд",
                        subtitle = if (state.crossfadeEnabled) "Включен • ${state.crossfadeDuration} сек переход" else "Плавный переход между треками",
                        checked = state.crossfadeEnabled,
                        onCheckedChange = { viewModel.setCrossfade(it) }
                    )
                    if (state.crossfadeEnabled) {
                        HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                        CrossfadeDurationRow(
                            duration = state.crossfadeDuration,
                            onDurationChange = { viewModel.setCrossfadeDuration(it) }
                        )
                    }
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchRow(
                        icon = Icons.Default.Sync,
                        title = "Синхронизация очереди",
                        subtitle = if (state.playQueueSyncEnabled) "Включена • getPlayQueue/savePlayQueue" else "Очередь только локально",
                        checked = state.playQueueSyncEnabled,
                        onCheckedChange = { viewModel.setPlayQueueSync(it) }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.Casino,
                        title = "Анимация случайного трека",
                        subtitle = if (state.randomTrackAnim == PreferencesManager.RANDOM_ANIM_BLACKHOLE) {
                            "Чёрная дыра • обложки, диск, горизонт"
                        } else {
                            "Выключена • трек играет сразу"
                        },
                        onClick = { showRandomAnimDialog = true }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.Notifications,
                        title = "Кнопки в уведомлении",
                        subtitle = buildList {
                            if (PreferencesManager.NOTIF_BTN_DISLIKE in state.notificationButtons) add("Дизлайк")
                            if (PreferencesManager.NOTIF_BTN_SHUFFLE in state.notificationButtons) add("Шаффл")
                            if (PreferencesManager.NOTIF_BTN_LIKE in state.notificationButtons) add("Лайк")
                        }.joinToString(", ").ifEmpty { "Не выбрано — только play/prev/next" },
                        onClick = { showNotifButtonsDialog = true }
                    )
                }
            }

            // Библиотека
            item {
                SettingsSectionHeader("Медиатека")
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Default.SwapHoriz,
                        title = "Переключение библиотек",
                        subtitle = if (state.librarySwitcherMode == PreferencesManager.LIBRARY_SWITCHER_MENU) {
                            "Меню • список библиотек по нажатию на чип"
                        } else {
                            "Кнопки • строка библиотек на экране"
                        },
                        onClick = { showLibrarySwitcherDialog = true }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(
                        icon = Icons.Default.PushPin,
                        title = "Закрепленные плейлисты",
                        subtitle = "${state.pinnedCount} закреплено",
                        onClick = {}
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.CleaningServices,
                        title = "Очистить закрепленные",
                        subtitle = "Убрать все закрепления",
                        onClick = { showClearPinnedDialog = true }
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Удалить дубли Исключённых",
                        subtitle = "Объединить и удалить дубликаты плейлиста",
                        onClick = { viewModel.cleanupDuplicates() }
                    )
                }
            }

            // Не понравившиеся
            item {
                SettingsSectionHeader("Исключённые треки")
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.ThumbDown,
                        title = "Исключено треков",
                        subtitle = "${state.dislikedCount} треков в списке",
                        onClick = {}
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.Delete,
                        title = "Очистить исключённые",
                        subtitle = "Убрать все дизлайки (локально)",
                        isDestructive = true,
                        onClick = { showClearDislikedDialog = true }
                    )
                }
            }

            // Уведомления - как в Spotify с обложкой
            item {
                SettingsSectionHeader("Уведомления")
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Notifications,
                        title = "Медиа-уведомление",
                        subtitle = "Обложка как в Spotify • управление из шторки",
                        onClick = {}
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(
                        icon = Icons.Default.ThumbDown,
                        title = "Исключить — слева",
                        subtitle = "Кнопка исключения левее всех в уведомлении",
                        onClick = {}
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(
                        icon = Icons.Default.Favorite,
                        title = "Избранное — справа",
                        subtitle = "Кнопка избранного правее всех в уведомлении",
                        onClick = {}
                    )
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(
                        icon = Icons.Default.Shuffle,
                        title = "Перемешивание в уведомлении",
                        subtitle = "Вкл/выкл случайной последовательности из шторки",
                        onClick = {}
                    )
                }
            }

            // Память - как в Spotify storage
            item {
                SettingsSectionHeader("Хранилище")
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Storage,
                        title = "Память",
                        subtitle = "Кэш текстов, обложек, главной • как в Twitch",
                        onClick = onMemoryClick
                    )
                }
            }

            // О приложении
            item {
                SettingsSectionHeader("О приложении")
                SettingsCard {
                    SettingsRow(icon = Icons.Default.Info, title = "Версия", subtitle = "1.0.0-debug • Navidrome client")
                    HorizontalDivider(color = SpotifyColors.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow(icon = Icons.Default.MusicNote, title = "Spotidrome", subtitle = "Spotify-style player for Navidrome")
                }
            }

            // Выход
            item {
                Spacer(Modifier.height(16.dp))
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Default.Logout,
                        title = "Выйти из аккаунта",
                        subtitle = "Удалить данные и вернуться к логину",
                        isDestructive = true,
                        onClick = { showLogoutDialog = true }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showClearDislikedDialog) {
        AlertDialog(
            onDismissRequest = { showClearDislikedDialog = false },
            title = { Text("Очистить исключённые?") },
            text = { Text("Все дизлайки будут удалены локально. Плейлист на сервере останется, но треки снова будут в рекомендациях.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDisliked(); showClearDislikedDialog = false }) { Text("Очистить", color = SpotifyColors.Red) }
            },
            dismissButton = { TextButton(onClick = { showClearDislikedDialog = false }) { Text("Отмена") } }
        )
    }

    if (showClearPinnedDialog) {
        AlertDialog(
            onDismissRequest = { showClearPinnedDialog = false },
            title = { Text("Убрать закрепления?") },
            text = { Text("Все закрепленные плейлисты будут откреплены.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearPinned(); showClearPinnedDialog = false }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { showClearPinnedDialog = false }) { Text("Отмена") } }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Выйти?") },
            text = { Text("Данные аккаунта будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = { viewModel.logout(); onLogout(); showLogoutDialog = false }) { Text("Выйти", color = SpotifyColors.Red) }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } }
        )
    }

    if (showNotifButtonsDialog) {
        AlertDialog(
            onDismissRequest = { showNotifButtonsDialog = false },
            title = { Text("Кнопки в уведомлении") },
            text = {
                Column {
                    Text(
                        "Отметьте кнопки для шторки уведомления — любые комбинации. Изменения применяются сразу.",
                        color = SpotifyColors.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    NotifButtonCheckRow(
                        label = "Дизлайк — исключить трек",
                        key = PreferencesManager.NOTIF_BTN_DISLIKE,
                        selected = state.notificationButtons,
                        onToggle = { viewModel.setNotificationButtons(it) }
                    )
                    NotifButtonCheckRow(
                        label = "Шаффл — перемешивание",
                        key = PreferencesManager.NOTIF_BTN_SHUFFLE,
                        selected = state.notificationButtons,
                        onToggle = { viewModel.setNotificationButtons(it) }
                    )
                    NotifButtonCheckRow(
                        label = "Лайк — в избранное",
                        key = PreferencesManager.NOTIF_BTN_LIKE,
                        selected = state.notificationButtons,
                        onToggle = { viewModel.setNotificationButtons(it) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotifButtonsDialog = false }) { Text("Готово", color = SpotifyColors.Green) }
            }
        )
    }

    if (showRandomAnimDialog) {
        AlertDialog(
            onDismissRequest = { showRandomAnimDialog = false },
            title = { Text("Анимация случайного трека") },
            text = {
                Column {
                    Text(
                        "Что показывать при нажатии на кубик в шапке «Главной». Сцена «Чёрная дыра» — как в десктопном Spotidrome: обложки вылетают из горизонта, потом засасываются обратно, и из дыры поднимается выпавший трек.",
                        color = SpotifyColors.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LibrarySwitcherOptionRow(
                        title = "Чёрная дыра",
                        description = "Полная сцена с обложками вокруг горизонта событий",
                        selected = state.randomTrackAnim == PreferencesManager.RANDOM_ANIM_BLACKHOLE,
                        onClick = { viewModel.setRandomTrackAnim(PreferencesManager.RANDOM_ANIM_BLACKHOLE) }
                    )
                    LibrarySwitcherOptionRow(
                        title = "Выключена",
                        description = "Случайный трек играет сразу, без анимации",
                        selected = state.randomTrackAnim != PreferencesManager.RANDOM_ANIM_BLACKHOLE,
                        onClick = { viewModel.setRandomTrackAnim(PreferencesManager.RANDOM_ANIM_OFF) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Анимацию всегда можно прервать тапом по экрану — трек всё равно заиграет.",
                        color = SpotifyColors.MediumGray,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRandomAnimDialog = false }) { Text("Готово", color = SpotifyColors.Green) }
            }
        )
    }

    if (showLibrarySwitcherDialog) {
        AlertDialog(
            onDismissRequest = { showLibrarySwitcherDialog = false },
            title = { Text("Переключение библиотек") },
            text = {
                Column {
                    Text(
                        "Как выбирать музыкальную библиотеку Navidrome. Действует и на «Главной», и в «Медиатеке» — изменения применяются сразу.",
                        color = SpotifyColors.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LibrarySwitcherOptionRow(
                        title = "Меню",
                        description = "Чип в шапке открывает список библиотек (bottom sheet)",
                        selected = state.librarySwitcherMode == PreferencesManager.LIBRARY_SWITCHER_MENU,
                        onClick = { viewModel.setLibrarySwitcherMode(PreferencesManager.LIBRARY_SWITCHER_MENU) }
                    )
                    LibrarySwitcherOptionRow(
                        title = "Кнопки библиотек",
                        description = "Строка с «Все библиотеки» и папками прямо на экране",
                        selected = state.librarySwitcherMode != PreferencesManager.LIBRARY_SWITCHER_MENU,
                        onClick = { viewModel.setLibrarySwitcherMode(PreferencesManager.LIBRARY_SWITCHER_BUTTONS) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Если на сервере одна библиотека, переключатель скрывается в любом режиме.",
                        color = SpotifyColors.MediumGray,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLibrarySwitcherDialog = false }) { Text("Готово", color = SpotifyColors.Green) }
            }
        )
    }
}

@Composable
private fun LibrarySwitcherOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = SpotifyColors.Green, unselectedColor = SpotifyColors.MediumGray)
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Text(description, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
        color = SpotifyColors.LightGray,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(SpotifyColors.Gray)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyColors.GrayLighter), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), maxLines = 1)
        }
    }
}

@Composable
private fun SettingsSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyColors.GrayLighter), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SpotifyColors.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = SpotifyColors.White, checkedTrackColor = SpotifyColors.Green, uncheckedThumbColor = SpotifyColors.LightGray, uncheckedTrackColor = SpotifyColors.GrayLighter))
    }
}

@Composable
private fun CrossfadeDurationRow(duration: Int, onDurationChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyColors.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Timer, null, tint = SpotifyColors.Green, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Длительность", color = SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    Text("${duration} секунд • ${if (duration <= 3) "короткий" else if (duration <= 6) "средний" else if (duration <= 9) "длинный" else "максимальный"} переход", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                }
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Green.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("${duration}с", color = SpotifyColors.Green, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp))
            }
        }
        Spacer(Modifier.height(16.dp))
        // Красивый слайдер в стиле Spotify
        Slider(
            value = duration.toFloat(),
            onValueChange = { onDurationChange(it.toInt()) },
            valueRange = 1f..12f,
            steps = 10,
            colors = SliderDefaults.colors(
                thumbColor = SpotifyColors.White,
                activeTrackColor = SpotifyColors.Green,
                inactiveTrackColor = SpotifyColors.GrayLighter,
                activeTickColor = SpotifyColors.Green.copy(alpha = 0.5f),
                inactiveTickColor = SpotifyColors.MediumGray.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1с", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
            Text("12с", color = SpotifyColors.MediumGray, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Black.copy(alpha = 0.3f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Как в Spotify: конец текущего трека затухает, следующий начинается с нарастанием громкости. Работает с 2 плеерами для настоящего перекрытия.",
                color = SpotifyColors.MediumGray,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
    }
}

@Composable
private fun SettingsActionRow(icon: ImageVector, title: String, subtitle: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(if (isDestructive) SpotifyColors.Red.copy(alpha = 0.15f) else SpotifyColors.GrayLighter), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (isDestructive) SpotifyColors.Red else SpotifyColors.White, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = SpotifyColors.MediumGray, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NotifButtonCheckRow(
    label: String,
    key: String,
    selected: Set<String>,
    onToggle: (Set<String>) -> Unit
) {
    val checked = key in selected
    val toggle = { onToggle(if (checked) selected - key else selected + key) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { toggle() },
            colors = CheckboxDefaults.colors(checkedColor = SpotifyColors.Green)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = SpotifyColors.White, style = MaterialTheme.typography.bodyMedium)
    }
}
