package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Тесты производительности кэша - CacheManager.
 * Проверяет JSON сериализацию, файловый IO, LRU eviction.
 */
class CachePerformanceTest {

    @Test
    fun testHomeCacheSerialization() {
        // Симуляция CachedHomeData сериализации

        data class CachedAlbum(val id: String, val name: String, val artist: String?, val coverArt: String?)
        data class CachedSong(val id: String, val title: String, val artist: String?, val coverArt: String?)
        data class CachedArtist(val id: String, val name: String, val coverArt: String?)
        data class CachedPlaylist(val id: String, val name: String, val songCount: Int)

        val recent = List(12) { CachedAlbum("album_$it", "Album $it", "Artist $it", "cover_$it") }
        val newest = List(12) { CachedAlbum("newest_$it", "Newest $it", "Artist $it", "cover_new_$it") }
        val random = List(20) { CachedSong("song_$it", "Song $it", "Artist $it", "cover_$it") }
        val artists = List(15) { CachedArtist("artist_$it", "Artist $it", "cover_artist_$it") }
        val playlists = List(20) { CachedPlaylist("pl_$it", "Playlist $it", 20) }

        // Сериализация вручную (как kotlinx.serialization)
        val time = measureTimeMillis {
            repeat(100) {
                val json = buildString {
                    append("{\"timestamp\":${System.currentTimeMillis()},")
                    append("\"recentAlbums\":[")
                    append(recent.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
                    append("],\"newestAlbums\":[")
                    append(newest.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
                    append("],\"randomSongs\":[")
                    append(random.joinToString(",") { """{"id":"${it.id}","title":"${it.title}"}""" })
                    append("],\"artists\":[")
                    append(artists.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
                    append("],\"playlists\":[")
                    append(playlists.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
                    append("]}")
                }
            }
        }

        println("Home cache serialization x100: ${time}ms avg ${time / 100}ms")

        if (time / 100 > 50) {
            println("🔴 Serialization slow >50ms - will block UI if on main thread!")
            println("Fix: withContext(Dispatchers.IO) { saveHomeCache() }")
        }

        // Размер
        val json = buildString {
            append("{\"timestamp\":${System.currentTimeMillis()},")
            append("\"recentAlbums\":[")
            append(recent.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","artist":"${it.artist}","coverArt":"${it.coverArt}"}""" })
            append("],\"newestAlbums\":[")
            append(newest.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
            append("],\"randomSongs\":[")
            append(random.joinToString(",") { """{"id":"${it.id}","title":"${it.title}"}""" })
            append("],\"artists\":[")
            append(artists.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
            append("],\"playlists\":[")
            append(playlists.joinToString(",") { """{"id":"${it.id}","name":"${it.name}"}""" })
            append("]}")
        }

        println("JSON size: ${json.length} chars, ${json.length / 1024}KB")

        // Десериализация
        val parseTime = measureTimeMillis {
            repeat(100) {
                org.json.JSONObject(json)
            }
        }

        println("Parse x100: ${parseTime}ms avg ${parseTime / 100}ms")

        if (parseTime / 100 > 100) {
            println("🔴 Parse slow! Cache too big")
        }
    }

    @Test
    fun testLyricsCacheLimits() {
        println("=== Lyrics Cache Limits ===")
        println("MAX_LYRICS_CACHE_SIZE_BYTES = 100MB")
        println("MAX_LYRICS_FILES = 1000")

        // Симуляция LRU eviction
        val files = List(1000) { i ->
            mapOf("name" to "lyrics_$i.json", "size" to (1024L * (10 + i % 100)), "lastModified" to (System.currentTimeMillis() - i * 1000000L))
        }

        val time = measureTimeMillis {
            // Сортировка по lastModified - O(n log n)
            val sorted = files.sortedBy { it["lastModified"] as Long }
            val totalSize = files.sumOf { it["size"] as Long }

            println("Total size: ${totalSize / 1024 / 1024}MB, count: ${files.size}")

            if (files.size > 1000 || totalSize > 100 * 1024 * 1024) {
                // Eviction
                var currentSize = totalSize
                var currentCount = files.size
                var deleted = 0

                for (file in sorted) {
                    if (currentCount <= 800 && currentSize <= 80 * 1024 * 1024) break
                    currentSize -= file["size"] as Long
                    currentCount--
                    deleted++
                }

                println("Would delete $deleted files")
            }
        }

        println("LRU check time for 1000 files: ${time}ms")

        if (time > 100) {
            println("🟡 LRU check >100ms - should be in IO dispatcher")
        }
    }

    @Test
    fun testCacheFreshness() {
        println("=== Cache Freshness ===")

        val now = System.currentTimeMillis()
        val testCases = listOf(
            "Just created" to now,
            "1 hour ago" to now - 1 * 60 * 60 * 1000,
            "12 hours ago" to now - 12 * 60 * 60 * 1000,
            "24 hours ago" to now - 24 * 60 * 60 * 1000,
            "25 hours ago" to now - 25 * 60 * 60 * 1000,
            "7 days ago" to now - 7 * 24 * 60 * 60 * 1000
        )

        testCases.forEach { (label, timestamp) ->
            val age = now - timestamp
            val isFresh = age < 24 * 60 * 60 * 1000
            println("$label: age=${age / 1000 / 60 / 60}h fresh=$isFresh")
        }

        println("")
        println("Logic: if cache fresh, show cache instantly + load in background")
        println("If stale, show cache but also reload immediately")
        println("If no cache, show loading")
    }

    @Test
    fun testDirSizeCalculation() {
        println("=== Dir Size Calculation ===")
        println("Current implementation in CacheManager.getDirSize():")
        println("  - Lists files, sums sizes, breaks if >200MB")
        println("  - For subdirectories, only 1 level deep")
        println("")

        // Симуляция
        val fileCounts = listOf(10, 100, 1000, 10000)

        fileCounts.forEach { count ->
            val time = measureNanoTime {
                // Симуляция listFiles + sum
                val files = List(count) { mapOf("length" to 1024L * 100) }
                var size = 0L
                for (file in files) {
                    size += file["length"] as Long
                    if (size > 200L * 1024 * 1024) break
                }
            }

            println("$count files: ${time / 1_000_000.0}ms")
        }

        println("")
        println("For 10000 files, listFiles() itself can be slow (IO)")
        println("Should be in Dispatchers.IO - already is")
    }

    @Test
    fun testSearchCacheVsHomeCache() {
        println("=== Search vs Home Cache ===")

        val homeCacheSize = 50 * 1024 // 50KB typical
        val searchCacheSize = 5 * 1024 // 5KB typical
        val lyricsCacheSize = 100 * 1024 * 1024 // 100MB max
        val coversCacheSize = 200 * 1024 * 1024 // 200MB typical

        println("Home cache: ${homeCacheSize / 1024}KB - small, fast")
        println("Search cache: ${searchCacheSize / 1024}KB - tiny")
        println("Lyrics cache: ${lyricsCacheSize / 1024 / 1024}MB - large, LRU needed")
        println("Covers cache: ${coversCacheSize / 1024 / 1024}MB - large, Coil manages")

        println("")
        println("Home cache should be read first, instantly, without network")
        println("If home cache read is slow (>100ms), check:")
        println("  - File IO on main thread?")
        println("  - JSON parse too big?")
        println("  - File not exists and fallback to network?")
    }
}
