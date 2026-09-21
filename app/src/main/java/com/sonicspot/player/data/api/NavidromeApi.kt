package com.sonicspot.player.data.api

import com.sonicspot.player.data.model.SubsonicResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface NavidromeApi {

    @GET("rest/ping.view")
    suspend fun ping(): SubsonicResponse

    @GET("rest/getMusicFolders.view")
    suspend fun getMusicFolders(
        @Header("Cache-Control") cacheControl: String? = null
    ): SubsonicResponse

    @GET("rest/getArtists.view")
    suspend fun getArtists(
        @Query("musicFolderId") musicFolderId: Int? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): SubsonicResponse

    @GET("rest/getArtist.view")
    suspend fun getArtist(@Query("id") id: String, @Header("Cache-Control") cacheControl: String? = null): SubsonicResponse

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(@Query("id") id: String, @Header("Cache-Control") cacheControl: String? = null): SubsonicResponse

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("musicFolderId") musicFolderId: Int? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): SubsonicResponse

    @GET("rest/search3.view")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 20,
        @Query("albumCount") albumCount: Int = 20,
        @Query("songCount") songCount: Int = 50,
        @Query("musicFolderId") musicFolderId: Int? = null,
        @Header("Cache-Control") cacheControl: String? = null
    ): SubsonicResponse

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(@Header("Cache-Control") cacheControl: String? = null): SubsonicResponse

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(@Query("id") id: String, @Header("Cache-Control") cacheControl: String? = null): SubsonicResponse

    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("name") name: String
    ): SubsonicResponse

    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("public") public: Boolean? = null,
        @Query("songIdToAdd") songIdToAdd: String? = null,
        @Query("songIdToRemove") songIdToRemove: String? = null,
        @Query("songIndexToRemove") songIndexToRemove: Int? = null
    ): SubsonicResponse

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(@Query("id") id: String): SubsonicResponse

    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(
        @Query("size") size: Int = 50,
        @Query("genre") genre: String? = null,
        @Query("musicFolderId") musicFolderId: Int? = null
    ): SubsonicResponse

    @GET("rest/getStarred.view")
    suspend fun getStarred(@Header("Cache-Control") cacheControl: String? = null): SubsonicResponse

    @GET("rest/star.view")
    suspend fun star(@Query("id") id: String): SubsonicResponse

    @GET("rest/unstar.view")
    suspend fun unstar(@Query("id") id: String): SubsonicResponse

    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("submission") submission: Boolean = true
    ): SubsonicResponse

    @GET("rest/getSimilarSongs.view")
    suspend fun getSimilarSongs(
        @Query("id") id: String,
        @Query("count") count: Int = 20
    ): SubsonicResponse

    @GET("rest/getSimilarSongs2.view")
    suspend fun getSimilarSongs2(
        @Query("id") id: String,
        @Query("count") count: Int = 20
    ): SubsonicResponse

    @GET("rest/getTopSongs.view")
    suspend fun getTopSongs(
        @Query("artist") artist: String,
        @Query("count") count: Int = 20
    ): SubsonicResponse

    @GET("rest/getLyrics.view")
    suspend fun getLyrics(
        @Query("artist") artist: String,
        @Query("title") title: String
    ): SubsonicResponse

    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(
        @Query("id") id: String
    ): SubsonicResponse

    @GET("rest/getPlayQueue.view")
    suspend fun getPlayQueue(): SubsonicResponse

    // Используем @Url чтобы полностью обойти баг Retrofit + R8 с List<String> в релизе
    // (Class cannot be cast to ParameterizedType)
    @GET
    suspend fun savePlayQueueDynamic(
        @retrofit2.http.Url url: String
    ): SubsonicResponse
}
