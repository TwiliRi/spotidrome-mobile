package com.sonicspot.player.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class LrclibResponse(
    val id: Long = 0,
    val name: String = "",
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)

interface LrclibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") duration: Int? = null
    ): LrclibResponse

    @GET("api/get")
    suspend fun getLyricsByDuration(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String,
        @Query("duration") duration: Int
    ): LrclibResponse

    // Fallback search
    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LrclibResponse>
}
