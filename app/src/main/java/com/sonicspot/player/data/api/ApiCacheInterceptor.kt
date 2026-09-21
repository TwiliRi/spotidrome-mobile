package com.sonicspot.player.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Дописывает Cache-Control ответам Subsonic API, чтобы OkHttp мог их кэшировать.
 *
 * Зачем: Navidrome не отдаёт заголовков кэширования, а OkHttp-кэш в приложении не был настроен
 * вовсе. Из-за этого каждый вход в «Библиотеку» заново тянул все альбомы, артистов, плейлисты и
 * избранное — даже если пользователь только что оттуда вышел.
 *
 * Почему белый список, а не всё подряд:
 *  - stream.view и download.view отдают аудио, кэшировать их на диске бессмысленно и дорого;
 *  - scrobble / star / savePlayQueue — это изменения на сервере, их кэшировать нельзя;
 *  - остальные ответы не кэшируются, пока не будут добавлены сюда осознанно.
 *
 * Явное обновление (pull-to-refresh) передаёт в запросе Cache-Control: no-cache — OkHttp
 * прочтёт его и пойдёт в сеть, не заглядывая в кэш.
 */
class ApiCacheInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method != "GET") return response

        val maxAge = MAX_AGE_SECONDS[request.url.encodedPath.substringAfterLast('/')]
            ?: return response

        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            .header("Cache-Control", "public, max-age=$maxAge")
            .build()
    }

    private companion object {
        /** Обложка для данного id неизменна, держать можно очень долго. */
        const val COVER = 30 * 24 * 60 * 60L
        /** Каталог: 5 минут. Повторный визит мгновенный, свежие данные не задерживаются. */
        const val CATALOG = 5 * 60L

        val MAX_AGE_SECONDS: Map<String, Long> = mapOf(
            "getCoverArt.view" to COVER,
            "getAlbumList2.view" to CATALOG,
            "getArtists.view" to CATALOG,
            "getArtist.view" to CATALOG,
            "getAlbum.view" to CATALOG,
            "getStarred.view" to CATALOG,
            "getPlaylists.view" to CATALOG,
            "getPlaylist.view" to CATALOG,
            "search3.view" to CATALOG,
            "getMusicFolders.view" to CATALOG,
            "getTopSongs.view" to CATALOG
        )
    }
}
