package com.sonicspot.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sonicspot.player.debug.LagDiagnosticRunner
import com.sonicspot.player.debug.PerformanceTracer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented тесты для проверки лагов на реальном устройстве.
 * Запуск: ./gradlew connectedDebugAndroidTest --tests "*HomeLagInstrumentedTest*"
 * Или через Android Studio: Run -> HomeLagInstrumentedTest
 *
 * Смотри Logcat с фильтром "SonicLag"
 */
@RunWith(AndroidJUnit4::class)
class HomeLagInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testQuickCheck() {
        // Быстрая проверка без контекста
        LagDiagnosticRunner.quickCheck()

        // Выводим отчет
        println(PerformanceTracer.dumpReport())
    }

    @Test
    fun testFullDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Запускаем полный набор тестов
        kotlinx.coroutines.runBlocking {
            LagDiagnosticRunner.runAll(context)
            LagDiagnosticRunner.testAppStartupScenario()
        }

        println(PerformanceTracer.dumpReport())
    }

    @Test
    fun testBrushCreationOnDevice() {
        // Тест создания Brush на реальном устройстве
        val iterations = 1000
        val time = kotlin.system.measureNanoTime {
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

        println("Brush creation x$iterations on device: ${time / 1_000_000.0}ms")
        println("Avg: ${time / 1_000_000.0 / iterations}ms")

        // На реальном устройстве должно быть <0.1ms avg
        assert(time / 1_000_000.0 / iterations < 1.0) { "Brush creation too slow" }
    }

    @Test
    fun testJsonParsingOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Тест парсинга home_cache.json если есть
        val homeCacheFile = java.io.File(context.cacheDir, "home_cache.json")

        if (homeCacheFile.exists()) {
            val content = homeCacheFile.readText()
            println("Home cache file size: ${content.length} chars, ${content.length / 1024}KB")

            val time = kotlin.system.measureTimeMillis {
                repeat(10) {
                    org.json.JSONObject(content)
                }
            }

            println("Parse home_cache.json x10: ${time}ms avg ${time / 10}ms")

            if (time / 10 > 100) {
                println("🔴 Home cache parse SLOW! File too big or device slow")
            }
        } else {
            println("No home_cache.json file - first launch")
        }
    }

    @Test
    fun testCoilCacheOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val cacheDir = context.cacheDir
        val imageCache = java.io.File(cacheDir, "image_cache")
        val coilCache = java.io.File(cacheDir, "coil")

        println("Cache dir: ${cacheDir.absolutePath}")
        println("image_cache exists: ${imageCache.exists()}, size: ${imageCache.listFiles()?.size ?: 0} files")
        println("coil exists: ${coilCache.exists()}, size: ${coilCache.listFiles()?.size ?: 0} files")

        val imageCacheSize = try {
            imageCache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }

        val coilCacheSize = try {
            coilCache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }

        println("image_cache total size: ${imageCacheSize / 1024 / 1024}MB")
        println("coil total size: ${coilCacheSize / 1024 / 1024}MB")

        if (imageCacheSize + coilCacheSize > 500 * 1024 * 1024) {
            println("🔴 Coil cache >500MB - too big, may cause memory pressure and GC lag")
        }
    }

    @Test
    fun testDataStoreOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Проверяем DataStore файлы
        val dataStoreDir = java.io.File(context.filesDir, "datastore")

        if (dataStoreDir.exists()) {
            val files = dataStoreDir.listFiles()
            println("DataStore files: ${files?.size ?: 0}")

            files?.forEach { file ->
                println("  ${file.name}: ${file.length()} bytes, modified ${java.util.Date(file.lastModified())}")

                if (file.length() > 100 * 1024) {
                    println("  🔴 DataStore file >100KB - too big, may cause slow reads")
                    println("  Check if dislikedIds set too large or other prefs bloated")
                }
            }
        } else {
            println("No DataStore dir yet")
        }
    }

    @Test
    fun testMemoryOnDevice() {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMem = runtime.maxMemory() / 1024 / 1024
        val totalMem = runtime.totalMemory() / 1024 / 1024
        val freeMem = maxMem - usedMem

        println("Memory: used=${usedMem}MB total=${totalMem}MB max=${maxMem}MB free=${freeMem}MB")

        if (freeMem < 50) {
            println("🔴 LOW MEMORY! free=${freeMem}MB - GC will be frequent, lag!")
        }

        // Проверяем количество потоков
        val threadCount = Thread.activeCount()
        println("Active threads: $threadCount")

        if (threadCount > 50) {
            println("🟡 Many threads: $threadCount - may cause context switching overhead")
        }
    }
}
