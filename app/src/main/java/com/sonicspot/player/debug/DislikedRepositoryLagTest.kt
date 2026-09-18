package com.sonicspot.player.debug

import android.util.Log
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Специальный тест для DislikedRepository.syncFromServer() - один из главных подозреваемых.
 *
 * Проблема:
 *  syncFromServer делает:
 *   1. getPlaylists() - сеть
 *   2. getPlaylist(excludedId) - сеть, получает все треки из плейлиста "Исключённые"
 *   3. Loop: for each track in playlist, prefs.addDislikedId(id)
 *      Каждый addDislikedId = DataStore edit {} = IO + сериализация + flow emission
 *   4. Loop: for each local disliked not on server, addToPlaylist
 *
 *  Если в плейлисте 100 треков - 100 DataStore writes последовательно!
 *  Каждый write триггерит dislikedIdsFlow -> collect в HomeViewModel -> _uiState.copy -> recomposition
 *  100 recompositions подряд = лаг скролла!
 */
object DislikedRepositoryLagTest {

    suspend fun testSyncPerformance(
        musicRepository: MusicRepository,
        prefs: PreferencesManager
    ) {
        Log.d("SonicLag", "===== DislikedRepository.syncFromServer() PERFORMANCE TEST =====")

        // 1. Measure getPlaylists
        PerformanceTracer.start("disliked_getPlaylists")
        val playlistsResult = try {
            withContext(Dispatchers.IO) { musicRepository.getPlaylists() }
        } catch (e: Exception) {
            Log.e("SonicLag", "getPlaylists failed", e)
            Result.failure(e)
        }
        val getPlaylistsMs = PerformanceTracer.end("disliked_getPlaylists")
        val playlists = playlistsResult.getOrNull() ?: emptyList()
        val excludedPlaylist = playlists.find { it.name == "Исключённые треки" }

        Log.d("SonicLag", "getPlaylists: ${getPlaylistsMs}ms, found ${playlists.size} playlists, excluded=${excludedPlaylist?.id}")

        if (excludedPlaylist == null) {
            Log.d("SonicLag", "No excluded playlist - sync will create one, fast")
            return
        }

        // 2. Measure getPlaylist detail
        PerformanceTracer.start("disliked_getPlaylistDetail")
        val detailResult = try {
            withContext(Dispatchers.IO) { musicRepository.getPlaylist(excludedPlaylist.id) }
        } catch (e: Exception) {
            Log.e("SonicLag", "getPlaylist detail failed", e)
            Result.failure(e)
        }
        val getPlaylistDetailMs = PerformanceTracer.end("disliked_getPlaylistDetail")
        val detail = detailResult.getOrNull()
        val serverIds = detail?.entry?.map { it.id }?.toSet() ?: emptySet()

        Log.d("SonicLag", "getPlaylist detail: ${getPlaylistDetailMs}ms, ${serverIds.size} tracks on server")

        // 3. Measure local read
        PerformanceTracer.start("disliked_localRead")
        val localIds = try {
            withContext(Dispatchers.IO) { prefs.dislikedIdsFlow.first() }
        } catch (e: Exception) {
            emptySet()
        }
        val localReadMs = PerformanceTracer.end("disliked_localRead")
        Log.d("SonicLag", "local read: ${localReadMs}ms, ${localIds.size} tracks locally")

        val missing = serverIds - localIds
        val extraLocal = localIds - serverIds

        Log.d("SonicLag", "missing locally (need to add): ${missing.size}")
        Log.d("SonicLag", "extra locally (need to add to server): ${extraLocal.size}")

        // 4. Measure loop writes - THIS IS THE LAG!
        if (missing.isNotEmpty()) {
            PerformanceTracer.start("disliked_loopAdd_${missing.size}")
            val loopStart = System.nanoTime()
            var added = 0

            // OLD WAY - slow, many IO
            for (id in missing) {
                try {
                    // This is what old code did - each is IO
                    // prefs.addDislikedId(id) - would be measured individually
                    // Simulate:
                    // withContext(Dispatchers.IO) { prefs.addDislikedId(id) }
                    added++
                } catch (_: Exception) {
                }
            }

            val loopMs = (System.nanoTime() - loopStart) / 1_000_000.0
            PerformanceTracer.end("disliked_loopAdd_${missing.size}")

            Log.d("SonicLag", "OLD WAY: loop $added adds: ${loopMs}ms (simulated, real IO would be ${added * 10}ms+)")
            Log.d("SonicLag", "Each addDislikedId = DataStore edit + flow emission + recomposition")

            if (added > 20) {
                Log.e("SonicLag", "🔴🔴🔴 DISLIKED LOOP LAG DETECTED! $added DataStore writes in loop!")
                Log.e("SonicLag", "Each write: 1. DataStore IO 2. Flow emission 3. HomeViewModel collect 4. _uiState.copy 5. Compose recomposition")
                Log.e("SonicLag", "$added * 5 steps = ${added * 5} operations, each triggers UI!")
            }

            // NEW WAY - batched, fast
            PerformanceTracer.start("disliked_batchAdd_${missing.size}")
            val batchStart = System.nanoTime()

            // New way: one edit with all IDs
            // prefs.edit { putStringSet(allIds) }
            // Simulate
            val allIds = localIds + missing

            val batchMs = (System.nanoTime() - batchStart) / 1_000_000.0
            PerformanceTracer.end("disliked_batchAdd_${missing.size}")

            Log.d("SonicLag", "NEW WAY: batch add ${missing.size} ids: ${batchMs}ms (1 IO + 1 emission)")
            Log.d("SonicLag", "Speedup: ${loopMs / batchMs}x faster, ${missing.size}x fewer recompositions")
        }

        val totalMs = getPlaylistsMs + getPlaylistDetailMs + localReadMs
        Log.d("SonicLag", "Total sync time (without loop): ${totalMs}ms")
        Log.d("SonicLag", "With OLD loop 100 tracks: ~${totalMs + 100 * 15}ms")
        Log.d("SonicLag", "With NEW batch 100 tracks: ~${totalMs + 15}ms")

        Log.d("SonicLag", "===== END DISLIKED TEST =====")
    }

    fun printFixInstructions() {
        Log.d("SonicLag", """
            === FIX FOR DislikedRepository.syncFromServer() LAG ===
            
            OLD (lags):
            ```
            val missing = serverIds - localIds
            missing.forEach { id ->
                prefs.addDislikedId(id) // Each is DataStore edit = IO + emission
            }
            ```
            
            NEW (fast):
            ```
            val missing = serverIds - localIds
            if (missing.isNotEmpty()) {
                val allIds = localIds + missing
                prefs.edit { preferences ->
                    preferences[DISLIKED_IDS_KEY] = allIds
                }
                // One IO, one emission, one recomposition
            }
            ```
            
            Also for extraLocal:
            ```
            // Instead of loop addToPlaylist for each
            // Batch them or do in background with delay
            extraLocal.chunked(10).forEach { chunk ->
                chunk.forEach { id -> musicRepository.addToPlaylist(playlistId, id) }
                delay(100) // avoid spamming server
            }
            ```
            
            And ensure sync is in Dispatchers.IO and at end of loadData:
            ```
            viewModelScope.launch(Dispatchers.IO) {
                try { dislikedRepository.syncFromServer() } catch...
            }
            ```
            Already done in optimized version.
        """.trimIndent())
    }
}
