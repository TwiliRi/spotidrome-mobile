package com.sonicspot.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.theme.SpotifyColors

/**
 * Меню трека, которое открывается по кнопке «…» в списках.
 *
 * Один и тот же лист на всех экранах: альбом, плейлист, избранное, поиск, главная. Экран передаёт
 * только то, что умеет, — остальные пункты не рисуются. «Добавить в плейлист» есть всегда: это
 * основное действие, ради которого лист и появился.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    song: Song?,
    coverUrl: String?,
    isLiked: Boolean,
    isDisliked: Boolean,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    shareUrl: String? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onStartTrackRadio: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null
) {
    if (song == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SpotifyColors.Gray,
        contentColor = SpotifyColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            // ---------- шапка с треком ----------
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.GrayLighter)) {
                    if (coverUrl != null) CoverArtImage(url = coverUrl, modifier = Modifier.size(56.dp), cornerRadius = 4.dp, sizePx = 112)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = SpotifyColors.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist ?: "", color = SpotifyColors.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))

            // ---------- главное действие ----------
            SheetItem(
                icon = Icons.Default.PlaylistAdd,
                title = "Добавить в плейлист",
                subtitle = "Выбрать плейлист или создать новый",
                onClick = onAddToPlaylist
            )

            if (onPlayNext != null || onAddToQueue != null) {
                HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                onPlayNext?.let { SheetItem(icon = Icons.Default.SkipNext, title = "Играть следующим", onClick = it) }
                onAddToQueue?.let { SheetItem(icon = Icons.Default.QueueMusic, title = "Добавить в очередь", onClick = it) }
            }

            if (onGoToAlbum != null || onGoToArtist != null || onStartTrackRadio != null) {
                HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                onGoToAlbum?.let { SheetItem(icon = Icons.Default.Album, title = "Перейти к альбому", onClick = it) }
                onGoToArtist?.let { SheetItem(icon = Icons.Default.Person, title = "Перейти к исполнителю", subtitle = song.artist, onClick = it) }
                onStartTrackRadio?.let { SheetItem(icon = Icons.Default.AutoAwesome, title = "Радио по треку", subtitle = "Похожие на ${song.title}", onClick = it) }
            }

            HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
            SheetItem(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                title = if (isLiked) "Удалить из Любимых" else "Добавить в Любимые",
                onClick = onToggleLike
            )
            SheetItem(
                icon = if (isDisliked) Icons.Filled.ThumbDown else Icons.Default.ThumbDownOffAlt,
                title = if (isDisliked) "Убрать из исключённых" else "Исключить трек",
                subtitle = "Не будет в рекомендациях",
                isDestructive = isDisliked,
                onClick = onToggleDislike
            )
            onRemoveFromPlaylist?.let {
                SheetItem(
                    icon = Icons.Default.Delete,
                    title = "Убрать из этого плейлиста",
                    isDestructive = true,
                    onClick = it
                )
            }
            if (onCopyLink != null || shareUrl != null) {
                SheetItem(
                    icon = Icons.Default.Link,
                    title = "Копировать ссылку",
                    subtitle = shareUrl,
                    onClick = onCopyLink ?: {}
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Пункт листа: иконка, заголовок, необязательная подпись. */
@Composable
private fun SheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) SpotifyColors.Red else SpotifyColors.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isDestructive) SpotifyColors.Red else SpotifyColors.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = SpotifyColors.LightGray,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
