package com.sonicspot.player.data.local

import kotlinx.serialization.Serializable

@Serializable
data class LyricsCacheEntry(
    val plain: String = "",
    val synced: String = "",
    val source: String = "lrclib",
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Int? = null,
    val qualityScore: Int = 0,
    val isInstrumental: Boolean = false,
    val isSynced: Boolean = false,
    val language: String? = null,
    val hitCount: Int = 1,
    val lastAccess: Long = System.currentTimeMillis(),
    val artist: String? = null,
    val title: String? = null
) {
    fun isHighQuality(): Boolean = qualityScore >= 80 && isSynced
    fun isExpired(): Boolean {
        // Lyrics rarely expire, but we can refresh plain-only after 30 days to try to upgrade to synced
        val ageDays = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60 * 24)
        return !isSynced && ageDays > 30
    }
}

object LyricsQualityScorer {
    fun score(
        isSynced: Boolean,
        source: String,
        durationMatch: Boolean,
        exactMatch: Boolean,
        isInstrumental: Boolean
    ): Int {
        if (isInstrumental) return 30
        var score = 0
        if (isSynced) score += 50 else score += 20
        when (source) {
            "lrclib" -> score += 30
            "embedded" -> score += 25
            "server" -> score += 20
            else -> score += 10
        }
        if (durationMatch) score += 15
        if (exactMatch) score += 10
        if (isSynced && source == "lrclib" && durationMatch) score = 100
        return score.coerceIn(0, 100)
    }

    fun isHighQuality(score: Int, isSynced: Boolean): Boolean = score >= 80 && isSynced
}
