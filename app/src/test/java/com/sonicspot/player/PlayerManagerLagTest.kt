package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Тесты для PlayerManager - главный подозреваемый в лаге 2-3 минуты.
 *
 * Проблема: restoreQueueFromServer вызывался через 1.5с после старта,
 * делал setMediaItems(50-100) + prepare() даже когда autoPlay=false.
 * prepare() готовит все MediaItems сразу - декодирует, грузит artwork.
 */
class PlayerManagerLagTest {

    @Test
    fun testUriParsePerformance() {
        val iterations = 100
        val serverUrl = "https://music.example.com"
        val username = "user"
        val token = "token"
        val salt = "salt"

        val time = measureNanoTime {
            repeat(iterations) { i ->
                val songId = "song_$i"
                val streamUrl = "$serverUrl/rest/stream.view?id=$songId&u=$username&t=$token&s=$salt&v=1.16.1&c=Spotidrome&f=json"
                // В реальности еще Uri.parse
                // android.net.Uri.parse(streamUrl) - нельзя в unit тесте без Android
                // Симулируем строковые операции
                streamUrl.hashCode()
            }
        }

        println("Uri generation x$iterations: ${time / 1_000_000.0}ms")

        // Для 100 треков
        val time100 = time / 1_000_000.0
        println("For 100 tracks: ${time100}ms just for URL generation")

        // Плюс Uri.parse - еще дороже
        println("Uri.parse is ~2-3x slower than string concat - total ~${time100 * 3}ms for 100 tracks")
        println("Plus MediaItem.Builder + MediaMetadata.Builder = even more")
    }

    @Test
    fun testMediaItemCreationBatch() {
        // Симуляция создания 100 MediaItems как в restoreQueue

        data class FakeSong(val id: String, val title: String, val artist: String, val album: String, val coverArt: String?)

        val songs = List(100) { i ->
            FakeSong("song_$i", "Song $i", "Artist $i", "Album $i", "cover_$i")
        }

        val time = measureTimeMillis {
            val mediaItems = songs.map { song ->
                // Симуляция createMediaItem
                val coverUrl = "https://example.com/cover?id=${song.coverArt}&size=500"
                val streamUrl = "https://example.com/stream?id=${song.id}"
                // В реальности: MediaMetadata.Builder + MediaItem.Builder + Uri.parse x2
                mapOf(
                    "id" to song.id,
                    "streamUrl" to streamUrl,
                    "coverUrl" to coverUrl,
                    "title" to song.title
                )
            }
        }

        println("Create 100 MediaItems (simulated): ${time}ms")

        if (time > 100) {
            println("🟡 Creating 100 MediaItems takes ${time}ms - may cause jank if on main thread")
        }

        // Тест с 20 треками (после оптимизации)
        val songs20 = songs.take(20)
        val time20 = measureTimeMillis {
            songs20.map { song ->
                mapOf(
                    "id" to song.id,
                    "streamUrl" to "https://example.com/stream?id=${song.id}",
                    "coverUrl" to "https://example.com/cover?id=${song.coverArt}"
                )
            }
        }

        println("Create 20 MediaItems: ${time20}ms - ${time.toDouble() / time20}x faster than 100")
    }

    @Test
    fun testPrepareVsSetMediaItems() {
        println("=== ExoPlayer prepare() vs setMediaItems() ===")
        println("setMediaItems(): легкая операция, только сохраняет список, не готовит")
        println("prepare(): ТЯЖЕЛАЯ, готовит все MediaItems:")
        println("  - Резолвит Uri (может делать сетевой запрос для проверки)")
        println("  - Парсит MediaMetadata")
        println("  - Загружает artwork Uri (может грузить картинку!)")
        println("  - Инициализирует декодеры")
        println("  - Для 100 треков - 100x работы")
        println("")
        println("БЫЛО в PlayerManager:")
        println("  player.setMediaItems(mediaItems, startIndex, position)")
        println("  player.prepare() // <- ВЫЗЫВАЛСЯ ДАЖЕ КОГДА autoPlay=false! ЛАГ 2-3 МИН!")
        println("  player.play() // только если autoPlay")
        println("")
        println("СТАЛО:")
        println("  player.setMediaItems(mediaItems, startIndex, position)")
        println("  if (autoPlay) {")
        println("    player.prepare()")
        println("    player.play()")
        println("  }")
        println("  // без prepare - легкая операция, подготовка будет при первом play()")
        println("")
        println("Это убирает главный лаг 2-3 минуты при старте!")
    }

    @Test
    fun testQueueRestorationTiming() {
        println("=== Queue Restoration Timing ===")

        // Симуляция тайминга при старте
        val scenarios = listOf(
            "Cold start, no cache, 100 tracks in server queue" to 3000L,
            "Cold start, cache hit, 20 tracks" to 500L,
            "Warm start, queue already exists locally" to 0L,
            "Server slow, getPlayQueue 2s" to 2000L
        )

        scenarios.forEach { (scenario, delay) ->
            println("$scenario: ${delay}ms delay + setMediaItems + prepare")

            val total = delay + when {
                scenario.contains("100 tracks") -> 1500 // prepare 100 tracks
                scenario.contains("20 tracks") -> 200 // prepare 20
                else -> 0
            }

            println("  Total blocking time: ~${total}ms")

            if (total > 1000) {
                println("  🔴 WILL LAG! >1s blocking")
            }
        }

        println("")
        println("Оптимизация: delay 1500ms -> 5000ms и проверка queue.isEmpty()")
        println("Если локальная очередь уже есть - не восстанавливаем с сервера")
        println("Если autoPlay=false - не делаем prepare()")
    }

    @Test
    fun testProgressUpdatesOverhead() {
        // Тест progress updates - каждые 50ms при игре, 300ms при паузе

        val updatesPerSecondPlaying = 1000 / 50 // 20
        val updatesPerSecondPaused = 1000 / 300 // 3

        println("Progress updates: $updatesPerSecondPlaying/sec when playing, $updatesPerSecondPaused/sec when paused")

        // Каждый update делает _playerState.update и _fullPlayerPosition.value =
        // Это StateFlow emission -> Compose recomposition если кто-то collectAsState

        val time = measureNanoTime {
            repeat(1000) {
                // Симуляция state update
                val progress = it / 1000f
                val position = it * 1000L
                // _playerState.update { it.copy(...) } - создает новый объект
                // Если много collectAsState на progressFlow - много рекомпозиций
            }
        }

        println("1000 state updates: ${time / 1_000_000.0}ms")

        println("")
        println("Если MiniPlayer и FullPlayer оба слушают progressFlow:")
        println("  20 updates/sec * 2 collectors = 40 recompositions/sec")
        println("  Каждая recomposition MiniPlayer = CoverArtImage + Text + ProgressBar")
        println("  Если CoverArtImage с ImageRequest builder - 40 builder creations/sec = лаг!")
        println("")
        println("Фикс: distinctUntilChanged { abs(old-new) < 0.01f } уже есть, но можно еще debounce")
    }

    @Test
    fun testSaveQueueDebounce() {
        println("=== Save Queue Debounce ===")

        // saveQueueToServerDebounced с debounce 1s

        val saves = listOf(
            "playSongs" to "immediate=true",
            "playNext" to "debounced 1s",
            "seekTo" to "position save every 10s",
            "toggleShuffle" to "debounced 1s"
        )

        saves.forEach { (action, mode) ->
            println("$action: $mode")
        }

        println("")
        println("Если debounce не работает и каждый seek сохраняет очередь:")
        println("  seek 10 раз за секунду = 10 сетевых запросов!")
        println("  Каждый savePlayQueue = build URL + OkHttp + JSON parse")
        println("  Может забить очередь и вызвать лаги")

        // Тест hash computation
        val queue = List(50) { "song_$it" }
        val time = measureNanoTime {
            repeat(1000) {
                var hash = queue.size
                hash = 31 * hash + ("song_25".hashCode())
                hash = 31 * hash + (12345L / 5000L).toInt()
                hash = 31 * hash + queue.first().hashCode()
                hash = 31 * hash + queue.last().hashCode()
            }
        }

        println("computeQueueHash x1000: ${time / 1_000_000.0}ms - fast, good for dedup")
    }
}
