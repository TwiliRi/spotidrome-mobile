package com.sonicspot.player.ui.navigation

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Открытие ссылок Navidrome (например, https://server/app/#/playlist/123/show)
 * прямо в приложении — как в Spotify: жмёшь ссылку и попадаешь на нужный экран.
 *
 * Поддерживаются ссылки на плейлист, исполнителя и альбом — в том числе
 * скопированные из самого приложения кнопкой «Поделиться».
 */
object DeepLinks {

    private val _pendingRoute = MutableStateFlow<String?>(null)

    /** Маршрут, на который нужно перейти после открытия ссылки (null — нечего открывать). */
    val pendingRoute: StateFlow<String?> = _pendingRoute

    /** Разбирает ссылку из Intent и запоминает маршрут внутри приложения. */
    fun handleIntent(intent: Intent?) {
        parseRoute(intent?.data)?.let { _pendingRoute.value = it }
    }

    /** Отметить, что маршрут обработан. */
    fun consume() {
        _pendingRoute.value = null
    }

    /**
     * https://host/app/#/playlist/123/show → "playlist/123"
     * Понимает хеш-роуты Navidrome (…#/playlist/{id}/show) и обычные пути
     * (…/app/playlist/{id}/show) — на случай если «#» потерялся по дороге.
     */
    fun parseRoute(uri: Uri?): String? {
        if (uri == null) return null
        val text = listOfNotNull(uri.fragment, uri.path).joinToString(" ")
        val match = Regex("/?(playlist|artist|album)/([A-Za-z0-9_-]+)").find(text) ?: return null
        val (type, id) = match.destructured
        return when (type) {
            "playlist" -> Screen.PlaylistDetail.createRoute(id)
            "artist" -> Screen.ArtistDetail.createRoute(id)
            "album" -> Screen.AlbumDetail.createRoute(id)
            else -> null
        }
    }
}
