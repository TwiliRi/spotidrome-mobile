package com.sonicspot.player.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.DownloadStore
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadedViewModel @Inject constructor(
    private val downloadStore: DownloadStore,
    val playerManager: PlayerManager,
    private val repository: MusicRepository
) : ViewModel() {

    val downloaded = downloadStore.downloaded
    val inProgress = downloadStore.inProgress

    private fun orderedSongs(): List<Song> =
        downloaded.value.values.sortedByDescending { it.addedAt }.map { it.toSong() }

    fun playAll() {
        val songs = orderedSongs()
        if (songs.isNotEmpty()) playerManager.playSongs(songs, 0)
    }

    fun shufflePlay() {
        val songs = orderedSongs().shuffled()
        if (songs.isNotEmpty()) playerManager.playSongs(songs, 0)
    }

    fun playAt(songId: String) {
        val songs = orderedSongs()
        if (songs.isEmpty()) return
        val idx = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
        playerManager.playSongs(songs, idx)
    }

    /** Удаляет трек из скачанных: файл стирается с устройства, память освобождается. */
    fun delete(songId: String) {
        viewModelScope.launch { downloadStore.delete(songId) }
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)
}
