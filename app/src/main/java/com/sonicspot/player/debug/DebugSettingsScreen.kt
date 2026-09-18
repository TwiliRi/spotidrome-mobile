package com.sonicspot.player.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicspot.player.ui.theme.SpotifyColors
import kotlinx.coroutines.launch

/**
 * Экран диагностики лагов - добавь в NavGraph как отдельный экран
 * или показывай как BottomSheet в SettingsScreen.
 *
 * Использование в SettingsScreen:
 * ```
 * var showLagDebug by remember { mutableStateOf(false) }
 * if (showLagDebug) LagDebugScreen(onBack = { showLagDebug = false })
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LagDebugScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(PerformanceTracer.lastHomeReport) }
    var spans by remember { mutableStateOf(PerformanceTracer.getRecentSpans(30)) }
    var slowSpans by remember { mutableStateOf(PerformanceTracer.getSlowSpansOnMain()) }
    var isRunning by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            report = PerformanceTracer.lastHomeReport
            spans = PerformanceTracer.getRecentSpans(30)
            slowSpans = PerformanceTracer.getSlowSpansOnMain()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🐛 Lag Debugger", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SpotifyColors.Black,
                    titleContentColor = SpotifyColors.White,
                    navigationIconContentColor = SpotifyColors.White
                )
            )
        },
        containerColor = SpotifyColors.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // FPS Monitor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live FPS:", color = SpotifyColors.White, fontWeight = FontWeight.Bold)
                FpsMonitor()
            }

            // Quick actions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SpotifyColors.Gray)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Быстрые тесты", color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isRunning = true
                                    lastResult = "Running quick check..."
                                    LagDiagnosticRunner.quickCheck()
                                    lastResult = PerformanceTracer.dumpReport()
                                    isRunning = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isRunning
                        ) {
                            Text("Quick Check", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isRunning = true
                                    lastResult = "Running full diagnostics..."
                                    LagDiagnosticRunner.runAll(context)
                                    LagDiagnosticRunner.testAppStartupScenario()
                                    lastResult = PerformanceTracer.dumpReport()
                                    isRunning = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isRunning
                        ) {
                            Text("Full Test", fontSize = 12.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                PerformanceTracer.clear()
                                lastResult = "Cleared"
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.GrayLighter)
                        ) {
                            Text("Clear Logs", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                lastResult = PerformanceTracer.dumpReport()
                                android.util.Log.d("SonicLag", lastResult)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyColors.Green)
                        ) {
                            Text("Dump to Logcat", fontSize = 12.sp)
                        }
                    }

                    if (isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SpotifyColors.Green)
                    }
                }
            }

            // Home Load Report
            report?.let { r ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.totalMs > 1000) Color(0xFF4A1A1A) else SpotifyColors.Gray
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Home Load Report", color = SpotifyColors.White, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (r.cacheHit) SpotifyColors.Green else SpotifyColors.Red)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (r.cacheHit) "CACHE HIT" else "CACHE MISS",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))

                        Text(
                            "Critical path (блокируют UI):",
                            color = SpotifyColors.Green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        MetricRow("folders", r.foldersMs, threshold = 500)
                        MetricRow("recent(12)", r.recentMs, threshold = 500)
                        MetricRow("newest(12)", r.newestMs, threshold = 500)
                        MetricRow("playlists", r.playlistsMs, threshold = 500)
                        MetricRow("cacheSave", r.cacheSaveMs, threshold = 200)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (r.totalMs > 2000) Color.Red.copy(alpha = 0.3f)
                                    else if (r.totalMs > 1000) Color.Yellow.copy(alpha = 0.2f)
                                    else Color.Green.copy(alpha = 0.2f)
                                )
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOTAL critical", color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "${r.totalMs.toInt()}ms",
                                color = when {
                                    r.totalMs > 2000 -> Color.Red
                                    r.totalMs > 1000 -> Color.Yellow
                                    else -> Color.Green
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Background (не блокируют):",
                            color = SpotifyColors.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        MetricRow("random(20)", r.randomMs, threshold = 2000)
                        MetricRow("artists", r.artistsMs, threshold = 2000)
                        MetricRow("dislikedSync", r.dislikedSyncMs, threshold = 1000, isCritical = true)
                        MetricRow("starredSync", r.starredSyncMs, threshold = 1000)

                        Spacer(Modifier.height(8.dp))
                        Text(
                            r.toLog().substringAfter("DIAGNOSIS:").substringBefore("====="),
                            color = Color.Yellow,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Slow spans on main
            if (slowSpans.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1A1A))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "🔴 SLOW ON MAIN THREAD (>100ms) - ПРИЧИНЫ ЛАГОВ!",
                            color = Color.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        slowSpans.take(10).forEach { span ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    span.tag,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${span.durationMs.toInt()}ms",
                                    color = Color.Red,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (span.extra.isNotBlank()) {
                                Text(
                                    "  ${span.extra}",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Recent spans
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SpotifyColors.Gray)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Recent spans (last 30)", color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    HorizontalDivider(color = SpotifyColors.GrayLighter.copy(alpha = 0.3f))

                    spans.take(20).forEach { span ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(
                                            when {
                                                span.isMainThread && span.durationMs > 100 -> Color.Red
                                                span.isMainThread && span.durationMs > 16 -> Color.Yellow
                                                span.isMainThread -> Color.White
                                                else -> Color.Gray
                                            }
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    span.tag,
                                    color = when {
                                        span.isMainThread && span.durationMs > 100 -> Color.Red
                                        span.isMainThread && span.durationMs > 16 -> Color.Yellow
                                        span.isMainThread -> Color.White
                                        else -> Color.Gray
                                    },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                            Text(
                                "${span.durationMs.toInt()}ms",
                                color = when {
                                    span.durationMs > 1000 -> Color.Red
                                    span.durationMs > 100 -> Color.Yellow
                                    else -> Color.LightGray
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Last result dump
            if (lastResult.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Last result:", color = SpotifyColors.Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            lastResult,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                                .padding(8.dp)
                        )
                    }
                }
            }

            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SpotifyColors.GrayLighter.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Как пользоваться:", color = SpotifyColors.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        """
                        1. Запусти Quick Check - покажет быстрые метрики
                        2. Запусти Full Test - полная диагностика
                        3. Смотри Home Load Report - главный отчет
                        4. Если TOTAL critical >1000ms - лагает из-за сети/кэша
                        5. Если есть SLOW ON MAIN - это 100% причина лагов!
                        6. Смотри Logcat с фильтром "SonicLag" для деталей
                        7. Dump to Logcat - сохраняет весь отчет в логи
                        
                        Главные причины лагов:
                        🔴 PlayerManager prepare() с 100 треками
                        🔴 DislikedRepository sync с 100 DataStore writes
                        🔴 CoverArtImage с ImageRequest builder
                        🔴 LazyColumn без keys
                        🔴 Brush без remember
                        🔴 IO на main thread
                        """.trimIndent(),
                        color = SpotifyColors.LightGray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
private fun MetricRow(name: String, value: Double, threshold: Long, isCritical: Boolean = false) {
    val color = when {
        value > threshold * 2 -> Color.Red
        value > threshold -> Color.Yellow
        else -> Color.White
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value > threshold) {
                Text(if (value > threshold * 2) "🔴" else "🟡", fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(name, color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            "${value.toInt()}ms",
            color = color,
            fontSize = 11.sp,
            fontWeight = if (value > threshold || isCritical) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
    }
}
