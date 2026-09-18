package com.sonicspot.player.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Debug оверлей для HomeScreen - показывает живые метрики лагов.
 * Подключи в HomeScreen:
 * ```
 * Box {
 *   HomeContent()
 *   if (BuildConfig.DEBUG) LagDebugOverlay()
 * }
 * ```
 */
@Composable
fun LagDebugOverlay(
    modifier: Modifier = Modifier
) {
    var report by remember { mutableStateOf(PerformanceTracer.lastHomeReport) }
    var spans by remember { mutableStateOf(PerformanceTracer.getRecentSpans(20)) }
    var slowSpans by remember { mutableStateOf(PerformanceTracer.getSlowSpansOnMain()) }
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            report = PerformanceTracer.lastHomeReport
            spans = PerformanceTracer.getRecentSpans(20)
            slowSpans = PerformanceTracer.getSlowSpansOnMain()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "🐛 Lag Debugger",
                color = Color.Green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (slowSpans.isNotEmpty()) {
                    Text(
                        "🔴 ${slowSpans.size} SLOW!",
                        color = Color.Red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.height(20.dp)
                ) {
                    Text(if (isExpanded) "▲" else "▼", color = Color.White, fontSize = 10.sp)
                }
            }
        }

        report?.let { r ->
            Spacer(Modifier.height(4.dp))
            Text(
                "Critical: ${r.totalMs.toInt()}ms | cache=${if (r.cacheHit) "HIT" else "MISS"}",
                color = if (r.totalMs > 1000) Color.Red else Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            if (isExpanded) {
                Text(
                    " folders=${r.foldersMs.toInt()}ms recent=${r.recentMs.toInt()}ms\n newest=${r.newestMs.toInt()}ms playlists=${r.playlistsMs.toInt()}ms\n random=${r.randomMs.toInt()}ms artists=${r.artistsMs.toInt()}ms\n disliked=${r.dislikedSyncMs.toInt()}ms cacheSave=${r.cacheSaveMs.toInt()}ms",
                    color = Color.LightGray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (slowSpans.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "🔴 SLOW ON MAIN (>100ms):",
                color = Color.Red,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            slowSpans.take(5).forEach { span ->
                Text(
                    " ${span.tag}: ${span.durationMs.toInt()}ms",
                    color = Color.Yellow,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (isExpanded) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
            Text("Recent spans:", color = Color.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .heightIn(max = 150.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                spans.take(15).forEach { span ->
                    val color = when {
                        span.isMainThread && span.durationMs > 100 -> Color.Red
                        span.isMainThread && span.durationMs > 16 -> Color.Yellow
                        span.isMainThread -> Color.White
                        else -> Color.Gray
                    }
                    Text(
                        "${if (span.isMainThread) "M" else "BG"} ${span.tag} ${span.durationMs.toInt()}ms",
                        color = color,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { PerformanceTracer.clear() },
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Clear", fontSize = 8.sp)
                }
                Button(
                    onClick = {
                        android.util.Log.d("SonicLag", PerformanceTracer.dumpReport())
                    },
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Dump Logcat", fontSize = 8.sp)
                }
            }
        }
    }
}

/**
 * Простой FPS монитор для Compose
 */
@Composable
fun FpsMonitor(modifier: Modifier = Modifier) {
    var fps by remember { mutableStateOf(0) }
    var frameCount by remember { mutableStateOf(0) }
    var lastTime by remember { mutableStateOf(System.nanoTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16) // ~60fps
            frameCount++
            val now = System.nanoTime()
            val elapsed = (now - lastTime) / 1_000_000_000.0
            if (elapsed >= 1.0) {
                fps = (frameCount / elapsed).toInt()
                frameCount = 0
                lastTime = now
            }
        }
    }

    Box(
        modifier = modifier
            .background(
                when {
                    fps < 30 -> Color.Red.copy(alpha = 0.8f)
                    fps < 50 -> Color.Yellow.copy(alpha = 0.8f)
                    else -> Color.Green.copy(alpha = 0.6f)
                },
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "${fps} FPS",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Счетчик рекомпозиций - оберни любой Composable чтобы видеть сколько раз он рекомпозится
 */
@Composable
fun RecompositionCounter(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val count = remember { mutableStateOf(0) }
    SideEffect {
        count.value++
        HomeLagProfiler.ComposeDiagnostics.logRecomposition(name)
    }

    Box(modifier = modifier) {
        content()
        if (count.value > 5) {
            Box(
                modifier = Modifier
                    .background(Color.Red.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                    .padding(2.dp)
            ) {
                Text(
                    "${count.value} recomps",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
