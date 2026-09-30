package com.sonicspot.player.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchHistoryEntry(
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
) {
    // Умный скор: чем чаще и чем недавно, тем выше
    fun smartScore(now: Long = System.currentTimeMillis()): Double {
        val ageHours = (now - lastUsed).coerceAtLeast(0L) / 3_600_000.0
        val recencyScore = 1.0 / (1.0 + ageHours / 24.0) // 1 сегодня, 0.5 через сутки
        val freqScore = kotlin.math.log10(count.toDouble() + 1) // логарифм частоты
        return recencyScore * 0.7 + freqScore * 0.3
    }

    fun timeAgoText(now: Long = System.currentTimeMillis()): String {
        val diff = now - lastUsed
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "сейчас"
            minutes < 60 -> "${minutes}м назад"
            hours < 24 -> "${hours}ч назад"
            days < 7 -> "${days}д назад"
            else -> "${days / 7}н назад"
        }
    }
}

@Serializable
data class SearchHistoryList(
    val items: List<SearchHistoryEntry> = emptyList()
)

/**
 * Трек или исполнитель, найденные через поиск, по которым кликнули и реально слушали
 * (трек запущен; исполнитель подтверждён запуском его трека). Хранится отдельно от
 * текстовой истории запросов — показывается сверху страницы поиска.
 */
@Serializable
data class SearchListenEntry(
    val kind: String,               // KIND_SONG / KIND_ARTIST
    val id: String,                 // id трека или исполнителя ("name:<имя>" если id не было)
    val title: String,              // название трека / имя исполнителя
    val subtitle: String? = null,   // у трека — строка исполнителей
    val coverArt: String? = null,
    val artistId: String? = null,   // у трека — id исполнителя (для перехода из «недавних»)
    val albumId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isSong: Boolean get() = kind == KIND_SONG
    val hasRealId: Boolean get() = !id.startsWith(NAME_ID_PREFIX)

    /** Минимальный Song для повторного запуска трека из «недавних» */
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = subtitle,
        albumId = albumId,
        artistId = artistId,
        coverArt = coverArt
    )

    companion object {
        const val KIND_SONG = "song"
        const val KIND_ARTIST = "artist"
        const val NAME_ID_PREFIX = "name:"

        /** id для исполнителя, у которого сервер не дал настоящего id */
        fun nameId(name: String): String = NAME_ID_PREFIX + name.trim().lowercase()
    }
}
