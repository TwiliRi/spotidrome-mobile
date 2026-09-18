package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Unit тесты для диагностики лагов HomeScreen.
 * Запуск: ./gradlew testDebugUnitTest --tests "*HomePerformanceTest*"
 * Смотри логи в build/reports/tests/
 *
 * Эти тесты не требуют Android, измеряют чистую логику.
 */
class HomePerformanceTest {

    @Test
    fun testQuickAlbumsCalculation() {
        // Тестируем derivedStateOf vs remember для quickAlbums
        // Было: remember(state.recent, state.newest) { distinctBy... }
        // Стало: derivedStateOf

        data class Album(val id: String, val name: String)

        val recent = List(20) { Album("recent_$it", "Recent $it") }
        val newest = List(20) { Album("newest_$it", "Newest $it") }

        val timeOld = measureNanoTime {
            repeat(1000) {
                val quick = (recent.take(4) + newest.take(2)).distinctBy { it.id }.take(6)
            }
        }

        println("quickAlbums calc x1000: ${timeOld / 1_000_000.0}ms avg ${timeOld / 1_000_000.0 / 1000}ms")
        // Должно быть <1ms, иначе нужен derivedStateOf
        assert(timeOld / 1_000_000.0 < 100) { "quickAlbums calc too slow!" }
    }

    @Test
    fun testCoverArtUrlGeneration() {
        val serverUrl = "https://music.example.com"
        val username = "user"
        val token = "abc123"
        val salt = "salt"

        val iterations = 1000
        val time = measureNanoTime {
            repeat(iterations) { i ->
                val coverArtId = "cover_$i"
                val size = 300
                "$serverUrl/rest/getCoverArt.view?id=$coverArtId&size=$size&u=$username&t=$token&s=$salt&v=1.16.1&c=Spotidrome"
            }
        }

        val avgMs = time / 1_000_000.0 / iterations
        val perFrame47 = avgMs * 47

        println("CoverArtUrl: $iterations iterations ${time / 1_000_000.0}ms avg ${avgMs}ms")
        println("47 per frame (HomeScreen): ${perFrame47}ms")

        // Если >16ms за кадр - будет лагать скролл
        if (perFrame47 > 16) {
            println("🔴 LAG DETECTED: 47 coverUrls per frame >16ms = jank!")
        }

        // Для скролла 60fps нужно <16ms на кадр, значит avg должно быть <0.34ms
        assert(avgMs < 1.0) { "CoverArtUrl generation too slow: ${avgMs}ms avg" }
    }

    @Test
    fun testRandomSongsFiltering() {
        // Тест фильтрации randomSongs.filterNot { disliked.contains(it.id) }
        // Если disliked много и random много - может лагать

        data class Song(val id: String)

        val randomSongs = List(50) { Song("song_$it") }
        val dislikedIds = (0..30).map { "song_$it" }.toSet()

        val time = measureNanoTime {
            repeat(1000) {
                randomSongs.filterNot { dislikedIds.contains(it.id) }
            }
        }

        println("filterNot 50 songs with 30 disliked x1000: ${time / 1_000_000.0}ms")

        // Должно быть быстро, т.к. Set.contains O(1)
        assert(time / 1_000_000.0 < 100) { "Filtering too slow" }

        // Тест с List вместо Set (O(n) vs O(1))
        val dislikedList = dislikedIds.toList()
        val timeList = measureNanoTime {
            repeat(1000) {
                randomSongs.filterNot { song -> dislikedList.contains(song.id) }
            }
        }

        println("filterNot with List (O(n)): ${timeList / 1_000_000.0}ms - slower!")
        println("Set is ${timeList.toDouble() / time}x faster than List")

        assert(time < timeList) { "Set should be faster than List" }
    }

    @Test
    fun testBrushCreation() {
        // Тест создания Brush - должен быть в remember

        val iterations = 1000
        val time = measureNanoTime {
            repeat(iterations) {
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF2A2A2A),
                        androidx.compose.ui.graphics.Color.Black
                    ),
                    startY = 0f,
                    endY = 600f
                )
            }
        }

        println("Brush creation x$iterations: ${time / 1_000_000.0}ms avg ${time / 1_000_000.0 / iterations}ms")

        // Brush creation сам по себе быстрый, но каждый рекомпоз создает новый объект
        // и триггерит рекомпозицию Box + всех детей
        println("Even if fast, Brush without remember causes recomposition of whole Box on every state change!")
    }

    @Test
    fun testHomeStateCopy() {
        // Тест _uiState.copy() - если вызывается часто, много аллокаций

        data class HomeUiState(
            val recentAlbums: List<String> = emptyList(),
            val newestAlbums: List<String> = emptyList(),
            val randomSongs: List<String> = emptyList(),
            val artists: List<String> = emptyList(),
            val playlists: List<String> = emptyList(),
            val pinnedIds: Set<String> = emptySet()
        )

        var state = HomeUiState()

        val time = measureNanoTime {
            repeat(100) {
                state = state.copy(
                    recentAlbums = List(12) { "album_$it" },
                    newestAlbums = List(12) { "newest_$it" },
                    playlists = List(20) { "pl_$it" }
                )
            }
        }

        println("HomeUiState copy x100: ${time / 1_000_000.0}ms")

        // Если disliked sync делает 100 copies подряд - будет лаг
        val time100Copies = measureNanoTime {
            repeat(100) { i ->
                state = state.copy(pinnedIds = setOf("id_$i"))
            }
        }

        println("100 state copies (simulating disliked sync loop): ${time100Copies / 1_000_000.0}ms")
        if (time100Copies / 1_000_000.0 > 16) {
            println("🔴 100 state copies >16ms - will cause jank if on main thread!")
        }
    }

    @Test
    fun testDistinctByPerformance() {
        data class Album(val id: String)

        val list = List(100) { Album("id_${it % 50}") } // 50% duplicates

        val time = measureNanoTime {
            repeat(1000) {
                list.distinctBy { it.id }
            }
        }

        println("distinctBy 100 items with 50% dupes x1000: ${time / 1_000_000.0}ms")
    }

    @Test
    fun testJsonSerializationSize() {
        // Тест размера home_cache JSON

        val recentJson = List(12) { i ->
            """{"id": "album_$i", "name": "Album $i", "artist": "Artist $i", "artistId": "artist_$i", "coverArt": "cover_$i", "songCount": 12, "year": 2023}"""
        }.joinToString(",")

        val newestJson = List(12) { i ->
            """{"id": "newest_$i", "name": "Newest Album $i", "artist": "Artist $i", "artistId": "artist_$i", "coverArt": "cover_new_$i", "songCount": 10, "year": 2024}"""
        }.joinToString(",")

        val randomJson = List(20) { i ->
            """{"id": "song_$i", "title": "Song $i", "artist": "Artist $i", "album": "Album $i", "albumId": "album_$i", "artistId": "artist_$i", "coverArt": "cover_$i", "duration": 210}"""
        }.joinToString(",")

        val artistsJson = List(15) { i ->
            """{"id": "artist_$i", "name": "Artist $i", "coverArt": "cover_artist_$i", "albumCount": 5}"""
        }.joinToString(",")

        val playlistsJson = List(20) { i ->
            """{"id": "pl_$i", "name": "Playlist $i", "songCount": 20, "public": ${i % 2 == 0}, "owner": "user", "coverArt": "pl_cover_$i"}"""
        }.joinToString(",")

        val fullJson = """{"timestamp": 1234567890, "recentAlbums": [$recentJson], "newestAlbums": [$newestJson], "randomSongs": [$randomJson], "artists": [$artistsJson], "playlists": [$playlistsJson]}"""

        println("Home cache JSON size: ${fullJson.length} chars, ${fullJson.length / 1024}KB")

        val parseTime = measureTimeMillis {
            repeat(10) {
                org.json.JSONObject(fullJson)
            }
        }

        println("Parse x10: ${parseTime}ms avg ${parseTime / 10}ms")

        if (parseTime / 10 > 100) {
            println("🔴 JSON parse slow! >100ms - cache too big or device slow")
        }

        // Размер файла на диске
        println("If saved to file, IO time ~${fullJson.length / 1024}KB / ~10MB/s = ${fullJson.length / 1024 / 1024 / 10.0}ms + JSON serialization")
    }
}
