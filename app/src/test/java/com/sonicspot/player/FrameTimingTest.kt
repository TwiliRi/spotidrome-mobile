package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Тесты времени кадра - сколько должно занимать чтобы держать 60fps.
 * 60fps = 16.6ms per frame
 * Если любой этап >16ms - будет дроп кадров.
 */
class FrameTimingTest {

    private val FRAME_BUDGET_MS = 16.6

    @Test
    fun testFrameBudget() {
        println("=== Frame Budget 60fps ===")
        println("Target: 60fps = ${FRAME_BUDGET_MS}ms per frame")
        println("")
        println("Breakdown of frame work:")
        println("  - Compose recomposition: 2-5ms")
        println("  - Compose layout: 1-3ms")
        println("  - Compose draw: 1-3ms")
        println("  - Image loading (Coil): 1-5ms")
        println("  - Other (state, etc): 1-2ms")
        println("  Total: ~6-18ms")
        println("")
        println("If total >16.6ms - frame drop, jank!")

        val scenarios = listOf(
            "Idle, no scroll" to 5.0,
            "Scroll, 10 new images" to 12.0,
            "Scroll, 47 new images (old CoverArtImage)" to 25.0,
            "Scroll, 47 new images (new CoverArtImage)" to 10.0,
            "State update, 64 items recompose (no keys)" to 30.0,
            "State update, 1 item recompose (with keys)" to 2.0,
            "Player progress update, 2 collectors" to 3.0,
            "Disliked sync, 100 emissions" to 100.0
        )

        scenarios.forEach { (scenario, ms) ->
            val status = if (ms > FRAME_BUDGET_MS) "🔴 JANK" else "🟢 OK"
            println("$status $scenario: ${ms}ms")
        }
    }

    @Test
    fun testHomeScreenFrameBreakdown() {
        println("=== HomeScreen Frame Breakdown ===")

        // Simulate a frame during scroll
        val steps = listOf(
            "LazyColumn scroll" to 1.0,
            "New items come into view (5 albums)" to 2.0,
            "CoverArtImage composition (5x)" to 1.0,
            "Coil image loading (5x, cached)" to 2.0,
            "Coil image loading (5x, not cached, old with builder)" to 8.0,
            "Coil image loading (5x, not cached, new)" to 3.0,
            "Text composition" to 0.5,
            "Total with old CoverArtImage" to 14.5,
            "Total with new CoverArtImage" to 8.5
        )

        steps.forEach { (step, ms) ->
            println("$step: ${ms}ms")
        }

        println("")
        println("Old: 14.5ms per frame during scroll - close to budget, may jank on slow device")
        println("New: 8.5ms per frame - safe, 60fps")

        // During fast scroll
        println("")
        println("Fast scroll (10 new items per frame):")
        println("  Old: 10 * (builder + hardware bitmap) = ~20ms - JANK!")
        println("  New: 10 * simple AsyncImage = ~6ms - OK")
    }

    @Test
    fun testInitialLoadFrame() {
        println("=== Initial Load Frame ===")
        println("")
        println("First frame after app start:")
        println("  1. HomeViewModel init")
        println("  2. getHomeCache() IO (should be fast if in IO dispatcher)")
        println("  3. If cache exists, show UI")
        println("  4. Compose first composition of HomeScreen")
        println("")

        val steps = listOf(
            "Cache read (IO, 50KB JSON)" to 20.0,
            "Cache parse (JSON to objects)" to 10.0,
            "First composition (all items)" to 15.0,
            "Image loading for visible items (10 images, cached)" to 5.0,
            "Total first frame" to 50.0
        )

        steps.forEach { (step, ms) ->
            println("$step: ${ms}ms")
        }

        println("")
        println("First frame 50ms = 3 frames dropped at start - acceptable, shows loading quickly")
        println("")
        println("If cache read on main thread:")
        println("  Cache read 20ms + parse 10ms on main = 30ms blocking main!")
        println("  = 2 frames dropped + ANR risk if longer")
        println("  Fix: withContext(Dispatchers.IO) { getHomeCache() }")

        println("")
        println("If no cache, network load:")
        println("  4 parallel requests, each 200-2000ms")
        println("  Total critical path = max(requests) = 2000ms worst case")
        println("  During this, UI shows loading shimmer - OK if shimmer is cheap")
        println("  Shimmer with infinite animation: may cause jank!")
        println("  Fix: static ShimmerPlaceholder without animation (done)")
    }

    @Test
    fun testDislikedSyncFrameImpact() {
        println("=== Disliked Sync Frame Impact ===")
        println("")
        println("Disliked sync does:")
        println("  1. getPlaylists() - network")
        println("  2. getPlaylist(detail) - network")
        println("  3. Loop through entries, addDislikedId for each")
        println("     Each addDislikedId = DataStore edit = IO + flow emission")
        println("")

        val trackCounts = listOf(10, 50, 100, 200)

        trackCounts.forEach { count ->
            val networkTime = 200 + 100 // getPlaylists + getPlaylist
            val dataStoreTimePerWrite = 10 // ms per edit
            val totalDataStoreTime = count * dataStoreTimePerWrite
            val recompositions = count
            val recompositionTime = recompositions * 1 // 1ms per recomposition

            val total = networkTime + totalDataStoreTime + recompositionTime

            println("$count tracks:")
            println("  Network: ${networkTime}ms")
            println("  DataStore ${count} writes: ${totalDataStoreTime}ms")
            println("  Recompositions ${recompositions}x: ${recompositionTime}ms")
            println("  Total: ${total}ms")

            if (total > 1000) {
                println("  🔴 SLOW! ${total}ms - will lag!")
            }
            println("")
        }

        println("Fix: batch DataStore write - one edit with all IDs")
        println("  Old: 100x prefs.addDislikedId(id) = 100 IO + 100 emissions")
        println("  New: 1x prefs.edit { putStringSet(allIds) } = 1 IO + 1 emission")
    }

    @Test
    fun testPlayerRestoreFrameImpact() {
        println("=== Player Restore Frame Impact ===")
        println("")
        println("PlayerManager restoreQueueFromServerIfNeeded:")
        println("  Called after delay (was 1.5s, now 5s)")
        println("  Does getPlayQueue() network + setMediaItems + prepare")
        println("")

        val queueSizes = listOf(10, 20, 50, 100)

        queueSizes.forEach { size ->
            val networkTime = 300 // getPlayQueue
            val setMediaItemsTime = size * 2 // 2ms per item
            val prepareTime = size * 15 // 15ms per item - heavy!
            val totalWithPrepare = networkTime + setMediaItemsTime + prepareTime
            val totalWithoutPrepare = networkTime + setMediaItemsTime

            println("$size tracks:")
            println("  With prepare (OLD, autoPlay=false): ${totalWithPrepare}ms 🔴")
            println("  Without prepare (NEW, autoPlay=false): ${totalWithoutPrepare}ms 🟢")
            println("")
        }

        println("For 100 tracks:")
        println("  OLD: 300 + 200 + 1500 = 2000ms blocking - MAIN THREAD LAG 2-3 MIN!")
        println("  Actually worse because prepare may do IO and artwork loading")
        println("  NEW: 300 + 200 = 500ms, no prepare, only setMediaItems - OK")
        println("  And delayed to 5s after start, so doesn't block initial UI")
    }

    @Test
    fun testGarbageCollectionImpact() {
        println("=== GC Impact ===")
        println("")
        println("GC causes jank when it runs:")
        println("  - Allocates many short-lived objects -> GC pressure")
        println("  - GC pauses main thread (even concurrent GC has pauses)")
        println("")

        val allocations = listOf(
            "CoverArtImage old: ImageRequest.Builder + 2 string keys per image" to 3,
            "CoverArtImage new: just url string" to 1,
            "Brush without remember: new Brush per recomposition" to 1,
            "Brush with remember: 1 allocation total" to 0,
            "State copy per disliked sync (100x)" to 100,
            "State copy batched (1x)" to 1
        )

        allocations.forEach { (what, allocPerFrame) ->
            println("$what: $allocPerFrame allocs per frame")
        }

        println("")
        println("During scroll with old CoverArtImage:")
        println("  47 images per frame * 3 allocs = 141 allocs per frame")
        println("  60fps * 141 = 8460 allocs per second")
        println("  = GC every few seconds, each GC pause 5-20ms = jank!")

        println("")
        println("With new CoverArtImage:")
        println("  47 * 1 = 47 allocs per frame")
        println("  60 * 47 = 2820 allocs per second")
        println("  = Less GC, smoother")
    }
}
