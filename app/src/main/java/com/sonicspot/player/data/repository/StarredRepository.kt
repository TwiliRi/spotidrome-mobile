package com.sonicspot.player.data.repository

import com.sonicspot.player.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StarredRepository @Inject constructor(
    private val musicRepository: MusicRepository,
    private val prefs: PreferencesManager
) {
    val likedIdsFlow: Flow<Set<String>> = prefs.likedIdsFlow

    suspend fun isLiked(songId: String): Boolean = prefs.likedIdsFlow.first().contains(songId)

    suspend fun syncFromServer(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val starred = musicRepository.getStarred().getOrNull()
            val serverIds = starred?.song?.map { it.id }?.toSet() ?: emptySet()
            val localIds = prefs.likedIdsFlow.first()
            val missing = serverIds - localIds
            missing.forEach { id ->
                try { prefs.addLikedId(id) } catch (_: Exception) {}
            }
            // Remove local ids that are not on server (if user unstarred from other client)
            val extra = localIds - serverIds
            extra.forEach { id ->
                try { prefs.removeLikedId(id) } catch (_: Exception) {}
            }
            Result.success(missing.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleLike(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val isCurrentlyLiked = isLiked(songId)
            if (isCurrentlyLiked) removeFromLiked(songId) else addToLiked(songId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addToLiked(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.addLikedId(songId)
            musicRepository.star(songId)
            Result.success(true)
        } catch (e: Exception) {
            // rollback on failure
            try { prefs.removeLikedId(songId) } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    suspend fun removeFromLiked(songId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.removeLikedId(songId)
            musicRepository.unstar(songId)
            Result.success(false)
        } catch (e: Exception) {
            // rollback
            try { prefs.addLikedId(songId) } catch (_: Exception) {}
            Result.failure(e)
        }
    }
}
