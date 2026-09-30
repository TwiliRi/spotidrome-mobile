package com.sonicspot.player.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Тема «Космос»: включается переключателем в настройках и живёт во всём приложении.
 *
 * MainActivity подкладывает [StarfieldBackground] под все вкладки и раздаёт этот
 * локал вниз; экраны вместо своего обычного фона рисуют [appBackground] — в теме
 * они становятся прозрачными и звёздное небо видно на каждом экране.
 */
val LocalStarTheme = staticCompositionLocalOf { false }

/**
 * Фон экрана с учётом темы: в звёздной теме экран прозрачен (под ним общий
 * анимированный слой со звёздами), иначе — привычный чёрный или градиент.
 *
 * Использование: `Modifier.fillMaxSize().appBackground()` или
 * `Modifier.fillMaxSize().appBackground(backgroundBrush)`.
 */
@Composable
fun Modifier.appBackground(fallback: Brush? = null): Modifier =
    if (LocalStarTheme.current) this
    else if (fallback != null) background(fallback)
    else background(SpotifyColors.Black)

/**
 * Живой фон «глубокий космос»: медленно мерцающие звёзды трёх температур,
 * деликатные туманности и изредка — метеор с тающим хвостом.
 *
 * Рисуется в один Canvas на ~30 fps (батарея важнее лишней плавности),
 * не перехватывает касания и живёт, пока включена тема.
 */
@Composable
fun StarfieldBackground(modifier: Modifier = Modifier) {
    val scene = remember { StarfieldScene() }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now -> scene.tick(now) }
    }
    Canvas(modifier = modifier) { with(scene) { drawScene() } }
}

/**
 * Сцена неба: состояние живёт вне композиции, кадр обновляется из [tick],
 * а [revision] читается прямо в draw-блоке Canvas — перерисовывается только
 * слой отрисовки, без рекомпозиции (как revision в «Чёрной дыре»).
 */
private class StarfieldScene {

    /** Триггер перерисовки: меняется раз в кадр (~30 fps). */
    val revision = mutableIntStateOf(0)

    private class Star(
        val x: Float,          // 0..1 по ширине
        val y: Float,          // 0..1 по высоте
        val radius: Float,     // px при density 1
        val base: Float,       // базовая яркость
        val amp: Float,        // глубина мерцания
        val speed: Float,      // скорость мерцания
        val phase: Float,      // сдвиг фазы
        val color: Color,
        val glow: Boolean      // «геройская» звезда с ореолом
    )

    private class Meteor(
        var x: Float,          // 0..1, может выходить за границы
        var y: Float,
        val vx: Float,         // доли экрана в секунду
        val vy: Float,
        val ttl: Float,        // полное время жизни, с
        var life: Float,       // прожито
        val tail: Float        // длина хвоста, доли ширины
    )

    private val stars = buildList {
        repeat(104) {
            val roll = Random.nextFloat()
            val color = when {
                roll < 0.12f -> Color(0xFFF6E7C8)   // тёплые
                roll < 0.32f -> Color(0xFFCFE0FF)   // голубые
                else -> Color(0xFFE9EEF6)           // нейтральные
            }
            add(
                Star(
                    x = Random.nextFloat(),
                    y = Random.nextFloat(),
                    radius = 0.5f + Random.nextFloat() * 1.5f,
                    base = 0.22f + Random.nextFloat() * 0.42f,
                    amp = 0.12f + Random.nextFloat() * 0.34f,
                    speed = 0.25f + Random.nextFloat() * 1.1f,
                    phase = Random.nextFloat() * TAU,
                    color = color,
                    glow = false
                )
            )
        }
        // несколько «геройских» звёзд — крупнее и с мягким ореолом
        repeat(6) {
            add(
                Star(
                    x = Random.nextFloat(),
                    y = Random.nextFloat(),
                    radius = 1.8f + Random.nextFloat() * 1.2f,
                    base = 0.6f + Random.nextFloat() * 0.25f,
                    amp = 0.18f + Random.nextFloat() * 0.15f,
                    speed = 0.35f + Random.nextFloat() * 0.5f,
                    phase = Random.nextFloat() * TAU,
                    color = Color(0xFFF2F5FF),
                    glow = true
                )
            )
        }
    }

    /** Туманности: (x, y, относительный радиус) → цвет. Крайне деликатные. */
    private val nebulas = listOf(
        Triple(0.20f, 0.16f, 0.62f) to Color(0xFF3D4AC8),   // индиго
        Triple(0.80f, 0.30f, 0.55f) to Color(0xFF7A3DB8),   // фиолет
        Triple(0.50f, 0.88f, 0.75f) to Color(0xFF0E6E63)    // тизл
    )

    private val meteors = ArrayList<Meteor>(3)
    private var time = 0f
    private var nextMeteorIn = 3.5f
    private var lastNanos = 0L
    private var lag = 0L

    /** Часы сцены: копим наносекунды и шагаем ~30 раз в секунду. */
    fun tick(nanos: Long) {
        if (lastNanos == 0L) {
            lastNanos = nanos
            return
        }
        lag += nanos - lastNanos
        lastNanos = nanos
        val frame = 33_000_000L
        if (lag < frame) return
        val dt = lag / 1_000_000_000f
        lag = 0
        time += dt

        // изредка пролетает метеор; иногда — сразу пара
        nextMeteorIn -= dt
        if (nextMeteorIn <= 0f) {
            spawnMeteor()
            if (Random.nextFloat() < 0.22f) spawnMeteor()
            nextMeteorIn = 5f + Random.nextFloat() * 8f
        }

        val it = meteors.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.x += m.vx * dt
            m.y += m.vy * dt
            m.life += dt
            if (m.life >= m.ttl || m.y > 1.2f || m.x < -0.35f || m.x > 1.35f) it.remove()
        }

        revision.intValue++
    }

    private fun spawnMeteor() {
        val dir = if (Random.nextFloat() < 0.5f) 1f else -1f
        val angle = (16f + Random.nextFloat() * 18f) * PI.toFloat() / 180f
        val speed = 0.95f + Random.nextFloat() * 0.75f
        meteors.add(
            Meteor(
                x = if (dir > 0f) -0.12f + Random.nextFloat() * 0.6f else 0.4f + Random.nextFloat() * 0.7f,
                y = -0.08f + Random.nextFloat() * 0.38f,
                vx = cos(angle) * speed * dir,
                vy = sin(angle) * speed,
                ttl = 1.0f + Random.nextFloat() * 0.6f,
                life = 0f,
                tail = 0.13f + Random.nextFloat() * 0.09f
            )
        )
    }

    fun DrawScope.drawScene() {
        // чтение revision здесь подписывает draw-блок на перерисовку
        val frame = revision.intValue

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return

        // глубокий космос: сине-чёрный купол, чуть светлее к середине
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF06070F), Color(0xFF0A0C1C), Color(0xFF05060D)),
                startY = 0f, endY = h
            ),
            size = size
        )

        // туманности — очень тихие, дышат с периодом ~20 с
        val shortSide = min(w, h)
        for ((i, nebula) in nebulas.withIndex()) {
            val (nx, ny, nr) = nebula.first
            val c = nebula.second
            val breathe = 0.055f + 0.03f * sin(time * 0.31f + i * 2.1f)
            val radius = nr * shortSide
            val center = Offset(nx * w, ny * h)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.copy(alpha = breathe), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        // звёзды: мерцание — своя фаза и скорость у каждой
        for (star in stars) {
            val twinkle = (star.base + star.amp * sin(time * star.speed + star.phase))
                .coerceIn(0.03f, 0.95f)
            val px = star.x * w
            val py = star.y * h
            val r = star.radius * (shortSide / 420f).coerceIn(0.6f, 2.6f)
            if (star.glow) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            star.color.copy(alpha = twinkle * 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = r * 5f
                    ),
                    radius = r * 5f,
                    center = Offset(px, py)
                )
            }
            drawCircle(color = star.color.copy(alpha = twinkle), radius = r, center = Offset(px, py))
        }

        // метеоры: яркая голова и градиентный хвост, вспыхивают и тают
        for (m in meteors) {
            val p = (m.life / m.ttl).coerceIn(0f, 1f)
            val fade = sin(PI.toFloat() * p)               // появление и угасание
            if (fade <= 0.01f) continue
            val hx = m.x * w
            val hy = m.y * h
            val speed = kotlin.math.sqrt(m.vx * m.vx + m.vy * m.vy)
            val dx = m.vx / speed
            val dy = m.vy / speed
            val tx = hx - dx * m.tail * w
            val ty = hy - dy * m.tail * w

            // ореол головы
            val glowR = 14f * (shortSide / 420f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF4F7FF).copy(alpha = 0.5f * fade),
                        Color(0xFFBFD4FF).copy(alpha = 0.12f * fade),
                        Color.Transparent
                    ),
                    center = Offset(hx, hy),
                    radius = glowR
                ),
                radius = glowR,
                center = Offset(hx, hy)
            )
            // хвост
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color(0xFFD9E6FF).copy(alpha = 0.85f * fade)),
                    start = Offset(tx, ty),
                    end = Offset(hx, hy)
                ),
                start = Offset(tx, ty),
                end = Offset(hx, hy),
                strokeWidth = 2.2f * (shortSide / 420f).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }

        // мягкая виньетка по краям — глубина кадра
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color(0xFF000000).copy(alpha = 0.34f)
                ),
                center = Offset(w / 2f, h / 2f),
                radius = shortSide * 1.35f
            ),
            size = size
        )
    }

    private companion object {
        const val TAU = (PI * 2).toFloat()
    }
}
