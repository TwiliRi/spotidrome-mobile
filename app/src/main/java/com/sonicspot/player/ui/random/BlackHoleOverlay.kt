package com.sonicspot.player.ui.random

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.ui.theme.SpotifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

/**
 * Оверлей «Чёрная дыра»: пока идёт бросок случайного трека, он лежит поверх всего
 * приложения и рисует сцену [BlackHoleScene].
 *
 * Сцена обновляется в такт кадрам (`withFrameNanos`), поэтому кадр не зависит от частоты
 * экрана: на 120 Гц движение то же, просто плавнее. Рекомпозиции при этом нет — состояние
 * читается внутри слоя отрисовки, перерисовывается только Canvas.
 *
 * Тап в любом месте (и кнопка «назад») — пропустить анимацию: трек всё равно заиграет.
 */
@Composable
fun BlackHoleOverlay(
    state: RandomRollState,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scene = remember { BlackHoleScene() }
    val grain = remember { FilmGrain(makeGrainBitmap()) }

    BackHandler(enabled = true) { onSkip() }

    // Часы сцены: dt считаем сами, чтобы движение не зависело от частоты кадров
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                scene.update(dt)
            }
        }
    }

    LaunchedEffect(state.phase) {
        scene.setPhase(state.phase)
        when (state.phase) {
            RollPhase.SETTLE -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            RollPhase.TOP -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            else -> Unit
        }
    }

    // Обложки тронутых треков — реальные, из фонотеки: сцена берёт их по мере загрузки
    LaunchedEffect(state.poolUrls) {
        if (state.poolUrls.isEmpty()) return@LaunchedEffect
        state.poolUrls.take(ART_PRELOAD).chunked(3).forEach { chunk ->
            coroutineScope {
                chunk.map { url ->
                    async(Dispatchers.IO) { loadCover(context, url, ART_COVER_PX) }
                }.awaitAll().forEach { bitmap -> bitmap?.let(scene::addArtBitmap) }
            }
        }
    }

    // Обложка выпавшего трека + её доминирующий цвет (акцент вспышек, свечения, подписи)
    LaunchedEffect(state.coverUrl) {
        val url = state.coverUrl ?: return@LaunchedEffect
        val bitmap = loadCover(context, url, HERO_COVER_PX)
        val accent = bitmap?.let(::dominantColor) ?: SpotifyColors.Green
        scene.setHeroBitmap(bitmap, accent)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Растворение оверлея в EXPAND: плеер уже стоит под ним
                alpha = scene.layerAlpha
            }
            .pointerInput(Unit) { detectTapGestures { onSkip() } }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            with(scene) { drawFrame(grain, PlayerCoverBounds.rect, PlayerCoverBounds.cornerRadius) }
        }
        PhaseCaption(
            phase = state.phase,
            track = state.track,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 72.dp, start = 24.dp, end = 24.dp)
        )
    }
}

/** Подпись снизу: пока трек не выпал — тексты фаз, потом название и исполнитель. */
@Composable
private fun PhaseCaption(phase: RollPhase, track: Song?, modifier: Modifier = Modifier) {
    val named = track != null &&
        (phase == RollPhase.SETTLE || phase == RollPhase.TOP || phase == RollPhase.EXPAND)

    val pulse = rememberInfiniteTransition(label = "caption")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "captionAlpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (named && track != null) {
            Text(
                text = "ВЫПАЛ ТРЕК",
                color = SpotifyColors.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 2.6.sp)
            )
            Text(
                text = track.title,
                color = SpotifyColors.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(color = Color.White.copy(alpha = 0.35f), blurRadius = 34f)
                )
            )
            Text(
                text = track.artist ?: "Unknown",
                color = SpotifyColors.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
            )
        } else {
            Text(
                text = when (phase) {
                    RollPhase.IDLE -> "ГОРИЗОНТ СОБЫТИЙ…"
                    RollPhase.BALANCE -> "ИЩЕМ ЧТО-НИБУДЬ СТОЯЩЕЕ…"
                    RollPhase.SETTLE -> "ЗАТЯГИВАЕТ…"
                    else -> "ВЫБРАСЫВАЕТ ОБЛОЖКИ…"
                },
                color = SpotifyColors.White.copy(alpha = pulseAlpha),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 2.6.sp)
            )
        }
        Text(
            text = "нажми, чтобы пропустить",
            color = SpotifyColors.White.copy(alpha = 0.24f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

// ==================== загрузка обложек ====================

private const val ART_PRELOAD = 18
private const val ART_COVER_PX = 240
private const val HERO_COVER_PX = 600

private suspend fun loadCover(context: Context, url: String, sizePx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(sizePx)
                .allowHardware(false)   // рисуем в Canvas сами — hardware-bitmap тут не помощник
                .build()
            val result = context.imageLoader.execute(request)
            if (result !is SuccessResult) return@withContext null
            result.drawable.toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

/**
 * Доминирующий цвет обложки: средний по насыщенным пикселям, с подтянутой насыщенностью.
 * Нужен для вспышек, свечения и подсветки подписи — как useDominantColor в десктопе.
 */
private fun dominantColor(image: ImageBitmap): Color {
    val bitmap = image.asAndroidBitmap()
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return SpotifyColors.Green

    val step = max(1, minOf(w, h) / 24)
    var sumR = 0f
    var sumG = 0f
    var sumB = 0f
    var sumW = 0f
    var x = 0
    while (x < w) {
        var y = 0
        while (y < h) {
            val pixel = bitmap.getPixel(x, y)
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val sat = if (mx <= 0f) 0f else (mx - mn) / mx
            val weight = sat * sat
            sumR += r * weight
            sumG += g * weight
            sumB += b * weight
            sumW += weight
            y += step
        }
        x += step
    }
    if (sumW <= 0.0001f) return SpotifyColors.Green

    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (sumR / sumW * 255f).toInt().coerceIn(0, 255),
        (sumG / sumW * 255f).toInt().coerceIn(0, 255),
        (sumB / sumW * 255f).toInt().coerceIn(0, 255),
        hsv
    )
    // акцент должен читаться на чёрном: держим насыщенность и яркость в разумных рамках
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceIn(0.62f, 0.96f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/** Плитка плёночного зерна: одна на весь оверлей, дальше просто тайлится шейдером. */
private fun makeGrainBitmap(): Bitmap {
    val size = 96
    val pixels = IntArray(size * size)
    val rnd = Random(size * 7919)
    for (i in pixels.indices) {
        val v = rnd.nextInt(256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}
