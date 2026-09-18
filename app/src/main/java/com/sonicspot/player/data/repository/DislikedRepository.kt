package com.sonicspot.player.data.repository

import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DislikedRepository @Inject constructor(
    private val musicRepository: MusicRepository,
    private val prefs: PreferencesManager
) {
    companion object {
        const val EXCLUDED_PLAYLIST_NAME = "Исключённые треки"
    }

    val dislikedIdsFlow: Flow<Set<String>> = prefs.dislikedIdsFlow
    private val mutex = Mutex()

    suspend fun isDisliked(songId: String): Boolean = prefs.dislikedIdsFlow.first().contains(songId)

    // FIX: Batch write вместо loop - было 100 IO + 100 recompositions = лаг скролла
    // Логи показали dislikedSync 281ms но могло быть 1000+ms при 100 треках
    suspend fun syncFromServer(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val playlistResult = getOrCreateExcludedPlaylist()
            val playlist = playlistResult.getOrNull() ?: return@withContext Result.success(0)
            val detail = musicRepository.getPlaylist(playlist.id).getOrNull() ?: return@withContext Result.success(0)
            val serverIds = detail.entry.map { it.id }.toSet()
            val localIds = prefs.dislikedIdsFlow.first()
            val missing = serverIds - localIds

            var added = 0
            if (missing.isNotEmpty()) {
                try {
                    prefs.addDislikedIds(missing)
                    added = missing.size
                    com.sonicspot.player.debug.PerformanceTracer.log("dislikedSync", "Batch added $added ids in 1 IO instead of $added IO")
                } catch (_: Exception) {
                    missing.forEach { id ->
                        try {
                            prefs.addDislikedId(id)
                            added++
                        } catch (_: Exception) {}
                    }
                }
            }

            val extraLocal = localIds - serverIds
            if (extraLocal.isNotEmpty() && extraLocal.size <= 20) {
                extraLocal.forEach { id ->
                    try { musicRepository.addToPlaylist(playlist.id, id) } catch (_: Exception) {}
                }
            } else if (extraLocal.size > 20) {
                android.util.Log.w("SonicLag", "Too many extraLocal ${extraLocal.size}, skipping server sync")
            }
            Result.success(added)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrCreateExcludedPlaylist(): Result<Playlist> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val cachedId = prefs.excludedPlaylistIdFlow.first()
                if (!cachedId.isNullOrEmpty()) {
                    val playlists = musicRepository.getPlaylists().getOrNull() ?: emptyList()
                    playlists.find { it.id == cachedId }?.let {
                        try {
                            val detail = musicRepository.getPlaylist(it.id).getOrNull()
                            val batch = detail?.entry?.map { song -> song.id }?.toSet() ?: emptySet()
                            if (batch.isNotEmpty()) {
                                prefs.addDislikedIds(batch)
                            }
                        } catch (_: Exception) {}
                        return@withContext Result.success(it)
                    }
                }

                val playlistsResult = musicRepository.getPlaylists()
                val allPlaylists = playlistsResult.getOrNull() ?: emptyList()
                val matching = allPlaylists.filter { it.name == EXCLUDED_PLAYLIST_NAME }

                if (matching.size > 1) {
                    val sorted = matching.sortedByDescending { it.songCount }
                    val keep = sorted.first()
                    val toDelete = sorted.drop(1)

                    try {
                        val keepDetail = musicRepository.getPlaylist(keep.id).getOrNull()
                        val keepSongIds = keepDetail?.entry?.map { it.id }?.toSet() ?: emptySet()
                        val toAdd = mutableSetOf<String>()
                        toDelete.forEach { dup ->
                            try {
                                val dupDetail = musicRepository.getPlaylist(dup.id).getOrNull()
                                dupDetail?.entry?.forEach { s ->
                                    if (!keepSongIds.contains(s.id)) toAdd.add(s.id)
                                }
                            } catch (_: Exception) {}
                        }
                        toAdd.forEach { sid ->
                            try { musicRepository.addToPlaylist(keep.id, sid) } catch (_: Exception) {}
                        }
                        val allIds = (keepDetail?.entry?.map { it.id }?.toSet() ?: emptySet()) + toAdd
                        if (allIds.isNotEmpty()) prefs.addDislikedIds(allIds)
                    } catch (_: Exception) {}

                    toDelete.forEach { dup ->
                        try { musicRepository.deletePlaylist(dup.id) } catch (_: Exception) {}
                    }

                    prefs.saveExcludedPlaylistId(keep.id)
                    return@withContext Result.success(keep)
                }

                if (matching.size == 1) {
                    val found = matching.first()
                    prefs.saveExcludedPlaylistId(found.id)
                    try {
                        val detail = musicRepository.getPlaylist(found.id).getOrNull()
                        val batch = detail?.entry?.map { it.id }?.toSet() ?: emptySet()
                        if (batch.isNotEmpty()) prefs.addDislikedIds(batch)
                    } catch (_: Exception) {}
                    return@withContext Result.success(found)
                }

                val createResult = musicRepository.createPlaylist(EXCLUDED_PLAYLIST_NAME)
                createResult.onSuccess { playlist ->
                    prefs.saveExcludedPlaylistId(playlist.id)
                }
                createResult
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun toggleDislike(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val isCurrentlyDisliked = isDisliked(songId)
            if (isCurrentlyDisliked) removeFromExcluded(songId) else addToExcluded(songId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addToExcluded(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.addDislikedId(songId)
            val playlistResult = getOrCreateExcludedPlaylist()
            val playlist = playlistResult.getOrNull() ?: return@withContext Result.success(true)
            musicRepository.addToPlaylist(playlist.id, songId)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromExcluded(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.removeDislikedId(songId)
            val playlistId = prefs.excludedPlaylistIdFlow.first() ?: getOrCreateExcludedPlaylist().getOrNull()?.id ?: return@withContext Result.success(false)
            val playlistDetail = musicRepository.getPlaylist(playlistId).getOrNull()
            val index = playlistDetail?.entry?.indexOfFirst { it.id == songId } ?: -1
            if (index >= 0) musicRepository.removeFromPlaylist(playlistId, index)
            Result.success(false)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cleanupDuplicateExcludedPlaylists(): Int = withContext(Dispatchers.IO) {
        try {
            val all = musicRepository.getPlaylists().getOrNull() ?: return@withContext 0
            val matching = all.filter { it.name == EXCLUDED_PLAYLIST_NAME }
            if (matching.size <= 1) {
                if (matching.size == 1) prefs.saveExcludedPlaylistId(matching.first().id)
                return@withContext 0
            }
            val sorted = matching.sortedByDescending { it.songCount }
            val keep = sorted.first()
            val toDelete = sorted.drop(1)

            try {
                val keepDetail = musicRepository.getPlaylist(keep.id).getOrNull()
                val keepSongIds = keepDetail?.entry?.map { it.id }?.toSet() ?: emptySet()
                val allIdsToAdd = mutableSetOf<String>()

                toDelete.forEach { dup ->
                    try {
                        val dupDetail = musicRepository.getPlaylist(dup.id).getOrNull()
                        dupDetail?.entry?.forEach { song ->
                            if (!keepSongIds.contains(song.id) && !allIdsToAdd.contains(song.id)) {
                                allIdsToAdd.add(song.id)
                            }
                        }
                    } catch (_: Exception) {}
                }

                allIdsToAdd.forEach { songId ->
                    try { musicRepository.addToPlaylist(keep.id, songId) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            var deleted = 0
            toDelete.forEach { dup ->
                try {
                    musicRepository.deletePlaylist(dup.id)
                    deleted++
                } catch (_: Exception) {}
            }
            prefs.saveExcludedPlaylistId(keep.id)
            deleted
        } catch (e: Exception) {
            0
        }
    }
}
