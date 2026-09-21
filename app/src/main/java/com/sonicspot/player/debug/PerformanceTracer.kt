package com.sonicspot.player.debug

import android.os.Looper
import android.util.Log
import com.sonicspot.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Главный трейсер для поиска лагов на HomeScreen.
 * Пишет в Logcat с тегом [SonicLag] + сохраняет в память для DebugOverlay.
 * В релизе полностью отключен для 0 overhead на main.
 */
object PerformanceTracer {
    private const val TAG = "SonicLag"
    private const val SLOW_THRESHOLD_MS = 16L
    private const val VERY_SLOW_THRESHOLD_MS = 100L

    data class Span(
        val tag: String,
        var startNs: Long = 0L,
        var endNs: Long = 0L,
        var thread: String = "",
        var isMainThread: Boolean = false,
        var extra: String = ""
    ) {
        val durationMs: Double get() = (endNs - startNs) / 1_000_000.0
        val isSlow: Boolean get() = durationMs > SLOW_THRESHOLD_MS
        val isVerySlow: Boolean get() = durationMs > VERY_SLOW_THRESHOLD_MS
    }

    private val activeSpans = ConcurrentHashMap<String, Span>()
    private val completedSpans = mutableListOf<Span>()
    private val lock = Any()
    private const val MAX_COMPLETED = 200

    data class HomeLoadReport(
        var cacheReadMs: Double = 0.0,
        var foldersMs: Double = 0.0,
        var recentMs: Double = 0.0,
        var newestMs: Double = 0.0,
        var playlistsMs: Double = 0.0,
        var randomMs: Double = 0.0,
        var artistsMs: Double = 0.0,
        var dislikedSyncMs: Double = 0.0,
        var starredSyncMs: Double = 0.0,
        var cacheSaveMs: Double = 0.0,
        var totalMs: Double = 0.0,
        var cacheHit: Boolean = false
    ) {
        fun toLog(): String = """
            ===== HOME LOAD REPORT =====
            cacheHit=$cacheHit | cacheRead=${cacheReadMs.format()}ms
            CRITICAL PATH:
              folders=${foldersMs.format()}ms
              recent(12)=${recentMs.format()}ms
              newest(12)=${newestMs.format()}ms
              playlists=${playlistsMs.format()}ms
              cacheSave=${cacheSaveMs.format()}ms
              TOTAL critical=${totalMs.format()}ms
            BACKGROUND:
              random(20)=${randomMs.format()}ms
              artists=${artistsMs.format()}ms
              dislikedSync=${dislikedSyncMs.format()}ms
              starredSync=${starredSyncMs.format()}ms
            DIAGNOSIS:
              ${diagnose()}
            ============================
        """.trimIndent()

        private fun diagnose(): String {
            val sb = StringBuilder()
            if (totalMs > 2000) sb.append("CRITICAL >2s ")
            else if (totalMs > 1000) sb.append("CRITICAL >1s ")
            else sb.append("CRITICAL OK <1s. ")
            val slowest = listOf(
                "folders" to foldersMs,
                "recent" to recentMs,
                "newest" to newestMs,
                "playlists" to playlistsMs
            ).maxByOrNull { it.second }
            if (slowest != null && slowest.second > 500) {
                sb.append("Slowest: ${slowest.first}=${slowest.second.format()}ms. ")
            }
            if (dislikedSyncMs > 1000) sb.append("dislikedSync ${dislikedSyncMs.format()}ms ")
            if (randomMs > 2000) sb.append("random ${randomMs.format()}ms ")
            if (cacheSaveMs > 200) sb.append("cacheSave ${cacheSaveMs.format()}ms ")
            if (sb.isEmpty()) sb.append("OK")
            return sb.toString()
        }
    }

    var lastHomeReport: HomeLoadReport? = null
        private set

    fun newHomeReport(): HomeLoadReport {
        val r = HomeLoadReport()
        lastHomeReport = r
        return r
    }

    private fun Double.format(): String = String.format("%.1f", this)

    fun start(tag: String, extra: String = "") {
        if (!BuildConfig.DEBUG) return
        val isMain = Looper.myLooper() == Looper.getMainLooper()
        val span = Span(
            tag = tag,
            startNs = System.nanoTime(),
            thread = Thread.currentThread().name,
            isMainThread = isMain,
            extra = extra
        )
        activeSpans[tag] = span
        if (isMain) {
            Log.d(TAG, "START [$tag] on MAIN $extra")
        } else {
            Log.d(TAG, "START [$tag] on ${span.thread} $extra")
        }
    }

    fun end(tag: String): Double {
        if (!BuildConfig.DEBUG) return 0.0
        val span = activeSpans.remove(tag) ?: run {
            Log.w(TAG, "END without START: $tag")
            return -1.0
        }
        span.endNs = System.nanoTime()
        val dur = span.durationMs
        synchronized(lock) {
            completedSpans.add(span)
            if (completedSpans.size > MAX_COMPLETED) completedSpans.removeAt(0)
        }
        val icon = when {
            span.isVerySlow && span.isMainThread -> "LAG"
            span.isSlow && span.isMainThread -> "JANK"
            span.isMainThread -> "MAIN"
            else -> "BG"
        }
        // Логируем ТОЛЬКО медленные спаны. Раньше сюда попадал КАЖДЫЙ main-спан
        // (даже 0.1мс) — сотни строк logcat на каждую загрузку экрана, и в debug-сборке
        // сам logcat добавлял джанк. Быстрые спаны и так видны в DebugOverlay.
        if (dur > SLOW_THRESHOLD_MS) {
            Log.d(TAG, "$icon [$tag] ${dur.format()}ms thread=${span.thread} main=${span.isMainThread} ${span.extra}")
        }
        if (dur > VERY_SLOW_THRESHOLD_MS && span.isMainThread) {
            Log.e(TAG, "VERY SLOW ON MAIN [$tag] ${dur.format()}ms ${span.extra}")
        }
        return dur
    }

    suspend fun <T> measure(tag: String, extra: String = "", block: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        start(tag, extra)
        try {
            return block()
        } finally {
            end(tag)
        }
    }

    inline fun <T> measureBlocking(tag: String, extra: String = "", block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        start(tag, extra)
        try {
            return block()
        } finally {
            end(tag)
        }
    }

    suspend fun <T> measureIo(tag: String, block: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return withContext(Dispatchers.IO) { block() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "IO ON MAIN THREAD! [$tag]")
        }
        return measure(tag) { withContext(Dispatchers.IO) { block() } }
    }

    fun log(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "[$tag] $message thread=${Thread.currentThread().name} main=${Looper.myLooper() == Looper.getMainLooper()}")
    }

    fun logMainThreadViolation(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "MAIN THREAD VIOLATION [$tag] $message")
        }
    }

    fun getRecentSpans(limit: Int = 50): List<Span> = synchronized(lock) {
        completedSpans.takeLast(limit).reversed()
    }

    fun getSlowSpansOnMain(): List<Span> = synchronized(lock) {
        completedSpans.filter { it.isMainThread && it.isVerySlow }
    }

    fun dumpReport(): String {
        if (!BuildConfig.DEBUG) return "DEBUG only"
        val sb = StringBuilder()
        sb.appendLine("=== SonicLag Report ===")
        lastHomeReport?.let { sb.appendLine(it.toLog()) }
        sb.appendLine("\n--- Recent spans ---")
        getRecentSpans(30).forEach {
            sb.appendLine("${if (it.isMainThread) "MAIN" else "BG"} ${it.tag} ${it.durationMs.format()}ms ${it.extra}")
        }
        sb.appendLine("\n--- Slow on Main (>100ms) ---")
        getSlowSpansOnMain().forEach {
            sb.appendLine("${it.tag} ${it.durationMs.format()}ms ${it.extra}")
        }
        return sb.toString()
    }

    fun clear() {
        if (!BuildConfig.DEBUG) return
        activeSpans.clear()
        synchronized(lock) { completedSpans.clear() }
    }
}
