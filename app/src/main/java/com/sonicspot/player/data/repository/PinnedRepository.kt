package com.sonicspot.player.data.repository

import com.sonicspot.player.data.local.PreferencesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinnedRepository @Inject constructor(
    private val prefs: PreferencesManager
) {
    val pinnedIdsFlow: Flow<Set<String>> = prefs.pinnedPlaylistsFlow
    val pinnedAlbumsFlow: Flow<Set<String>> = prefs.pinnedAlbumsFlow

    suspend fun togglePin(playlistId: String): Boolean {
        val current = prefs.pinnedPlaylistsFlow.first()
        return if (current.contains(playlistId)) {
            prefs.removePinnedPlaylist(playlistId)
            false
        } else {
            prefs.addPinnedPlaylist(playlistId)
            true
        }
    }

    suspend fun toggleAlbumPin(albumId: String): Boolean {
        val current = prefs.pinnedAlbumsFlow.first()
        return if (current.contains(albumId)) {
            prefs.removePinnedAlbum(albumId)
            false
        } else {
            prefs.addPinnedAlbum(albumId)
            true
        }
    }

    suspend fun isPinned(id: String): Boolean = prefs.pinnedPlaylistsFlow.first().contains(id)
    suspend fun isAlbumPinned(id: String): Boolean = prefs.pinnedAlbumsFlow.first().contains(id)
    suspend fun clearAllPins() = prefs.clearPinnedPlaylists()
}
