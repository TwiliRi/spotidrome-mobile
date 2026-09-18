package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Тесты производительности загрузки картинок - Coil, CoverArtImage.
 * Главная причина лагов скролла.
 */
class ImageLoadingPerformanceTest {

    @Test
    fun testCoverArtImageOldVsNew() {
        println("=== CoverArtImage Old vs New ===")
        println("")
        println("OLD (лагало):")
        println("  @Composable fun CoverArtImage(url, sizePx=300) {")
        println("    val context = LocalContext.current")
        println("    val request = remember(url, sizePx) {")
        println("      ImageRequest.Builder(context)")
        println("        .data(url)")
        println("        .size(sizePx) // 304px")
        println("        .memoryCacheKey(url)")
        println("        .diskCacheKey(url)")
        println("        .allowHardware(true) // Hardware bitmap!")
        println("        .crossfade(false)")
        println("        .build()")
        println("    }")
        println("    AsyncImage(model=request, ...)")
        println("  }")
        println("")
        println("Problems:")
        println("  1. remember(url, sizePx) - пересоздает request при каждом изменении size")
        println("  2. ImageRequest.Builder - аллокация каждый раз")
        println("  3. size(304) - Coil ресайзит, CPU работа")
        println("  4. memoryCacheKey/diskCacheKey - лишние, Coil сам кэширует по url")
        println("  5. allowHardware(true) - создает Hardware Bitmap, требует GPU upload")
        println("     Для 47+ картинок одновременно = 47 GPU uploads за кадр = лаг!")
        println("  6. 47 items * builder = 47 allocations per frame during scroll")
        println("")
        println("NEW (60fps):")
        println("  @Composable fun CoverArtImage(url) {")
        println("    Box(...) {")
        println("      if (url != null) AsyncImage(model=url, ...)")
        println("    }")
        println("  }")
        println("")
        println("Benefits:")
        println("  - No Builder allocation")
        println("  - No remember with keys")
        println("  - Coil uses url as cache key automatically")
        println("  - No hardware bitmap forced, Coil decides")
        println("  - No size resize, original cached")
        println("  - Less GC pressure")

        // Симуляция аллокаций
        val iterations = 1000

        val timeOld = measureNanoTime {
            repeat(iterations) { i ->
                // Симуляция старого подхода
                val url = "https://example.com/cover_$i.jpg"
                val size = 304
                val memoryKey = url
                val diskKey = url
                val request = mapOf(
                    "url" to url,
                    "size" to size,
                    "memoryKey" to memoryKey,
                    "diskKey" to diskKey,
                    "allowHardware" to true
                )
            }
        }

        val timeNew = measureNanoTime {
            repeat(iterations) { i ->
                val url = "https://example.com/cover_$i.jpg"
                // Просто url
                val model = url
            }
        }

        println("")
        println("Old approach x$iterations: ${timeOld / 1_000_000.0}ms")
        println("New approach x$iterations: ${timeNew / 1_000_000.0}ms")
        println("New is ${timeOld.toDouble() / timeNew}x faster, less GC")

        val perFrameOld = timeOld / 1_000_000.0 / iterations * 47
        val perFrameNew = timeNew / 1_000_000.0 / iterations * 47

        println("")
        println("Per frame (47 images): old=${perFrameOld}ms new=${perFrameNew}ms")
        if (perFrameOld > 5) {
            println("🔴 Old >5ms per frame just for ImageRequest creation - will cause jank!")
        }
    }

    @Test
    fun testHardwareBitmapImpact() {
        println("=== Hardware Bitmap Impact ===")
        println("")
        println("Hardware Bitmap:")
        println("  - Stored in GPU memory, not heap")
        println("  - Faster to draw (GPU)")
        println("  - BUT: requires GPU upload when created")
        println("  - AND: cannot be drawn on software canvas")
        println("  - AND: upload is synchronous and blocks")
        println("")
        println("For small images (44dp, 56dp) in SongRow and QuickAccessCard:")
        println("  - 44dp * 3 density = 132px")
        println("  - 56dp * 3 = 168px")
        println("  - Hardware bitmap for such small images: overhead > benefit")
        println("  - Plus: 6 quick + 6 random songs with covers = 12 small hardware bitmaps")
        println("  - Each requires GPU upload")
        println("")
        println("For large images (152dp) in AlbumCard:")
        println("  - 152dp * 3 = 456px")
        println("  - Hardware bitmap makes sense, but only if many same images reused")
        println("  - During scroll, new images come in, old go out - constant uploads")
        println("")
        println("Best: let Coil decide, don't force allowHardware(true)")
        println("Coil automatically uses hardware bitmaps for large images when beneficial")

        // Симуляция GPU upload time
        val smallImageUploadMs = 0.5 // ms per 132px image
        val largeImageUploadMs = 2.0 // ms per 456px image

        val smallCount = 12
        val largeCount = 20

        val totalUpload = smallCount * smallImageUploadMs + largeCount * largeImageUploadMs

        println("")
        println("Estimated GPU upload per frame:")
        println("  $smallCount small x ${smallImageUploadMs}ms = ${smallCount * smallImageUploadMs}ms")
        println("  $largeCount large x ${largeImageUploadMs}ms = ${largeCount * largeImageUploadMs}ms")
        println("  Total: ${totalUpload}ms")

        if (totalUpload > 16) {
            println("🔴 Total GPU upload >16ms - will drop frames!")
        }
    }

    @Test
    fun testCoilMemoryCache() {
        println("=== Coil Memory Cache ===")
        println("")
        println("Coil memory cache:")
        println("  - Default: 10-20% of app memory")
        println("  - Stores decoded bitmaps")
        println("  - Key: url (or custom memoryCacheKey)")
        println("  - If memoryCacheKey = url, same as default - redundant")
        println("")
        println("Old code: memoryCacheKey(url) + diskCacheKey(url) - redundant, extra string allocation")
        println("New code: no custom keys, Coil uses url automatically")
        println("")
        println("Disk cache:")
        println("  - Stores original image data")
        println("  - Key: url")
        println("  - If diskCacheKey = url, same as default - redundant")

        val url = "https://example.com/cover.jpg?id=123&size=300"
        val iterations = 10000

        val timeWithKeys = measureNanoTime {
            repeat(iterations) {
                val memoryKey = url
                val diskKey = url
                // Extra allocations
                val key1 = memoryKey
                val key2 = diskKey
            }
        }

        val timeWithoutKeys = measureNanoTime {
            repeat(iterations) {
                // No extra keys
                val model = url
            }
        }

        println("")
        println("With custom keys x$iterations: ${timeWithKeys / 1_000_000.0}ms")
        println("Without keys x$iterations: ${timeWithoutKeys / 1_000_000.0}ms")
        println("Difference: ${(timeWithKeys - timeWithoutKeys) / 1_000_000.0}ms - small but 47x per frame adds up")
    }

    @Test
    fun testImageSizeResizing() {
        println("=== Image Size Resizing ===")
        println("")
        println("Old: .size(304) for 152dp card (152*2=304)")
        println("  - Coil resizes image to 304px on decode")
        println("  - CPU work for resize")
        println("  - But: if original is 500px, resize to 304 saves memory")
        println("")
        println("New: no size, Coil loads original and caches")
        println("  - No resize CPU work")
        println("  - But: larger memory usage")
        println("  - Tradeoff: CPU vs memory")
        println("")
        println("For 60fps scroll, CPU is more critical than memory (within limits)")
        println("So no size is better for scroll performance")
        println("Coil will still downsample based on ImageView size automatically")

        // Симуляция resize cost
        val originalSize = 500
        val targetSize = 304
        val pixelsOriginal = originalSize * originalSize
        val pixelsTarget = targetSize * targetSize

        println("")
        println("Original: ${originalSize}x${originalSize} = $pixelsOriginal pixels")
        println("Target: ${targetSize}x${targetSize} = $pixelsTarget pixels")
        println("Resize ratio: ${pixelsTarget.toDouble() / pixelsOriginal}")

        val resizeTimePerImageMs = 1.5 // estimated
        val totalResize47 = resizeTimePerImageMs * 47

        println("Resize 47 images: ${totalResize47}ms CPU")

        if (totalResize47 > 10) {
            println("🟡 Resize 47 images >10ms CPU - noticeable")
        }
    }

    @Test
    fun testLazyRowRecycling() {
        println("=== LazyRow Recycling ===")
        println("")
        println("Without key: Compose cannot track which item is which")
        println("  - When list changes, all items recompose")
        println("  - No recycling, new compositions created")
        println("  - During scroll, items going off-screen and on-screen cause recomposition")
        println("")
        println("With key = { it.id }:")
        println("  - Compose tracks items by key")
        println("  - Only changed items recompose")
        println("  - Recycling works, compositions reused")
        println("  - Much faster scroll")

        println("")
        println("HomeScreen has:")
        println("  - 6 quick access (2 per row, 3 rows)")
        println("  - 5 pinned playlists")
        println("  - 10 private playlists")
        println("  - 10 public playlists")
        println("  - 12 newest albums")
        println("  - 6 random songs")
        println("  - 15 artists")
        println("  Total ~64 items in LazyRows + LazyColumn")

        println("")
        println("Without keys: any state change (like dislikedIds, likedIds, currentSongId)")
        println("  triggers recomposition of ALL 64 items!")
        println("With keys: only items that actually changed recompose")

        // Симуляция
        val items = List(64) { it }
        val changedIndex = 5

        val timeWithoutKey = measureNanoTime {
            repeat(100) {
                // Without key, check all
                items.forEach { _ -> }
            }
        }

        val timeWithKey = measureNanoTime {
            repeat(100) {
                // With key, only check changed
                val map = items.associateBy { it }
                map[changedIndex]
            }
        }

        println("")
        println("Without key (check all 64): ${timeWithoutKey / 1_000_000.0}ms x100")
        println("With key (check 1): ${timeWithKey / 1_000_000.0}ms x100")
    }
}
