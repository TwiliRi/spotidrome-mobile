package com.sonicspot.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sonicspot.player.debug.PerformanceTracer
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Очередь загрузки элементов интерфейса как в Spotify
 *
 * Проблема из логов:
 * - home_cache.json 140KB с 1072 артистами -> decode 279ms
 * - Image decoding logging dropped x15 из-за 64 картинок одновременно (22 плейлиста + 12 newest + 15 артистов + 6 quick)
 * - Skipped 43 frames + Davey 1094ms из-за одной большой рекомпозиции всех секций сразу
 *
 * Решение:
 * 1. Каждая горизонтальная секция показывает только первые 3 элемента (чтобы нормально выглядело)
 * 2. Остальные подгружаются по мере скролла - когда пользователь доскроллил до предпоследнего видимого
 * 3. Загрузка идет через очередь с лимитом, чтобы не грузить 22 картинки одновременно
 * 4. Шиммер в конце показывает что есть еще данные
 */

@Composable
fun <T> PaginatedLazyRow(
    items: List<T>,
    initialVisible: Int = 3,
    pageSize: Int = 3,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    // Начинаем с 3 элементов чтобы нормально выглядело, как просил пользователь
    var visibleCount by remember(items.size) {
        mutableIntStateOf(initialVisible.coerceAtMost(items.size))
    }
    val rowState = rememberLazyListState()

    // Сбрасываем visibleCount если список полностью поменялся (например смена библиотеки)
    LaunchedEffect(items.size) {
        if (visibleCount > items.size) {
            visibleCount = initialVisible.coerceAtMost(items.size)
        }
        // Если список стал больше, а мы показывали все - показываем все
        // Если список маленький (<=3) - показываем все сразу
        if (items.size <= initialVisible) {
            visibleCount = items.size
        }
    }

    // Очередь подгрузки: когда пользователь доскроллил до предпоследнего видимого элемента,
    // подгружаем следующую пачку
    LaunchedEffect(rowState, items.size) {
        snapshotFlow {
            val layoutInfo = rowState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) 0 else visibleItems.last().index
        }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                // Если доскроллили до visibleCount - 2 (предпоследний) и есть еще данные
                if (lastVisibleIndex >= visibleCount - 2 && visibleCount < items.size) {
                    val nextCount = (visibleCount + pageSize).coerceAtMost(items.size)
                    PerformanceTracer.log(
                        "paginatedRow",
                        "Queue: loading next page $visibleCount -> $nextCount / ${items.size} (lastVisible=$lastVisibleIndex)"
                    )
                    visibleCount = nextCount
                }
            }
    }

    LazyRow(
        state = rowState,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement
    ) {
        items(
            count = visibleCount,
            key = { index -> key(items[index]) },
            contentType = { "paginated_item" }
        ) { index ->
            itemContent(items[index])
        }

        // Индикатор что есть еще данные - маленький шиммер в конце пока не все загружено
        // Это дает понять пользователю что можно скроллить дальше
        if (visibleCount < items.size) {
            item(key = "load_more_${visibleCount}", contentType = "load_more") {
                // Полупрозрачный шиммер как hint что есть еще
                ShimmerPlaceholder(
                    modifier = Modifier
                        .size(152.dp)
                )
            }
        }
    }
}

/**
 * Вертикальная пагинация для колонок (например "Чтобы вернуться" - 6 песен)
 * Показывает первые 3, остальные по кнопке "Показать еще" или при скролле главного списка
 */
@Composable
fun <T> PaginatedColumn(
    items: List<T>,
    initialVisible: Int = 3,
    pageSize: Int = 3,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
    showMoreContent: @Composable ((onShowMore: () -> Unit) -> Unit)? = null
) {
    var visibleCount by remember(items.size) {
        mutableIntStateOf(initialVisible.coerceAtMost(items.size))
    }

    LaunchedEffect(items.size) {
        if (items.size <= initialVisible) {
            visibleCount = items.size
        } else if (visibleCount > items.size) {
            visibleCount = initialVisible.coerceAtMost(items.size)
        }
    }

    val visibleItems = remember(items, visibleCount) {
        items.take(visibleCount)
    }

    visibleItems.forEach { item ->
        key(key(item)) {
            itemContent(item)
        }
    }

    // Кнопка "Показать еще" если есть еще элементы
    if (visibleCount < items.size) {
        if (showMoreContent != null) {
            showMoreContent {
                val nextCount = (visibleCount + pageSize).coerceAtMost(items.size)
                PerformanceTracer.log("paginatedColumn", "Queue: loading next page $visibleCount -> $nextCount / ${items.size}")
                visibleCount = nextCount
            }
        } else {
            // По умолчанию просто увеличиваем при клике на последний? Но лучше кнопка
            // Для простоты - ничего, пользователь может скроллить и мы подгрузим автоматически через LaunchedEffect в HomeScreen
        }
    }
}

/**
 * Центральная очередь загрузки секций интерфейса
 * Как в Spotify - секции грузятся по очереди, а не все сразу, чтобы не лагало
 *
 * Использование:
 * val queue = rememberSectionLoadQueue()
 * queue.enqueue("playlists") { loadPlaylists() }
 * queue.enqueue("newest") { loadNewest() }
 */
class SectionLoadQueue {
    private val queue = ArrayDeque<LoadTask>()
    private var isProcessing = false

    data class LoadTask(
        val id: String,
        val priority: Int = 0,
        val block: suspend () -> Unit
    )

    fun enqueue(id: String, priority: Int = 0, block: suspend () -> Unit) {
        // Не добавляем дубликаты
        if (queue.any { it.id == id }) return
        queue.add(LoadTask(id, priority, block))
        // Сортируем по приоритету (меньше = выше приоритет)
        queue.sortBy { it.priority }
    }

    suspend fun processAll() {
        if (isProcessing) return
        isProcessing = true
        try {
            while (queue.isNotEmpty()) {
                val task = queue.removeFirst()
                try {
                    PerformanceTracer.start("queue_${task.id}")
                    task.block()
                    PerformanceTracer.end("queue_${task.id}")
                    PerformanceTracer.log("loadQueue", "Processed ${task.id}, remaining=${queue.size}")
                    // Небольшая задержка между секциями чтобы дать UI отрисоваться - как в Spotify
                    kotlinx.coroutines.delay(80)
                } catch (e: Exception) {
                    PerformanceTracer.log("loadQueue", "Failed ${task.id}: ${e.message}")
                }
            }
        } finally {
            isProcessing = false
        }
    }

    fun clear() {
        queue.clear()
    }

    fun size(): Int = queue.size
}

@Composable
fun rememberSectionLoadQueue(): SectionLoadQueue {
    return remember { SectionLoadQueue() }
}
