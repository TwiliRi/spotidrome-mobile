package com.sonicspot.player.util

import com.sonicspot.player.data.model.Song

/**
 * Один исполнитель в строке исполнителей трека («A feat. B & C» → три токена).
 *
 * id известен, если сервер отдал список исполнителей (OpenSubsonic) или это основной
 * исполнитель трека; у остальных имён id ищется поиском в момент клика.
 */
data class ArtistToken(
    val name: String,
    val id: String? = null,
    /** Разделитель из исходной строки после этого имени (", ", " feat. ", " & ") — чтобы отображение не менялось. */
    val separatorAfter: String? = null
)

/**
 * Разделители имён исполнителей в отображаемой строке:
 * запятая, точка с запятой, «&», «×», feat./ft./featuring, x, vs.
 */
private val ARTIST_SEPARATOR = Regex(
    """\s*(?:,|;|&|×)\s*|\s+(?:feat\.?|ft\.?|featuring|vs\.?)\s+|\s+x\s+""",
    setOf(RegexOption.IGNORE_CASE)
)

/** Разбивает отображаемую строку исполнителей на имена, сохраняя исходные разделители. */
private fun splitDisplayArtists(display: String): List<Pair<String, String?>> {
    val parts = mutableListOf<Pair<String, String?>>()
    var cursor = 0
    while (cursor < display.length) {
        val match = ARTIST_SEPARATOR.find(display, cursor) ?: run {
            display.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let { parts.add(it to null) }
            return parts
        }
        val name = display.substring(cursor, match.range.first).trim()
        if (name.isNotEmpty()) parts.add(name to normalizeSeparator(match.value))
        cursor = match.range.last + 1
    }
    return parts
}

/** Приводит разделитель к аккуратному виду: «,» → ", ", «feat.» → " feat. ". */
private fun normalizeSeparator(raw: String): String {
    val sep = raw.trim()
    return if (sep == "," || sep == ";") "$sep " else " $sep "
}

/**
 * Строит список исполнителей трека для кликабельного отображения в плеере.
 *
 * Приоритет — структурным данным OpenSubsonic (song.artists): свежие Navidrome присылают
 * каждого исполнителя трека со своим id. Если сервер сообщил, что исполнитель один,
 * строку не делим — иначе «Earth, Wind & Fire» развалился бы на три имени.
 * Если структурных данных нет (старые серверы) — делим строку по разделителям:
 * первый токен получает artistId трека, остальные ищутся поиском при клике.
 */
fun buildArtistTokens(song: Song): List<ArtistToken> {
    val display = song.artist?.trim().orEmpty()
    val structured = song.artists.orEmpty().filter { it.id.isNotBlank() && it.name.isNotBlank() }

    if (structured.size == 1) {
        return listOf(ArtistToken(name = display.ifEmpty { structured.first().name }, id = structured.first().id))
    }

    val parts = if (display.isEmpty()) emptyList() else splitDisplayArtists(display)
    if (parts.isEmpty()) {
        // Строка не разобралась, но сервер прислал список исполнителей — показываем его
        return structured.mapIndexed { index, artist ->
            ArtistToken(
                name = artist.name,
                id = artist.id,
                separatorAfter = if (index < structured.lastIndex) ", " else null
            )
        }
    }

    val idsByName = structured.associate { it.name.trim().lowercase() to it.id }
    val primaryArtistId = song.artistId?.takeIf { it.isNotBlank() }
    return parts.mapIndexed { index, (name, separator) ->
        ArtistToken(
            name = name,
            id = idsByName[name.lowercase()] ?: if (index == 0) primaryArtistId else null,
            separatorAfter = separator
        )
    }
}
