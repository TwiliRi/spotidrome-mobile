package com.sonicspot.player.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.*

@Serializable
data class NativeLoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class NativeLoginResponse(
    val id: String = "",
    val username: String = "",
    val name: String = "",
    val token: String = "",
    val isAdmin: Boolean = false
)

@Serializable
data class NativeLibrary(
    val id: Int,
    val name: String,
    val path: String = "",
    val remotePath: String? = null
)

@Serializable
data class NativeSong(
    val id: String,
    val title: String = "",
    val albumId: String = "",
    val libraryId: Int = 0,
    val libraryName: String? = null,
    val path: String = "",
    val artist: String = ""
)

@Serializable
data class NativeAlbumResponse(
    val id: String,
    val name: String = "",
    val libraryId: Int = 0,
    val libraryName: String? = null,
    val path: String = ""
)

interface NavidromeNativeApi {

    @POST("auth/login")
    suspend fun login(@Body request: NativeLoginRequest): NativeLoginResponse

    @GET("api/library")
    suspend fun getLibraries(@Header("x-nd-authorization") token: String): List<NativeLibrary>

    @GET("api/song/{id}")
    suspend fun getSong(
        @Path("id") id: String,
        @Header("x-nd-authorization") token: String
    ): NativeSong

    @GET("api/album/{id}")
    suspend fun getAlbumNative(
        @Path("id") id: String,
        @Header("x-nd-authorization") token: String
    ): NativeAlbumResponse

    @GET("api/song")
    suspend fun getSongsByAlbum(
        @Query("albumId") albumId: String,
        @Header("x-nd-authorization") token: String
    ): List<NativeSong>
}
