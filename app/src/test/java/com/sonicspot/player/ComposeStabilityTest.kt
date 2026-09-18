package com.sonicspot.player

import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Тесты стабильности Compose - поиск причин рекомпозиций.
 */
class ComposeStabilityTest {

    @Test
    fun testBrushRemember() {
        println("=== Brush Remember ===")
        println("")
        println("BAD:")
        println("  @Composable fun HomeScreen() {")
        println("    Box(modifier = Modifier.background(Brush.verticalGradient(...)))")
        println("  }")
        println("  // Brush создается каждый рекомпоз!")
        println("  // Box получает новый Modifier каждый раз -> рекомпозиция Box + всех детей")
        println("")
        println("GOOD:")
        println("  @Composable fun HomeScreen() {")
        println("    val brush = remember { Brush.verticalGradient(...) }")
        println("    Box(modifier = Modifier.background(brush))")
        println("  }")
        println("  // Brush создается один раз, remember сохраняет")
        println("")

        val iterations = 1000
        val timeWithoutRemember = measureNanoTime {
            repeat(iterations) {
                // Симуляция создания Brush каждый раз
                val brush = mapOf("colors" to listOf(0xFF2A2A2A, 0xFF000000), "startY" to 0f, "endY" to 600f)
            }
        }

        val timeWithRemember = measureNanoTime {
            val brush = mapOf("colors" to listOf(0xFF2A2A2A, 0xFF000000), "startY" to 0f, "endY" to 600f)
            repeat(iterations) {
                // Используем запомненный
                val b = brush
            }
        }

        println("Without remember x$iterations: ${timeWithoutRemember / 1_000_000.0}ms")
        println("With remember x$iterations: ${timeWithRemember / 1_000_000.0}ms")
        println("Remember is ${timeWithoutRemember.toDouble() / timeWithRemember}x faster")
        println("")
        println("But main benefit is not speed, it's stability:")
        println("  Without remember: new object every recomposition -> Modifier changed -> recompose children")
        println("  With remember: same object -> Modifier stable -> no recomposition")
    }

    @Test
    fun testDerivedStateOf() {
        println("=== derivedStateOf ===")
        println("")
        println("BAD:")
        println("  val quickAlbums = remember(state.recentAlbums, state.newestAlbums) {")
        println("    (state.recentAlbums.take(4) + state.newestAlbums.take(2)).distinctBy { it.id }.take(6)")
        println("  }")
        println("  // remember с keys - пересчитывается когда recent или newest меняются")
        println("  // Но: если state.recentAlbums - новый список с теми же данными (из-за copy),")
        println("  // remember все равно пересчитает, даже если данные не изменились!")
        println("")
        println("GOOD:")
        println("  val quickAlbums by remember(state.recentAlbums, state.newestAlbums) {")
        println("    derivedStateOf {")
        println("      (state.recentAlbums.take(4) + state.newestAlbums.take(2)).distinctBy { it.id }.take(6)")
        println("    }")
        println("  }")
        println("  // derivedStateOf - пересчитывается только когда результат чтения изменился")
        println("  // Более стабильный, меньше рекомпозиций")

        data class Album(val id: String)

        val recent1 = List(4) { Album("recent_$it") }
        val newest1 = List(2) { Album("newest_$it") }
        val recent2 = List(4) { Album("recent_$it") } // same data, new list object
        val newest2 = List(2) { Album("newest_$it") }

        val timeRemember = measureNanoTime {
            repeat(1000) {
                // Simulate remember with new list objects
                val quick = (recent2.take(4) + newest2.take(2)).distinctBy { it.id }.take(6)
            }
        }

        println("")
        println("Recalculation x1000: ${timeRemember / 1_000_000.0}ms")

        println("")
        println("With remember but new list objects (same data):")
        println("  - remember sees new list objects (different references)")
        println("  - Recalculates even though data same")
        println("  - Returns new list -> triggers recomposition of quick access cards")
        println("")
        println("With derivedStateOf:")
        println("  - Tracks actual reads inside")
        println("  - Only recalculates if read values changed")
        println("  - More stable")
    }

    @Test
    fun testStateFlowCollection() {
        println("=== StateFlow Collection in Compose ===")
        println("")
        println("HomeScreen collects:")
        println("  val state by viewModel.uiState.collectAsState()")
        println("  val currentSongId by viewModel.playerManager.currentSongFlow.collectAsState()")
        println("  val likedIds by viewModel.likedIds.collectAsState()")
        println("  val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()")
        println("")
        println("Each collectAsState causes recomposition when flow emits")
        println("")
        println("If dislikedRepository.syncFromServer does 100 DataStore writes:")
        println("  - Each write emits dislikedIdsFlow")
        println("  - Each emission causes _uiState.copy or direct collect")
        println("  - 100 recompositions in quick succession = lag!")
        println("")
        println("Fix: batch writes, debounce, or collect in IO and update once")

        val emissions = 100
        val time = measureNanoTime {
            repeat(emissions) {
                // Simulate state copy and recomposition trigger
                val state = mapOf("pinnedIds" to setOf("id_$it"))
            }
        }

        println("")
        println("$emissions state emissions: ${time / 1_000_000.0}ms")
        println("If each triggers recomposition of 64 items:")
        println("  ${emissions} * 64 = ${emissions * 64} item recompositions")
        println("  At 1ms per item: ${emissions * 64}ms total - will lag!")
    }

    @Test
    fun testGreetingRemember() {
        println("=== Greeting Remember ===")

        val timeWithCalendar = measureNanoTime {
            repeat(1000) {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val greeting = when (hour) {
                    in 5..11 -> "Доброе утро"
                    in 12..17 -> "Добрый день"
                    in 18..22 -> "Добрый вечер"
                    else -> "Доброй ночи"
                }
            }
        }

        println("Greeting with Calendar.getInstance() x1000: ${timeWithCalendar / 1_000_000.0}ms")

        println("")
        println("Calendar.getInstance() is relatively heavy (creates new instance, reads system time)")
        println("Should be remember { ... } - currently is remember { } without keys, so once per composition")
        println("Good: remember { Calendar... } - only once")
        println("Even better: remember { } with no keys is ok for greeting, it doesn't change often")

        // Check if greeting changes - only once per hour, so remember without keys is fine
        println("Greeting changes only once per hour, so remember without keys is OK")
    }

    @Test
    fun testPullToRefreshBox() {
        println("=== PullToRefreshBox ===")
        println("")
        println("OLD: PullToRefreshBox inside Box with gradient background")
        println("  - PullToRefreshBox has its own nested scroll handling")
        println("  - Inside Box with background - may cause extra recompositions")
        println("  - No keys for LazyColumn items")
        println("")
        println("NEW: SpotifyPullToRefreshBox wrapper")
        println("  - Wrapper around PullToRefreshBox with remembered state")
        println("  - Should be more stable")

        println("")
        println("PullToRefresh performance:")
        println("  - rememberPullToRefreshState() creates state")
        println("  - Should be remembered, not recreated")
        println("  - Currently: val pullState = rememberPullToRefreshState() - good, remembered")
        println("  - But not used in SpotifyPullToRefreshBox? Check implementation")

        println("")
        println("Nested scroll:")
        println("  - LazyColumn inside PullToRefreshBox")
        println("  - Both have scroll handling")
        println("  - Can cause jank if not properly coordinated")
        println("  - SpotifyPullToRefreshBox should handle nested scroll correctly")
    }

    @Test
    fun testContentType() {
        println("=== ContentType for LazyColumn ===")
        println("")
        println("Without contentType: Compose cannot optimize, treats all items same")
        println("With contentType: Compose can reuse compositions for same type")
        println("")
        println("HomeScreen items:")
        println("  header: contentType=header")
        println("  greeting: contentType=greeting")
        println("  quick: contentType=quick")
        println("  pinned: contentType=playlists")
        println("  private: contentType=playlists")
        println("  public: contentType=playlists")
        println("  newest: contentType=albums")
        println("  random: contentType=songs")
        println("  artists: contentType=artists")
        println("  loading: contentType=loading")
        println("")
        println("And for LazyRow items:")
        println("  items(playlists, key={id}, contentType={playlist})")
        println("  items(albums, key={id}, contentType={album})")
        println("  etc.")
        println("")
        println("This helps Compose reuse and skip recompositions")
        println("Before fix: no keys, no contentType - worst performance")
        println("After fix: keys + contentType - best performance")
    }
}
