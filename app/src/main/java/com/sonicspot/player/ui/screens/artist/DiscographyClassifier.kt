package com.sonicspot.player.ui.screens.artist

import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.model.ArtistDetail
import com.sonicspot.player.data.model.ReleaseKind

/** Ключи типов релизов — используются в маршруте «все релизы типа». */
object ReleaseTypes {
    const val ALBUMS = "albums"
    const val EPS = "eps"
    const val SINGLES = "singles"
    const val APPEARS_ON = "appears_on"
}

/**
 * Разбивает дискографию артиста на четыре группы. Используется и профилем артиста,
 * и отдельной страницей «все релизы одного типа».
 *
 * «Участие» (appears on): Navidrome включает в getArtist релизы, где артист
 * указан только в отдельных треках — у таких релизов основной исполнитель
 * (album artist) не совпадает с именем артиста.
 *
 * Тип основного релиза: сначала по тегу releaseTypes из OpenSubsonic
 * (RELEASETYPE / MusicBrainz Album Type), затем эвристика по суффиксам
 * Apple Music («- Single», «- EP») и по числу треков/длительности.
 */
object DiscographyClassifier {

    fun group(artist: ArtistDetail): ArtistDiscography {
        val albums = mutableListOf<Album>()
        val eps = mutableListOf<Album>()
        val singles = mutableListOf<Album>()
        val appearsOn = mutableListOf<Album>()
        val artistName = artist.name.trim().lowercase()

        artist.album.forEach { album ->
            val albumArtist = album.artist?.trim()?.lowercase()
            if (albumArtist != null && albumArtist != artistName) {
                appearsOn.add(album)
                return@forEach
            }
            when (classifyRelease(album)) {
                ReleaseKind.SINGLE -> singles.add(album)
                ReleaseKind.EP -> eps.add(album)
                ReleaseKind.ALBUM -> albums.add(album)
            }
        }
        return ArtistDiscography(albums = albums, eps = eps, singles = singles, appearsOn = appearsOn)
    }

    private fun classifyRelease(album: Album): ReleaseKind {
        // 1. Точный тип из тегов релиза, если сервер его отдаёт
        // (массив releaseTypes или одиночное поле releaseType у некоторых серверов)
        val types = (album.releaseTypes ?: album.releaseType?.let { listOf(it) } ?: emptyList())
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        when {
            types.any { it == "single" } -> return ReleaseKind.SINGLE
            types.any { it == "ep" } -> return ReleaseKind.EP
            types.any { it == "album" } -> return ReleaseKind.ALBUM
        }
        // 2. Суффиксы Apple Music: «Song - Single», «Song - EP»
        val name = album.name.trim()
        if (name.endsWith("- Single", ignoreCase = true)) return ReleaseKind.SINGLE
        if (name.endsWith("- EP", ignoreCase = true)) return ReleaseKind.EP
        // 3. Эвристика: 1–3 трека — сингл; до 6 треков и не дольше 30 минут — EP
        return when {
            album.songCount in 1..3 -> ReleaseKind.SINGLE
            album.songCount in 4..6 && album.duration in 1..(30 * 60) -> ReleaseKind.EP
            else -> ReleaseKind.ALBUM
        }
    }
}

/** Русская плюрализация: 1 альбом / 2 альбома / 5 альбомов. */
fun pluralRu(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "$n $one"
        mod10 in 2..4 && (mod100 < 10 || mod100 >= 20) -> "$n $few"
        else -> "$n $many"
    }
}
