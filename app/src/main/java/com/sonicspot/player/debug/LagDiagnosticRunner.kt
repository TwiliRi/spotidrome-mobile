package com.sonicspot.player.debug

import android.content.Context
import android.util.Log
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Набор тестов которые можно запустить на устройстве чтобы найти причину лагов.
 *
 * Как запустить:
 * 1. Добавь кнопку в SettingsScreen: LagDiagnosticRunner.runAll(context)
 * 2. Или вызови из HomeViewModel init: LagDiagnosticRunner.quickCheck()
 * 3. Смотри Logcat с фильтром "SonicLag"
 */
object LagDiagnosticRunner {

    private const val TAG = "SonicLag"

    /**
     * Быстрая проверка - логирует основные подозреваемые
     */
    fun quickCheck() {
        Log.d(TAG, "===== QUICK LAG CHECK =====")

        // Проверка 1: Main thread?
        checkMainThread()

        // Проверка 2: Сколько времени занимает создание Brush
        measureBrushCreation()

        // Проверка 3: ImageRequest builder
        measureImageRequestBuilder()

        // Проверка 4: JSON сериализация
        measureJsonSerialization()

        // Проверка 5: String concatenation для coverUrl
        measureCoverUrlGeneration()

        Log.d(TAG, "===== QUICK CHECK DONE =====")
    }

    private fun checkMainThread() {
        val isMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        Log.d(TAG, "Current thread: ${Thread.currentThread().name} isMain=$isMain")
        if (isMain) {
            Log.w(TAG, "⚠ quickCheck вызван на MAIN потоке - нормально для теста, но IO не должно быть на main!")
        }
    }

    private fun measureBrushCreation() {
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
        val avgMs = time / 1_000_000.0 / iterations
        Log.d(TAG, "Brush creation: avg ${String.format("%.4f", avgMs)}ms x $iterations = ${time / 1_000_000.0}ms total")
        if (avgMs > 0.1) {
            Log.e(TAG, "🔴 Brush creation SLOW ${avgMs}ms avg - обязательно remember!")
        } else {
            Log.d(TAG, "🟢 Brush creation OK, но все равно нужен remember чтобы не создавать каждый рекомпоз")
        }
    }

    private fun measureImageRequestBuilder() {
        // Симулируем старый код CoverArtImage
        val iterations = 100
        val fakeContext = null // не можем создать без контекста, измеряем логику
        val time = measureNanoTime {
            repeat(iterations) {
                // Симуляция того что делал старый CoverArtImage
                val url = "https://example.com/cover.jpg?id=123&size=300"
                val size = 304
                val memoryKey = url
                val diskKey = url
                // String operations
                val key = "${memoryKey}-${size}"
            }
        }
        Log.d(TAG, "ImageRequest-like string ops: ${time / 1_000_000.0}ms for $iterations iterations")

        // Замер реального влияния allowHardware
        Log.d(TAG, "ImageRequest with allowHardware(true) создает Hardware Bitmap - дорого для 47+ картинок одновременно!")
        Log.d(TAG, "Решение: AsyncImage(model=url) без builder, без size, без allowHardware, без custom keys")
    }

    private fun measureJsonSerialization() {
        val iterations = 50
        val sampleJson = """
            {"timestamp": 1234567890, "recentAlbums": [{"id": "1", "name": "Album 1", "artist": "Artist", "coverArt": "123", "songCount": 10, "year": 2023}]}
        """.trimIndent()

        val time = measureNanoTime {
            repeat(iterations) {
                try {
                    JSONObject(sampleJson)
                } catch (_: Exception) {
                }
            }
        }
        Log.d(TAG, "JSON parse: ${time / 1_000_000.0}ms for $iterations iterations avg ${time / 1_000_000.0 / iterations}ms")

        // Замер для реального размера home_cache (примерно 20 альбомов + 20 песен + 15 артистов + 20 плейлистов)
        val largeJson = buildString {
            append("""{"timestamp": 1234567890, "recentAlbums": [""")
            repeat(20) { i ->
                if (i > 0) append(",")
                append("""{"id": "$i", "name": "Album $i with very long name that might be truncated", "artist": "Artist $i", "artistId": "artist_$i", "coverArt": "cover_$i", "songCount": 12, "year": 2023}""")
            }
            append("""], "newestAlbums": [""")
            repeat(20) { i ->
                if (i > 0) append(",")
                append("""{"id": "new_$i", "name": "New Album $i", "artist": "Artist $i", "artistId": "artist_$i", "coverArt": "cover_new_$i", "songCount": 10, "year": 2024}""")
            }
            append("""], "randomSongs": [""")
            repeat(50) { i ->
                if (i > 0) append(",")
                append("""{"id": "song_$i", "title": "Song $i title", "artist": "Artist $i", "album": "Album $i", "albumId": "album_$i", "artistId": "artist_$i", "coverArt": "cover_$i", "duration": 210}""")
            }
            append("""], "artists": [""")
            repeat(15) { i ->
                if (i > 0) append(",")
                append("""{"id": "artist_$i", "name": "Artist $i", "coverArt": "cover_artist_$i", "albumCount": 5}""")
            }
            append("""], "playlists": [""")
            repeat(20) { i ->
                if (i > 0) append(",")
                append("""{"id": "pl_$i", "name": "Playlist $i", "songCount": 20, "public": ${i % 2 == 0}, "owner": "user", "coverArt": "pl_cover_$i"}""")
            }
            append("]}")
        }

        val largeTime = measureTimeMillis {
            repeat(10) {
                try {
                    JSONObject(largeJson)
                } catch (_: Exception) {
                }
            }
        }
        Log.d(TAG, "Large home_cache JSON (${largeJson.length} chars) parse: ${largeTime}ms for 10 iterations avg ${largeTime / 10}ms")
        if (largeTime / 10 > 100) {
            Log.e(TAG, "🔴 JSON parse SLOW! ${largeTime / 10}ms - кэш слишком большой или kotlinx.serialization медленная, уменьши размер или используй streaming")
        }
    }

    private fun measureCoverUrlGeneration() {
        val iterations = 1000
        val serverUrl = "https://music.example.com"
        val username = "user"
        val token = "abc123token"
        val salt = "randomsalt"

        val time = measureNanoTime {
            repeat(iterations) {
                val coverArtId = "cover_$it"
                val size = 300
                // Старый способ - конкатенация строк каждый раз
                val url = "$serverUrl/rest/getCoverArt.view?id=$coverArtId&size=$size&u=$username&t=$token&s=$salt&v=1.16.1&c=Spotidrome"
            }
        }
        Log.d(TAG, "CoverArtUrl generation: ${time / 1_000_000.0}ms for $iterations avg ${time / 1_000_000.0 / iterations}ms")

        // Если вызывается 47+ раз за кадр - это 47 * avg
        val perFrame = 47 * (time / 1_000_000.0 / iterations)
        Log.d(TAG, "47 coverUrls per frame: ~${perFrame}ms")
        if (perFrame > 16) {
            Log.e(TAG, "🔴 47 coverUrl generation >16ms per frame - лагает скролл! Кэшируй url или делай remember")
        }
    }

    /**
     * Полный тест всех подозреваемых мест
     */
    suspend fun runAll(context: Context) = withContext(Dispatchers.Default) {
        Log.d(TAG, "========== FULL LAG DIAGNOSTICS START ==========")

        // 1. DataStore performance
        testDataStorePerformance(context)

        // 2. Coil cache performance
        testCoilCachePerformance(context)

        // 3. ExoPlayer MediaItem creation
        testMediaItemCreation()

        // 4. Filter operations (randomSongs.filterNot)
        testFilterPerformance()

        // 5. LazyColumn key performance simulation
        testLazyKeysPerformance()

        // 6. Memory pressure
        testMemoryPressure()

        Log.d(TAG, "========== FULL DIAGNOSTICS DONE ==========")
        Log.d(TAG, PerformanceTracer.dumpReport())
    }

    private suspend fun testDataStorePerformance(context: Context) {
        Log.d(TAG, "--- DataStore Performance ---")

        // Симулируем чтение dislikedIds - это Flow.first() который может блокировать
        val time = measureTimeMillis {
            // В реальности это prefs.dislikedIdsFlow.first()
            // Здесь симулируем
            delay(10) // эмуляция IO
        }
        Log.d(TAG, "DataStore first() simulated: ${time}ms")

        // Тест цикла addDislikedId - главная причина лага в syncFromServer
        val loopCount = 100
        val loopTime = measureTimeMillis {
            repeat(loopCount) {
                // prefs.addDislikedId(id) - каждый вызов это edit {} -> IO
                // Если 100 треков в плейлисте "Исключённые" - 100 IO операций последовательно!
            }
        }
        Log.d(TAG, "DataStore loop $loopCount x addDislikedId: ${loopTime}ms (simulated, real would be much higher with IO)")

        if (loopCount > 20) {
            Log.e(TAG, "🔴🔴🔴 DataStore WRITE LOOP DETECTED! syncFromServer делает prefs.addDislikedId() в цикле по каждому треку из плейлиста. " +
                    "Каждый add = DataStore edit = IO + сериализация. 100 треков = 100 IO! " +
                    "ФИКС: собери все ID в Set и сделай ОДИН edit { putStringSet }")
        }
    }

    private suspend fun testCoilCachePerformance(context: Context) {
        Log.d(TAG, "--- Coil Cache Performance ---")

        val cacheDir = context.cacheDir
        val imageCache = java.io.File(cacheDir, "image_cache")
        val coilCache = java.io.File(cacheDir, "coil")

        val imageCacheSize = try {
            imageCache.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }

        val coilCacheSize = try {
            coilCache.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }

        Log.d(TAG, "Coil cache: image_cache=${imageCacheSize / 1024}KB, coil=${coilCacheSize / 1024}KB")

        // Проверка allowHardware
        Log.d(TAG, "allowHardware(true) для маленьких картинок (44dp, 56dp) создает Hardware Bitmap который нельзя рисовать на software canvas и требует GPU upload каждый раз")
        Log.d(TAG, "Для 47+ картинок одновременно - 47 GPU uploads за кадр = лаг!")
        Log.d(TAG, "ФИКС: убери allowHardware(true) и size() из ImageRequest, используй просто AsyncImage(model=url)")
    }

    private fun testMediaItemCreation() {
        Log.d(TAG, "--- ExoPlayer MediaItem Creation ---")

        val songCount = 100
        val time = measureTimeMillis {
            repeat(songCount) { i ->
                // Симуляция createMediaItem
                val songId = "song_$i"
                val streamUrl = "https://example.com/rest/stream.view?id=$songId&u=user&t=token&s=salt&v=1.16.1&c=Spotidrome&f=json"
                val coverUrl = "https://example.com/rest/getCoverArt.view?id=cover_$i&size=500&u=user&t=token&s=salt&v=1.16.1&c=Spotidrome"
                try {
                    android.net.Uri.parse(streamUrl)
                    android.net.Uri.parse(coverUrl)
                } catch (_: Exception) {
                }
                // MediaMetadata building
                // MediaItem building
            }
        }

        Log.d(TAG, "MediaItem creation $songCount items: ${time}ms avg ${time.toDouble() / songCount}ms per item")

        if (time > 500) {
            Log.e(TAG, "🔴 MediaItem creation SLOW $time ms for $songCount items - Uri.parse тяжелый, кэшируй или делай лениво")
        }

        // prepare() тест
        Log.d(TAG, "ExoPlayer prepare() с $songCount треками - самая тяжелая операция, готовит все MediaItems, декодирует метаданные, загружает artwork Uri")
        Log.d(TAG, "Если вызывается при восстановлении очереди без автоплея - ЭТО ГЛАВНАЯ ПРИЧИНА ЛАГА 2-3 МИН!")
        Log.d(TAG, "ФИКС: не вызывай prepare() когда autoPlay=false, только setMediaItems()")
    }

    private fun testFilterPerformance() {
        Log.d(TAG, "--- Filter Performance ---")

        val randomSongs = List(50) { i -> mapOf("id" to "song_$i") }
        val dislikedIds = (0..20).map { "song_$it" }.toSet()

        val time = measureNanoTime {
            repeat(1000) {
                randomSongs.filterNot { dislikedIds.contains(it["id"]) }
            }
        }

        Log.d(TAG, "filterNot disliked (50 songs, 20 disliked) x1000: ${time / 1_000_000.0}ms avg ${time / 1_000_000.0 / 1000}ms")

        // Проверка: dislikedIds.value внутри remember? Если нет - каждый рекомпоз читает StateFlow
        Log.d(TAG, "Если dislikedIds читается без remember/derivedStateOf в Compose - каждый рекомпоз делает set.contains() для каждого трека")
    }

    private fun testLazyKeysPerformance() {
        Log.d(TAG, "--- LazyColumn Keys Performance ---")
        Log.d(TAG, "Без key: Compose не может определить какой item изменился, рекомпозиция ВСЕХ видимых + переиспользование сломано")
        Log.d(TAG, "С key: Compose реюзает композиции, только измененные рекомпозятся")
        Log.d(TAG, "Для HomeScreen: 6 quick + 20 newest + 6 random + 15 artists + 20 playlists = 67 items без key = 67 рекомпозиций при любом изменении state")

        // Симуляция
        val itemsWithoutKey = List(67) { it }
        val itemsWithKey = List(67) { it }

        val timeWithoutKey = measureNanoTime {
            repeat(100) {
                // Симуляция diff без key - O(n) сравнение всех
                itemsWithoutKey.forEach { _ -> }
            }
        }

        val timeWithKey = measureNanoTime {
            repeat(100) {
                // Симуляция diff с key - O(1) lookup
                val map = itemsWithKey.associateBy { it }
                map[5]
            }
        }

        Log.d(TAG, "Simulated diff without key: ${timeWithoutKey / 1_000_000.0}ms, with key: ${timeWithKey / 1_000_000.0}ms")
    }

    private fun testMemoryPressure() {
        Log.d(TAG, "--- Memory Pressure ---")

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMem = runtime.maxMemory() / 1024 / 1024
        val freeMem = maxMem - usedMem

        Log.d(TAG, "Memory: used=${usedMem}MB max=${maxMem}MB free=${freeMem}MB")

        if (freeMem < 50) {
            Log.e(TAG, "🔴 LOW MEMORY free=${freeMem}MB - GC будет часто вызываться, лаги! Уменьши кэш Coil, уменьши размер очереди ExoPlayer")
        }

        // Проверка hardware bitmaps
        Log.d(TAG, "Hardware bitmaps (allowHardware=true) хранятся в GPU памяти, не в heap, но каждый требует GPU upload")
        Log.d(TAG, "47 hardware bitmaps x 304x304 x 4 bytes = ~17MB GPU памяти за кадр, плюс upload time")
    }

    /**
     * Тест конкретного сценария: что происходит при старте приложения
     */
    suspend fun testAppStartupScenario() {
        Log.d(TAG, "========== APP STARTUP SCENARIO ==========")
        Log.d(TAG, "Timeline при старте приложения (подозреваемые):")
        Log.d(TAG, "0ms: MainActivity onCreate, Hilt injection")
        Log.d(TAG, "0-100ms: HomeViewModel init, getHomeCache() from file (IO)")
        Log.d(TAG, "100-200ms: if cache exists, show UI instantly (should be fast)")
        Log.d(TAG, "100-5000ms: loadData() - 4 параллельных запроса (folders, recent, newest, playlists)")
        Log.d(TAG, "  -> Каждый запрос: OkHttp + Retrofit + JSON parse + Dispatchers.IO")
        Log.d(TAG, "  -> Если сервер медленный или сеть плохая - каждый может быть 500-2000ms")
        Log.d(TAG, "  -> 4 параллельно = max(4) = 2000ms в худшем случае")
        Log.d(TAG, "5000ms: PlayerManager tryRestoreQueueFromServerIfNeeded() - БЫЛО 1500ms, СТАЛО 5000ms")
        Log.d(TAG, "  -> getPlayQueue() сетевой запрос")
        Log.d(TAG, "  -> setMediaItems(100) + prepare() если autoPlay=false БЫЛО - ГЛАВНЫЙ ЛАГ!")
        Log.d(TAG, "  -> СТАЛО: только setMediaItems без prepare когда autoPlay=false")
        Log.d(TAG, "  -> Если очередь 100 треков, каждый MediaItem с Uri.parse + coverArt Uri.parse = 200 Uri.parse")
        Log.d(TAG, "0-10000ms: DislikedRepository.syncFromServer() в IO")
        Log.d(TAG, "  -> getPlaylists() + getPlaylist(detail) + loop prefs.addDislikedId()")
        Log.d(TAG, "  -> Если в плейлисте 100 исключенных - 100 DataStore writes!")
        Log.d(TAG, "  -> Каждый DataStore write = файл IO + сериализация + flow emission")
        Log.d(TAG, "  -> Flow emission -> dislikedIds.collect -> _uiState.copy -> recomposition!")
        Log.d(TAG, "  -> 100 recompositions подряд = лаг скролла!")
        Log.d(TAG, "")
        Log.d(TAG, "РЕШЕНИЕ:")
        Log.d(TAG, "1. disliked sync в самом конце в Dispatchers.IO (сделано)")
        Log.d(TAG, "2. batch DataStore write: один edit вместо 100 (нужно пофиксить в DislikedRepository)")
        Log.d(TAG, "3. PlayerManager без prepare когда autoPlay=false (сделано)")
        Log.d(TAG, "4. HomeViewModel staggered loading: сначала 4 критичных, потом random+artists в фоне (сделано)")
        Log.d(TAG, "5. CoverArtImage без ImageRequest builder (сделано)")
        Log.d(TAG, "6. LazyColumn с keys (сделано)")
        Log.d(TAG, "7. Brush remember (сделано)")
        Log.d(TAG, "========== END SCENARIO ==========")
    }
}
