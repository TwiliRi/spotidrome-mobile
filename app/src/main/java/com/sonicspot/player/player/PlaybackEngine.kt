package com.sonicspot.player.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единая точка настройки «железа» воспроизведения: буферизации, HTTP-источника и локального
 * кэша. Создаёт конфигурацию для любого ExoPlayer в приложении (основной и кроссфейдный).
 *
 * ПОЧЕМУ ЭТО ЗДЕСЬ, А НЕ В [PlayerManager]
 * ---------------------------------------
 * Изначально плеер собирался голым `ExoPlayer.Builder(context).build()`, то есть с дефолтами
 * Media3, и именно это было причиной зависаний:
 *
 * 1) `DefaultLoadControl` по умолчанию имеет `minBufferMs == maxBufferMs == 50_000`.
 *    Как только буфер доходит до 50 с, загрузчик **останавливается и закрывает соединение**,
 *    а при опустошении ниже 50 с — **открывает новое** с заголовком `Range: bytes=<позиция>-`.
 *    Для 3-минутного трека это десятки переподключений за одну песню, каждые несколько секунд.
 *
 * 2) Media3 (одинаково в `DefaultHttpDataSource` и `OkHttpDataSource`) на такой перезагрузке
 *    делает так (см. open(), строки про `bytesToSkip`):
 *    ```
 *    // If we requested a range starting from a non-zero position and received a 200 rather than
 *    // a 206, then the server does not support partial requests. We'll need to manually skip.
 *    long bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;
 *    ...
 *    skipFully(bytesToSkip, dataSpec);   // читает и ВЫБРАСЫВАЕТ байты буфером по 4096
 *    ```
 *    Стоит серверу/прокси ответить на Range не `206`, а `200` с начала файла — плеер обязан
 *    скачивать и выбрасывать байты до нужной позиции, 4 КБ за `read()`. На середине трека это
 *    мегабайты: звук стоит, состояние — STATE_BUFFERING, исключения нет. Позиция детерминирована,
 *    поэтому «замирает на одном и том же месте». Ровно этот симптом и наблюдался.
 *    (Navidrome на `?format=<то, что он не отдаёт как файл>` отвечает именно так:
 *    `200`, `Accept-Ranges: none`, без `Content-Length` — проверено на живом сервере.)
 *
 * 3) `DefaultHttpDataSource` по умолчанию имеет read/connect timeout **8 секунд** — это сильно
 *    агрессивнее, чем 30 с у API-клиента этого приложения. На мобильной сети/медленном
 *    транскоде такого хватает, чтобы легитимная дозагрузка превратилась в ошибку.
 *
 * Браузерный плеер Navidrome этого не видит: `<audio>` читает поток последовательно, одним
 * соединением, и не гоняет цикл «стоп загрузка → новое Range-соединение» каждые 50 с.
 *
 * ЧТО МЫ ДЕЛАЕМ
 * -------------
 * • `DefaultLoadControl`: буфер по времени, `maxBufferMs` заметно больше `minBufferMs`.
 *   Загрузка идёт одним непрерывным чтением трека — пересозданий запроса почти нет.
 * • `SimpleCache` + `CacheDataSource`: скачанное ложится на диск, поэтому дозагрузка и любая
 *   перемотка внутри уже буферизованного берут данные с диска, а не из сети. `skipFully`
 *   становится нечем вызвать: нет запроса — нет проблемы.
 * • `DefaultHttpDataSource` с явными таймаутами и `Accept-Encoding: identity`, чтобы прокси не
 *   подменял `Content-Length` gzip'ом (см. isCompressed-ветку в том же классе).
 *
 * НИ ОДНОЙ НОВОЙ ЗАВИСИМОСТИ: `media3-datasource` и `media3-database` приходят транзитивно от
 * `media3-exoplayer`.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlaybackEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Кэш живёт столько же, сколько процесс. [SimpleCache] бросает IllegalStateException, если
     * для той же папки создают второй инстанс, поэтому мы его намеренно не release()-им в
     * PlayerManager.release(): плеер пересоздаётся, кэш остаётся.
     */
    private val audioCache: SimpleCache by lazy {
        SimpleCache(
            cacheDir(),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context)
        )
    }

    /**
     * Буферизация под стриминг аудио. Цифры и их смысл:
     *
     * • minBuffer 45 с / maxBuffer 180 с — зазор между ними и есть «не переспрашивай сервер».
     *   Средний трек (3–4 мин) целиком прочитывается одним соединением.
     * • prioritizeTimeOverSizeThresholds(true) — иначе лимит по байтам (для аудио это
     *   DEFAULT_AUDIO_BUFFER_SIZE = 200 × 16 КБ ≈ 3.2 МБ) может оборвать загрузку раньше времени,
     *   и мы получим тот самый цикл «стоп → Range-перезапрос».
     * • старт через 1.5 с, после ребаффера через 3 с — заметно быстрее обычного «крутящегося»
     *   старта, чем дефолт.
     *
     * Для FLAC 1411 кбит/с 180 с ≈ 32 МБ в очереди. Если на слабом устройстве память важна,
     * уменьшите MAX_BUFFER_MS до 90 000 — защита от `skipFully` при этом остаётся в кэше.
     */
    fun createLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ MIN_BUFFER_MS,
            /* maxBufferMs = */ MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /** Фабрика источников для `ExoPlayer.Builder.setMediaSourceFactory(...)`. */
    fun createMediaSourceFactory(): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, createCacheDataSourceFactory()))

    /**
     * `DefaultDataSource` здесь нужен обёрткой: он сам разбирается с `file://`/`content://`
     * (загруженные офлайн треки), а http(s) отдаёт дальше — в кэш поверх HTTP.
     */
    private fun createCacheDataSourceFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(audioCache)
        .setUpstreamDataSourceFactory(createHttpDataSourceFactory())
        .setCacheKeyFactory { dataSpec -> cacheKey(dataSpec) }
        // Повреждённый/вытесненный системой кэш не должен ломать воспроизведение: при любой
        // ошибке кэша просто читаем из сети.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // Navidrome/обратный прокси иногда уводят стрим на редирект между http и https;
            // без этого флага Media3 не пойдёт по такому редиректу, а упадёт на открытии.
            .setAllowCrossProtocolRedirects(true)
            // Если прокси сожмёт audio/* в gzip, Content-Length в ответе относится к сжатому телу,
            // и плеер начнёт «читать вслепую». Запрет сжатия для медиа — стандартный фикс.
            .setDefaultRequestProperties(mapOf("Accept-Encoding" to "identity"))

    /**
     * Ключ кэша. Дефолт Media3 — полный URI, а в нём крутятся `u`/`t`/`s` (пользователь,
     * токен, соль): после перевхода логин даёт новый токен → тот же самый файл начинает
     * качаться заново, а старый мусор остаётся в кэше. Отбрасываем авторизационные и
     * служебные параметры, оставляем всё, что меняет тело ответа.
     */
    private fun cacheKey(dataSpec: DataSpec): String = dataSpec.key ?: stableKey(dataSpec.uri)

    private fun stableKey(uri: Uri): String {
        if (uri.isOpaque) return uri.toString()
        val builder = StringBuilder(SCHEME_TAG)
            .append(':')
            .append(uri.host.orEmpty())
            .append(uri.path.orEmpty())
        val names = runCatching { uri.queryParameterNames }.getOrNull()
        if (names != null) {
            for (name in names.toSortedSet()) {
                if (name in NON_MEANINGFUL_PARAMS) continue
                val value = runCatching { uri.getQueryParameter(name) }.getOrNull() ?: continue
                builder.append(if (builder.indexOf("?") < 0) "?" else "&").append(name).append('=').append(value)
            }
        }
        return builder.toString()
    }

    /**
     * Прогрев кэша вне MAIN-потока: первый доступ к [SimpleCache] создаёт папку и открывает
     * SQLite-индекс. Вызывается из SonicSpotApp.onCreate на Dispatchers.IO, чтобы создание
     * плеера не платило за это на главном потоке (в проекте за этим следят и так).
     */
    fun prewarmCache() {
        runCatching { audioCache.cacheSpace }
    }

    /** Полная очистка кэша аудио (для экрана «Память»/настроек). Плеер должен быть остановлен. */
    fun clearAudioCache() {
        // Сам SimpleCache не release()-им и не пересоздаём: Media3 запрещает второй инстанс
        // на ту же папку в пределах процесса, поэтому чистим содержимое через API кэша.
        runCatching {
            audioCache.keys.toList().forEach { key -> runCatching { audioCache.removeResource(key) } }
        }
    }

    /** Размер кэша в байтах. Считается по индексу кэша, обход файловой системы не нужен. */
    fun audioCacheSizeBytes(): Long = runCatching { audioCache.cacheSpace }.getOrDefault(0L)

    private fun cacheDir(): File =
        File(context.noBackupFilesDir, AUDIO_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

    private companion object {
        const val SCHEME_TAG = "spotidrome"
        const val AUDIO_CACHE_DIR_NAME = "audio_cache"

        /** Авторизация и обвязка Subsonic-протокола: на тело аудио не влияют. */
        val NON_MEANINGFUL_PARAMS = setOf("u", "t", "s", "v", "c", "f")

        const val USER_AGENT = "Spotidrome/1.0 (Android; SonicSpot)"

        // 150 МБ: примерно 70–100 треков. LRU вытесняет старое, так что кэш не разрастается.
        const val MAX_CACHE_BYTES = 150L * 1024 * 1024

        const val MIN_BUFFER_MS = 45_000
        const val MAX_BUFFER_MS = 180_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
