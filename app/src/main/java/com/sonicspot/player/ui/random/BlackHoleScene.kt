package com.sonicspot.player.ui.random

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Сцена «Чёрная дыра» для кнопки случайного трека — порт сцены из десктопного Spotidrome
 * (src/lib/blackHoleScene.js) на Compose. Десктоп считает диск честным шейдером с геодезикой
 * фотона; здесь — рисованный аналог того же языка: аккреционный диск с нитями, фотонное
 * кольцо, тень горизонта, разлетающиеся и падающие обложки, вспышки у горизонта.
 *
 * Что перенесено один в один:
 *  - модель потока: один параметр `flow` от +1 (выброс) до −1 (всасывание). Поэтому обложки
 *    не «переключаются» между фазами, а именно разворачиваются — гасят скорость и летят назад;
 *  - Кеплер: внутренние обложки несутся быстрее внешних (ω ∝ r^−1.5), радиальная скорость
 *    падает к краю на выбросе и растёт к горизонту при всасывании;
 *  - прозрачность обложки = рождение у горизонта × растворение у края × «выныривание»;
 *  - раскалённый внутренний край диска, доплеровское усиление одной стороны, наклон и крен
 *    камеры, дыхание диска, вспышки на каждом выбросе и поглощении;
 *  - обложка-герой поднимается из горизонта, разворачиваясь из «ребра» лицом к камере.
 *
 * Сцена ничего не знает про корутины и фазы: фазу ей сообщают, время она получает дельтой.
 */
class BlackHoleScene {

    private enum class ArtState { IDLE, WAIT, LIVE, GONE }

    /** Обложка, летающая по спирали вокруг дыры. */
    private class Art(seed: Int) {
        private val rnd = Random(seed * 6151 + 29)
        val y0 = (rnd.nextFloat() - 0.5f) * 0.7f      // смещение внутри толщины диска
        val size = 0.7f + rnd.nextFloat() * 0.4f
        val spin = (rnd.nextFloat() - 0.5f) * 0.9f    // собственное вращение обложки
        val k = 0.8f + rnd.nextFloat() * 0.5f         // разброс скоростей: облако, а не строй

        var state = ArtState.IDLE
        var wait = 0f
        var angle = rnd.nextFloat() * TAU
        var radius = R_SPAWN
        var face = rnd.nextFloat() * TAU
        var alpha = 0f
        var stretch = 1f
        var heat = 0f
        var bitmap: ImageBitmap? = null

        fun respawn(delay: Float) {
            state = ArtState.WAIT
            wait = delay
        }
    }

    private class Flash {
        var life = 0f
        var x = 0f
        var y = 0f
        var size = 1f
        var accent = false
        val ttl = 0.42f
    }

    /** Триггер перерисовки: читается в draw-блоке Canvas, меняется раз в кадр. */
    internal val revision = mutableIntStateOf(0)

    private val arts = List(ART_COUNT) { Art(it) }
    private val flashes = List(FLASH_COUNT) { Flash() }

    private var artCursor = 0

    // ---------- состояние сцены ----------
    var width = 0f; private set
    var height = 0f; private set
    var phase: RollPhase = RollPhase.IDLE; private set
    var time = 0f; private set
    var phaseTime = 0f; private set

    /** Поток: +1 — дыра выплёвывает, −1 — засасывает. */
    var flow = 0.1f; private set
    /** Насколько дыра «нагрета» всасыванием, 0..1. */
    var feed = 0f; private set

    private var balanceTime = 0f
    private var dist = 9.6f
    private var fov = 58f
    private var orbit = 0.05f
    private var roll = 0f
    private var squash = 0.38f
    private var finalSuction = false

    /** Прозрачность всего оверлея: 1 — виден, 0 — растворился в плеере (фаза EXPAND). */
    var layerAlpha = 1f
        private set

    private var heroTime = 0f
    private var heroBitmap: ImageBitmap? = null
    private var heroGrow = 0f
    private var heroFlipY = 0f
    private var heroFlipX = 0f

    private var accent = Color(0xFF1DB954)

    // Качество подстраивается по факту: слабая машина получит диск чуть проще, без просадок
    private var quality = 1f
    private var frameAccum = 0f
    private var frameCount = 0

    // ---------- геометрия экрана ----------
    private val center: Offset get() = Offset(width / 2f, height * 0.46f)

    /** Радиус тени горизонта в пикселях: HORIZON / (2 · dist · tg(fov/2)) в долях высоты кадра. */
    private val horizonPx: Float get() = height / (2f * dist * tan(fov * PI.toFloat() / 360f))

    private fun setSize(w: Float, h: Float) {
        width = w
        height = h
    }

    fun setPhase(newPhase: RollPhase) {
        if (newPhase == phase) return
        phase = newPhase
        phaseTime = 0f
        when (newPhase) {
            RollPhase.SPIN -> {
                // Выброс начинается не залпом, а потоком: обложки стартуют по очереди
                arts.forEachIndexed { i, art -> art.respawn(i * 0.05f + Random.nextFloat() * 0.12f) }
            }
            RollPhase.BALANCE -> {
                if (arts.none { it.state != ArtState.IDLE }) {
                    arts.forEachIndexed { i, art -> art.respawn(i * 0.05f + Random.nextFloat() * 0.12f) }
                }
                balanceTime = 0f
            }
            RollPhase.SETTLE -> finalSuction = true
            RollPhase.TOP -> heroTime = 0f
            else -> Unit
        }
    }

    /** Обложки тронутых треков — из них сцена берёт то, что летает вокруг дыры. */
    fun addArtBitmap(bitmap: ImageBitmap) {
        val free = arts.firstOrNull { it.bitmap == null }
        if (free != null) {
            free.bitmap = bitmap
            return
        }
        // все заняты — подменяем по кругу, как artPool в десктопе
        arts[artCursor % arts.size].bitmap = bitmap
        artCursor++
    }

    fun setHeroBitmap(bitmap: ImageBitmap?, accentColor: Color) {
        heroBitmap = bitmap
        accent = accentColor
    }

    /**
     * Обновление на кадр. dt — секунды (в десктопе он тоже обрезан сверху,
     * чтобы после просадки сцена не «прыгала»).
     */
    fun update(dtSeconds: Float) {
        if (width <= 0f || height <= 0f) return
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        time += dt
        phaseTime += dt

        adaptQuality(dt)

        /* Куда дует поток и где стоит камера — таблица из десктопной сцены */
        var flowTarget = 0.1f
        var distTarget = 9.6f
        var fovTarget = 58f
        var orbitSpeed = 0.05f
        when (phase) {
            RollPhase.IDLE -> Unit
            RollPhase.SPIN -> { flowTarget = 1f; distTarget = 11.4f; fovTarget = 70f; orbitSpeed = 0.14f }
            RollPhase.BALANCE -> {
                // Пока трек ищется, дыра дышит: поток ходит от выброса к всасыванию и обратно.
                // Косинус начинается с максимума, поэтому перехода из разгона не видно.
                balanceTime += dt
                flowTarget = 0.78f * cos(balanceTime * 1.35f)
                distTarget = 10.5f; fovTarget = 64f; orbitSpeed = 0.08f
            }
            RollPhase.SETTLE -> { flowTarget = -1f; distTarget = 8.6f; fovTarget = 56f; orbitSpeed = 0.2f }
            RollPhase.TOP, RollPhase.EXPAND -> { flowTarget = -0.4f; distTarget = 7.4f; fovTarget = 52f; orbitSpeed = 0.06f }
        }

        // В settle разворот идёт резче — это и есть перелом момента
        val flowSpeed = if (phase == RollPhase.SETTLE) 3.4f else 2.2f
        flow = approach(flow, flowTarget, flowSpeed, dt)
        feed = (-flow).coerceIn(0f, 1f)

        dist = approach(dist, distTarget, 2f, dt)
        fov = approach(fov, fovTarget, 2.2f, dt)
        orbit += orbitSpeed * dt

        // Наклон к плоскости диска дышит: то диск видно почти с ребра, то он чуть раскрывается.
        // Плюс у самого горизонта всё «прижимается» — отсюда прибавка от feed.
        squash = 0.34f + 0.105f * (0.5f + 0.5f * sin(time * 0.21f)) + feed * 0.05f
        // Крен камеры: композиция не стоит на месте
        roll = sin(time * 0.43f) * 0.034f + sin(time * 0.17f) * 0.022f

        updateArts(dt)
        updateFlashes(dt)

        if (phase == RollPhase.TOP || phase == RollPhase.EXPAND) {
            heroTime += dt
            heroGrow = easeOutCubic((heroTime / 0.5f).coerceIn(0f, 1f))
            // обложка выходит из горизонта «ребром» и разворачивается лицом к камере
            heroFlipY = 1f - easeOutBack((heroTime / 0.62f).coerceIn(0f, 1f))
            heroFlipX = 1f - easeOutCubic((heroTime / 0.7f).coerceIn(0f, 1f))
        }

        // Растворение оверлея: обложка успевает долететь до плеера, потом гаснет вся сцена
        layerAlpha = if (phase == RollPhase.EXPAND) {
            val expand = RollTimings.EXPAND / 1000f
            1f - easeOutCubic(((phaseTime - 0.06f) / (expand - 0.06f)).coerceIn(0f, 1f))
        } else {
            1f
        }

        revision.intValue++
    }

    /**
     * Реальная длительность кадра: решение принимаем по 12 кадрам, чтобы не дёргаться.
     * Режем только число нитей диска и звёзд — картинка остаётся той же.
     */
    private fun adaptQuality(dt: Float) {
        frameAccum += dt
        frameCount++
        if (frameCount < 12) return
        val avg = frameAccum / frameCount
        frameAccum = 0f
        frameCount = 0
        quality = when {
            avg > 0.04f -> (quality - 0.18f).coerceAtLeast(0.42f)
            avg > 0.026f -> (quality - 0.08f).coerceAtLeast(0.42f)
            avg < 0.017f -> (quality + 0.06f).coerceAtMost(1f)
            else -> quality
        }
    }

    // ==================== физика обложек ====================

    private fun updateArts(dt: Float) {
        val spinBoost = 1f + feed * 0.8f
        for (art in arts) {
            when (art.state) {
                ArtState.IDLE, ArtState.GONE -> art.alpha = 0f
                ArtState.WAIT -> {
                    art.alpha = 0f
                    art.wait -= dt
                    if (art.wait <= 0f) spawn(art)
                }
                ArtState.LIVE -> {
                    // угловая скорость по Кеплеру: внутренние обложки несутся быстрее внешних
                    art.angle += (1.35f / maxOf(art.radius, 0.75f).pow(1.5f)) * dt * spinBoost

                    /* Радиальная скорость. Выброс гасится к краю (стартует резко, потом угасает),
                       всасывание наоборот разгоняется к горизонту. Знак потока один и тот же,
                       поэтому при смене фазы обложки просто разворачиваются. */
                    val vr = if (flow >= 0f) {
                        flow * VR_OUT * (0.35f + 1.5f / maxOf(art.radius, 0.9f)) * art.k
                    } else {
                        flow * VR_IN * (0.55f + 0.42f * art.radius) * art.k
                    }
                    art.radius += vr * dt
                    art.face += art.spin * dt

                    if (art.radius <= HORIZON * 0.92f) {
                        if (finalSuction && flow < 0f) {
                            // проглочена навсегда — вспышка цвета акцента у самого горизонта
                            art.state = ArtState.GONE
                            art.alpha = 0f
                            popFlash(cos(art.angle) * HORIZON, sin(art.angle) * HORIZON, 1.25f, true)
                        } else {
                            // пока трек ищется, дыра её «переваривает» и выплёвывает заново
                            art.respawn(0.05f + Random.nextFloat() * 0.3f)
                        }
                        continue
                    }
                    if (art.radius >= R_MAX) {
                        art.respawn(0.05f + Random.nextFloat() * 0.35f)
                        continue
                    }

                    // проявляется из-за горизонта и гаснет, растворяясь у внешнего края
                    val born = ((art.radius - HORIZON) / 0.55f).coerceIn(0f, 1f)
                    val edge = 1f - ((art.radius - (R_MAX - FADE_EDGE)) / FADE_EDGE).coerceIn(0f, 1f)
                    val dive = ((art.radius - HORIZON * 0.9f) / 0.5f).coerceIn(0f, 1f)
                    art.heat = ((3.4f - art.radius) / 2.4f).coerceIn(0f, 1f)

                    art.alpha = born * edge * dive * (0.78f + feed * 0.22f) * (1f + art.heat * 0.15f)
                    art.stretch = 1f + feed * 1.6f * (((HORIZON * 4.4f - art.radius) / (HORIZON * 3.6f)).coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun spawn(art: Art) {
        art.state = ArtState.LIVE
        art.radius = R_SPAWN
        art.angle = (art.angle + 2.2f + Random.nextFloat() * 2.2f) % TAU
        art.stretch = 1f
        // новая обложка рождается у самого горизонта — со вспышкой
        popFlash(cos(art.angle) * R_SPAWN, sin(art.angle) * R_SPAWN, 0.75f, false)
    }

    private fun popFlash(x: Float, y: Float, size: Float, accentTinted: Boolean) {
        val flash = flashes.firstOrNull { it.life <= 0f } ?: flashes[0]
        flash.life = flash.ttl
        flash.x = x
        flash.y = y
        flash.size = size
        flash.accent = accentTinted
    }

    private fun updateFlashes(dt: Float) {
        for (flash in flashes) if (flash.life > 0f) flash.life -= dt
    }

    // ==================== отрисовка ====================

    /** Прямоугольник обложки-героя на экране — отсюда её подхватывает перелёт в плеер. */
    fun heroRect(): Rect? {
        heroBitmap ?: return null
        if (heroGrow <= 0.01f) return null
        val size = heroSizePx() * heroGrow
        val c = center
        return Rect(c.x - size / 2f, c.y - size / 2f, c.x + size / 2f, c.y + size / 2f)
    }

    private fun heroSizePx(): Float {
        // Как в десктопе: min(HERO, visibleH * aspect * 0.62) — обложка не шире кадра
        val visibleH = 2f * dist * tan(fov * PI.toFloat() / 360f)
        val aspect = width / height
        val worldSize = min(HERO_WORLD, visibleH * aspect * 0.62f)
        val side = worldSize * horizonPx
        val maxSide = min(width, height) * 0.78f
        return if (side > maxSide) maxSide else side
    }

    /**
     * Полный кадр оверлея: подложка → сцена → проявление → виньетка → вспышка → зерно → герой.
     * Всё рисуется одним вызовом, поэтому кадр обходится без рекомпозиции: обновляется
     * только слой отрисовки.
     */
    fun DrawScope.drawFrame(grain: FilmGrain?, heroTarget: Rect?, heroTargetRadius: Float) {
        // Подписка на кадры: draw-блок перерисовывается, только если он наблюдал
        // изменение состояния — читаем счётчик кадров, иначе картинка замрёт.
        val frame = revision.intValue
        if (frame < 0) return
        if (width <= 0f || height <= 0f) setSize(size.width, size.height)
        val S = horizonPx
        val sceneFade = easeOutCubic((time / 0.8f).coerceIn(0f, 1f))
        val introScale = 1f + 0.12f * (1f - sceneFade)   // сцена «притягивается» к кадру
        val c = center

        drawBackdrop(easeOutCubic((time / 0.38f).coerceIn(0f, 1f)))
        withTransform({ scale(introScale, introScale, pivot = c) }) {
            drawScenery(S)
        }
        // сцена проявляется из черноты: подложка почти чёрная, гасим остаток
        if (sceneFade < 0.999f) {
            drawRect(Color.Black.copy(alpha = (1f - sceneFade) * 0.96f))
        }
        drawVignette()
        drawFlash()
        drawGrain(grain)
        drawHero(heroTarget, heroTargetRadius)
    }

    /** Тёмная подложка вместо размытия приложения: размывать чужой кадр на телефоне дорого. */
    private fun DrawScope.drawBackdrop(alpha: Float) {
        if (alpha <= 0.001f) return
        val c = center
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF0A0A0F).copy(alpha = 0.94f * alpha),
                    0.62f to Color.Black.copy(alpha = 0.99f * alpha),
                    1f to Color.Black.copy(alpha = alpha)
                ),
                center = c,
                radius = maxOf(width, height) * 0.75f
            )
        )
    }

    /** Затемнение к краям; на всасывании стягивается внутрь вместе с обложками. */
    private fun DrawScope.drawVignette() {
        val settling = phase == RollPhase.SETTLE
        val p = if (settling) (phaseTime / 1.15f).coerceIn(0f, 1f) else 1f
        val scaleV = if (settling) 1.22f - 0.22f * p else 1f
        val alpha = if (settling) 0.65f + 0.35f * p else 0.8f
        val c = center
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.3f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f * alpha)
                ),
                center = c,
                radius = min(width, height) * 0.72f * scaleV
            )
        )
    }

    /** Вспышка: короткая — на развороте потока, яркая — когда трек выходит из дыры. */
    private fun DrawScope.drawFlash() {
        val (alpha, scaleF) = when (phase) {
            RollPhase.SETTLE -> {
                val t = (phaseTime / 0.7f).coerceIn(0f, 1f)
                val a = if (t < 0.22f) (t / 0.22f) * 0.16f else (1f - (t - 0.22f) / 0.78f) * 0.16f
                a to (1.1f - 0.18f * t)
            }
            RollPhase.TOP -> {
                val t = (phaseTime / 0.78f).coerceIn(0f, 1f)
                val a = if (t < 0.16f) (t / 0.16f) * 0.46f else (1f - (t - 0.16f) / 0.84f) * 0.46f
                a to (0.8f + 0.34f * t)
            }
            else -> 0f to 1f
        }
        if (alpha <= 0.002f) return
        val c = center
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    lerpColor(accent, Color.White, 0.65f).copy(alpha = alpha),
                    accent.copy(alpha = alpha * 0.4f),
                    Color.Transparent
                ),
                center = c,
                radius = min(width, height) * 0.5f * scaleF
            ),
            radius = min(width, height) * 0.5f * scaleF,
            center = c,
            blendMode = BlendMode.Plus
        )
    }

    /**
     * Плёночное зерно: плитка шума тайлится BitmapShader'ом и дрожит каждые три кадра.
     * Рисуем нативным холстом — в Compose публичного шейдерного браша для своей картинки нет.
     */
    private fun DrawScope.drawGrain(grain: FilmGrain?) {
        if (grain == null || quality < 0.7f) return
        val step = (time * 6f).toInt() / 3
        val rnd = Random(step)
        grain.offset((rnd.nextFloat() - 0.5f) * 24f, (rnd.nextFloat() - 0.5f) * 24f)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, width, height, grain.frameworkPaint)
        }
    }

    private fun DrawScope.drawScenery(S: Float) {
        val accentColor = accent
        val c = center
        val intensity = 0.62f * (1f + feed * 0.5f + heroGrow * 0.35f)

        // ---------- фон: пыль, звёзды ----------
        drawNebula()
        rotate(degrees = roll * 180f / PI.toFloat(), pivot = c) {
            drawStars(S)
            // ---------- обложки за дырой ----------
            drawArts(S, back = true)
            // ---------- диск, горизонт, линзирование ----------
            drawDisk(S, accentColor, intensity)
            drawShadow(S)
            drawLensedArcs(S, accentColor, intensity)
            drawPhotonRing(S, accentColor)
            // ---------- обложки перед дырой ----------
            drawArts(S, back = false)
        }
        drawFlashes(S)
    }

    private fun DrawScope.drawNebula() {
        // Очень скупые пятна пыли: фон должен читаться как космос, но не перебивать диск
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF16203A).copy(alpha = 0.42f), Color.Transparent),
                center = Offset(width * 0.22f, height * 0.18f),
                radius = min(width, height) * 0.85f
            ),
            radius = min(width, height) * 0.85f,
            center = Offset(width * 0.22f, height * 0.18f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF2A1A38).copy(alpha = 0.36f), Color.Transparent),
                center = Offset(width * 0.82f, height * 0.78f),
                radius = min(width, height) * 0.7f
            ),
            radius = min(width, height) * 0.7f,
            center = Offset(width * 0.82f, height * 0.78f)
        )
    }

    private fun DrawScope.drawStars(S: Float) {
        // Звёзды: 140 фиксированных точек с мерцанием. Дёшево и даёт глубину.
        var seed = 991
        val count = (STAR_COUNT * quality).toInt()
        for (i in 0 until count) {
            seed = seed * 1103515245 + 12345
            val rx = ((seed ushr 8) and 0xFFFF) / 65535f
            seed = seed * 1103515245 + 12345
            val ry = ((seed ushr 8) and 0xFFFF) / 65535f
            seed = seed * 1103515245 + 12345
            val rB = ((seed ushr 8) and 0xFF) / 255f

            val px = rx * width
            val py = ry * height
            // звёзды не должны пробиваться сквозь диск — гасим те, что попали в него
            val d = (Offset(px, py) - center).getDistance() / S
            if (d in DISK_IN..DISK_OUT) continue

            val twinkle = 0.55f + 0.45f * sin(time * (0.4f + rB * 0.8f) + i * 1.7f)
            val radius = (0.6f + rB * 1.5f) * (S / 220f).coerceIn(0.5f, 2.2f)
            drawCircle(
                color = Color(0.78f, 0.85f, 1f).copy(alpha = 0.28f * twinkle),
                radius = radius,
                center = Offset(px, py)
            )
        }
    }

    private fun DrawScope.drawDisk(S: Float, accentColor: Color, intensity: Float) {
        val c = center
        val rIn = DISK_IN * S
        val rOut = DISK_OUT * S

        withTransform({
            translate(c.x, c.y)
            scale(1f, squash, pivot = Offset.Zero)
        }) {
            // «полотно» диска: раскалённый внутренний край → акцент → растворение у края
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        (DISK_IN / DISK_OUT) to Color(1f, 0.82f, 0.62f).copy(alpha = 0.34f * intensity),
                        ((DISK_IN + (DISK_OUT - DISK_IN) * 0.35f) / DISK_OUT) to Color(1f, 0.62f, 0.32f).copy(alpha = 0.2f * intensity),
                        ((DISK_IN + (DISK_OUT - DISK_IN) * 0.7f) / DISK_OUT) to accentColor.copy(alpha = 0.1f * intensity),
                        1f to Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = rOut
                ),
                radius = rOut,
                center = Offset.Zero
            )

            // Нити: каждое кольцо — короткая дуга со своей яркостью и цветом.
            // Внутренние крутятся быстрее (Кеплер), у края гаснут.
            val filaments = (FILAMENTS * quality).toInt()
            val beamAngle = 2.2f
            for (i in 0 until filaments) {
                val f = (i + 0.37f) / filaments
                val r = rIn + (rOut - rIn) * sqrt(f)
                val rn = r / rOut
                val omega = (2.6f + 2.8f * feed) / (rn * 3f + 0.6f)
                val ang = i * 2.39996f + time * omega
                val n = noise3(ang * 1.6f, rn * 4.5f, time * 0.35f)
                val radial = hotProfile(rn)
                // доплеровское усиление: сторона, летящая на камеру, светится ярче
                val beam = 0.55f + 0.62f * cos(ang - beamAngle)
                val a = (radial * (0.22f + 0.78f * n) * beam * intensity).coerceIn(0f, 1f)
                if (a < 0.015f) continue

                drawArc(
                    color = diskColor(rn, accentColor, n),
                    startAngle = radiansToDegrees(ang),
                    sweepAngle = 9f + 14f * n,
                    useCenter = false,
                    topLeft = Offset(-r, -r),
                    size = Size(r * 2f, r * 2f),
                    alpha = a,
                    style = Stroke(width = (1.2f + 9f * (1f - rn) * (0.5f + 0.5f * n)).coerceAtLeast(0.8f), cap = StrokeCap.Round),
                    blendMode = BlendMode.Plus
                )
            }

            // Внутренний раскалённый обод у ISCO — самая яркая часть диска
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.62f to Color.Transparent,
                        0.78f to Color(1f, 0.93f, 0.82f).copy(alpha = 0.5f * intensity),
                        0.92f to Color(1f, 0.72f, 0.42f).copy(alpha = 0.22f * intensity),
                        1f to Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = rIn * 1.7f
                ),
                radius = rIn * 1.7f,
                center = Offset.Zero,
                blendMode = BlendMode.Plus
            )
        }
    }

    private fun DrawScope.drawShadow(S: Float) {
        // Тень горизонта: чёрный круг с мягкой границей — всё, что за дырой, честно пропадает,
        // потому что обложки задней половины рисуются до этого круга
        val c = center
        drawCircle(Color.Black, radius = HORIZON * S, center = c)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
                center = c,
                radius = HORIZON * S * 1.16f
            ),
            radius = HORIZON * S * 1.16f,
            center = c
        )
    }

    private fun DrawScope.drawLensedArcs(S: Float, accentColor: Color, intensity: Float) {
        // Линзирование: дальняя сторона диска поднимается гравитацией над дырой («шапка»),
        // ближняя — уходит под неё. Настоящая геодезика тут не считается, но силуэт читается.
        val c = center
        val boost = 1f + feed * 0.55f
        withTransform({
            translate(c.x, c.y)
            scale(1f, squash * 0.86f, pivot = Offset.Zero)
        }) {
            drawArc(
                color = Color(1f, 0.93f, 0.84f).copy(alpha = 0.34f * intensity * boost),
                startAngle = 197f, sweepAngle = 146f, useCenter = false,
                topLeft = Offset(-S * 1.22f, -S * 1.22f), size = Size(S * 2.44f, S * 2.44f),
                style = Stroke(width = (3.2f * S / 220f).coerceIn(1.4f, 6f), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
            drawArc(
                color = accentColor.copy(alpha = 0.22f * intensity * boost),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(-S * 1.62f, -S * 1.62f), size = Size(S * 3.24f, S * 3.24f),
                style = Stroke(width = (7f * S / 220f).coerceIn(2f, 12f), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
            // нижний «отражённый» образ диска — почти незаметная полоса под горизонтом
            drawArc(
                color = Color(1f, 0.8f, 0.6f).copy(alpha = 0.14f * intensity),
                startAngle = 17f, sweepAngle = 146f, useCenter = false,
                topLeft = Offset(-S * 1.1f, -S * 1.1f), size = Size(S * 2.2f, S * 2.2f),
                style = Stroke(width = (2.4f * S / 220f).coerceIn(1f, 5f), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
        }
    }

    private fun DrawScope.drawPhotonRing(S: Float, accentColor: Color) {
        // Кольцо Эйнштейна: тонкое и очень яркое у самой тени + мягкое гало вокруг
        val c = center
        val boost = 1f + feed * 0.45f
        val unit = (S / 220f).coerceIn(0.4f, 3f)
        drawCircle(
            color = Color(1f, 0.97f, 0.92f).copy(alpha = 0.9f * boost),
            radius = S * 1.03f,
            center = c,
            style = Stroke(width = 1.6f * unit),
            blendMode = BlendMode.Plus
        )
        drawCircle(
            color = Color(1f, 0.76f, 0.5f).copy(alpha = 0.32f * boost),
            radius = S * 1.12f,
            center = c,
            style = Stroke(width = 4.5f * unit),
            blendMode = BlendMode.Plus
        )
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.86f to Color.Transparent,
                    1f to accentColor.copy(alpha = 0.16f * boost),
                    1.4f to Color.Transparent
                ),
                center = c,
                radius = S * 2.6f
            ),
            radius = S * 2.6f,
            center = c,
            blendMode = BlendMode.Plus
        )
    }

    /** Обложки: задняя половина рисуется до дыры (её и прячет тень), передняя — после. */
    private fun DrawScope.drawArts(S: Float, back: Boolean) {
        val c = center
        for (art in arts) {
            if (art.alpha <= 0.01f) continue
            val behind = sin(art.angle) < 0f
            if (behind != back) continue

            // диск утончается к дыре: обложка прижимается к плоскости
            val thin = (art.radius / 3.2f).coerceIn(0f, 1f)
            val px = c.x + cos(art.angle) * art.radius * S
            val py = c.y + (sin(art.angle) * art.radius * squash + art.y0 * thin) * S

            // перспектива: обложка на передней половине диска чуть крупнее
            val scaleDepth = 0.85f + 0.3f * (0.5f + 0.5f * sin(art.angle))
            val stretch = art.stretch
            val baseSide = art.size * 0.75f * S * scaleDepth
            val w = baseSide * stretch
            val h = baseSide / sqrt(stretch)

            // растяжение идёт вдоль пути обложки на экране — у горизонта её вытягивает в нить
            val tangent = radiansToDegrees(kotlin.math.atan2(cos(art.angle) * squash, -sin(art.angle)))
            val color = heatedColor(art.heat)

            rotate(degrees = tangent + radiansToDegrees(art.face), pivot = Offset(px, py)) {
                val bitmap = art.bitmap
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset((px - w / 2f).toInt(), (py - h / 2f).toInt()),
                        dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                        alpha = art.alpha.coerceIn(0f, 1f),
                        filterQuality = FilterQuality.Low
                    )
                } else {
                    // обложка ещё едет — рисуем заготовку, чтобы выброс не выглядел пустым
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF2B2B33), accent.copy(alpha = 0.6f)),
                            start = Offset(px - w / 2f, py - h / 2f),
                            end = Offset(px + w / 2f, py + h / 2f)
                        ),
                        topLeft = Offset(px - w / 2f, py - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
                        alpha = art.alpha.coerceIn(0f, 1f)
                    )
                }
                // при нагреве у раскалённого диска обложка светлеет: накладка цвета
                if (art.heat > 0.05f) {
                    drawRect(
                        color = color.copy(alpha = art.alpha * art.heat * 0.35f),
                        topLeft = Offset(px - w / 2f, py - h / 2f),
                        size = Size(w, h),
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }

    private fun DrawScope.drawFlashes(S: Float) {
        val c = center
        for (flash in flashes) {
            if (flash.life <= 0f) continue
            val p = (flash.life / flash.ttl).coerceIn(0f, 1f)
            val px = c.x + flash.x * S
            val py = c.y + flash.y * S * squash
            val radius = flash.size * S * (1.9f - 0.9f * p)
            val tint = if (flash.accent) accent else Color(1f, 0.87f, 0.7f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.85f * easeOutCubic(p)), tint.copy(alpha = 0.25f * p), Color.Transparent),
                    center = Offset(px, py),
                    radius = radius
                ),
                radius = radius,
                center = Offset(px, py),
                blendMode = BlendMode.Plus
            )
        }
    }

    /** Обложка выпавшего трека: поднимается из горизонта, в EXPAND улетает в плеер. */
    private fun DrawScope.drawHero(heroTarget: Rect?, heroTargetRadius: Float) {
        val bitmap = heroBitmap ?: return
        if (phase != RollPhase.TOP && phase != RollPhase.EXPAND) return
        if (heroGrow <= 0.01f) return

        val base = heroRect() ?: return
        var rect = base
        var corner = base.width * 0.04f

        if (phase == RollPhase.EXPAND && heroTarget != null && heroTarget.width > 0f) {
            val p = easeOutCubic((phaseTime / (RollTimings.EXPAND / 1000f)).coerceIn(0f, 1f))
            rect = lerpRect(base, heroTarget, p)
            corner = base.width * 0.04f + (heroTargetRadius - base.width * 0.04f) * p
        }

        // дыхание обложки — композиция не стоит на месте
        val breathe = 1f + sin(time * 1.6f) * 0.007f
        val side = rect.width * breathe
        val cx = rect.center.x
        val cy = rect.center.y
        val drawRect = Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)

        // свечение акцентом — только пока обложка выходит из горизонта
        if (phase != RollPhase.EXPAND) {
            val glowRadius = side * 1.4f
            val glowAlpha = heroGrow * (0.34f + sin(time * 2.1f) * 0.1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glowAlpha), accent.copy(alpha = glowAlpha * 0.3f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus
            )
        }

        val scaleX = (1f - heroFlipY * 0.46f).coerceIn(0.35f, 1f)
        val scaleY = (1f - heroFlipX * 0.22f).coerceIn(0.6f, 1f)
        val path = Path().apply {
            addRoundRect(RoundRect(drawRect, CornerRadius(corner, corner)))
        }
        clipPath(path) {
            withTransform({ scale(scaleX, scaleY, pivot = Offset(cx, cy)) }) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset((cx - side / 2f).toInt(), (cy - side / 2f).toInt()),
                    dstSize = IntSize(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium
                )
            }
        }
    }

    // ==================== мелкая математика ====================

    private fun hotProfile(rn: Float): Float {
        // Яркость диска по радиусу: резко вспыхивает у ISCO, держится и гаснет к краю
        val inner = smoothstep(0.17f, 0.26f, rn)
        val outer = 1f - smoothstep(0.45f, 1f, rn)
        return (inner * outer * (0.85f + 0.3f * sin(rn * 19f))).coerceIn(0f, 1f)
    }

    private fun diskColor(rn: Float, accentColor: Color, n: Float): Color {
        // Внутренний край — почти белый, дальше уходит в оранжевый и в акцент обложки
        val hot = Color(1f, 0.965f, 0.90f)
        val warm = Color(1f, 0.69f, 0.40f)
        val t1 = ((rn - 0.2f) / 0.25f).coerceIn(0f, 1f)
        val t2 = ((rn - 0.45f) / 0.55f).coerceIn(0f, 1f)
        val base = lerpColor(lerpColor(hot, warm, t1), accentColor, t2 * 0.9f)
        return lerpColor(base, Color.White, n * 0.15f)
    }

    private fun heatedColor(heat: Float): Color =
        lerpColor(Color.White, Color(1f, 0.69f, 0.40f), heat * 0.35f)

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k
        )
    }

    private fun lerpRect(a: Rect, b: Rect, t: Float): Rect {
        val k = t.coerceIn(0f, 1f)
        return Rect(
            left = a.left + (b.left - a.left) * k,
            top = a.top + (b.top - a.top) * k,
            right = a.right + (b.right - a.right) * k,
            bottom = a.bottom + (b.bottom - a.bottom) * k
        )
    }

    private companion object {
        const val TAU = (PI * 2.0).toFloat()

        // Мировые единицы десктопной сцены: радиус тени горизонта = 1
        const val HORIZON = 1f
        const val R_SPAWN = 1.16f
        const val R_MAX = 10.5f
        const val FADE_EDGE = 2.6f
        const val VR_OUT = 3.6f
        const val VR_IN = 4.2f
        const val DISK_IN = 1.155f   // ISCO: 3 радиуса Шварцшильда
        const val DISK_OUT = 5.77f   // внешний край: 15 радиусов Шварцшильда
        const val ART_COUNT = 34
        const val FLASH_COUNT = 8
        const val STAR_COUNT = 140
        const val FILAMENTS = 130
        const val HERO_WORLD = 1.95f
    }
}

/**
 * Плёночное зерно «Чёрной дыры»: плитка шума, размноженная по экрану BitmapShader'ом.
 * Держим отдельным классом: и шейдер, и матрица сдвига — нативные объекты, пересобирать
 * их каждый кадр незачем, достаточно подвинуть матрицу.
 */
class FilmGrain(tile: android.graphics.Bitmap) {

    private val shader = android.graphics.BitmapShader(
        tile,
        android.graphics.Shader.TileMode.REPEAT,
        android.graphics.Shader.TileMode.REPEAT
    )
    private val matrix = android.graphics.Matrix()
    private val paint = android.graphics.Paint()

    init {
        paint.shader = shader
        paint.alpha = GRAIN_ALPHA
        paint.isAntiAlias = false
        paint.isFilterBitmap = false
    }

    /** Сдвиг плитки: зерно должно дрожать, а не стоять на месте. */
    fun offset(x: Float, y: Float) {
        matrix.setTranslate(x, y)
        shader.setLocalMatrix(matrix)
    }

    internal val frameworkPaint: android.graphics.Paint get() = paint

    private companion object {
        /** ~5% непрозрачности: зерно должно читаться, но не мешать. */
        const val GRAIN_ALPHA = 13
    }
}

// ==================== вспомогательное ====================

/** Экспоненциальное приближение к цели, независимое от частоты кадров. */
private fun approach(current: Float, target: Float, speed: Float, dt: Float): Float {
    val factor = 1f - kotlin.math.exp(-speed * dt)
    return current + (target - current) * factor
}

private fun easeOutCubic(x: Float): Float {
    val p = 1f - x.coerceIn(0f, 1f)
    return 1f - p * p * p
}

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val t = x.coerceIn(0f, 1f) - 1f
    return 1f + c3 * t * t * t + c1 * t * t
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Дешёвый псевдо-noise из трёх синусов: сгустки, потоки и нити без таблиц. */
private fun noise3(x: Float, y: Float, z: Float): Float {
    val n = sin(x * 1.7f + z * 1.3f) * 0.5f +
        sin(y * 2.3f - z * 0.9f + x) * 0.3f +
        sin((x + y) * 4.1f + z * 2.1f) * 0.2f
    return (n * 0.5f + 0.5f).coerceIn(0f, 1f)
}

private fun radiansToDegrees(radians: Float): Float = radians * 180f / PI.toFloat()
