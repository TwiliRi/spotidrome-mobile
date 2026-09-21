package com.sonicspot.player.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.debug.PerformanceTracer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val progress: Float = 0f,
    val duration: Long = 0L,
    val currentPosition: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val autoDjEnabled: Boolean = false,
    val radioMode: RadioMode = RadioMode.OFF,
    val radioSource: String? = null // id трека или имя артиста для радио
)

enum class RadioMode {
    OFF,
    TRACK,
    ARTIST
}

sealed class SleepTimerState {
    object Off : SleepTimerState()
    data class Active(
        val endTimeMillis: Long,
        val remainingMillis: Long,
        val totalMillis: Long
    ) : SleepTimerState()
}

@Singleton
@OptIn(UnstableApi::class)
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val prefs: PreferencesManager,
    private val playbackEngine: PlaybackEngine,
    private val downloadStore: com.sonicspot.player.data.local.DownloadStore
) {
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var autoDjJob: Job? = null
    private var sleepTimerJob: Job? = null
    private val queueMutex = Mutex()

    private val _sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    val currentSongFlow: StateFlow<Song?> = _playerState.map { it.currentSong }.distinctUntilChanged { old, new -> old?.id == new?.id }.stateIn(scope, SharingStarted.Eagerly, null)
    val isPlayingFlow: StateFlow<Boolean> = _playerState.map { it.isPlaying }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
    val progressFlow: StateFlow<Float> = _playerState.map { it.progress }.distinctUntilChanged { old, new -> kotlin.math.abs(old - new) < 0.01f }.stateIn(scope, SharingStarted.Eagerly, 0f)
    private val _fullPlayerProgress = MutableStateFlow(0f)
    val fullPlayerProgressFlow: StateFlow<Float> = _fullPlayerProgress.asStateFlow()
    private val _fullPlayerPosition = MutableStateFlow(0L)
    val fullPlayerPositionFlow: StateFlow<Long> = _fullPlayerPosition.asStateFlow()

    val queueFlow: StateFlow<List<Song>> = _playerState.map { it.queue }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val upcomingQueue: StateFlow<List<Song>> = _playerState.map { state ->
        if (state.currentIndex >= 0 && state.currentIndex + 1 < state.queue.size) {
            state.queue.subList(state.currentIndex + 1, state.queue.size)
        } else emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val originalQueue = mutableListOf<Song>()
    private var dislikedIds: Set<String> = emptySet()
    private var skipDislikedEnabled: Boolean = true

    // Страховка от лавины автоскипов: если в очереди подряд идут дубли одного
    // непонравившегося трека, цепочка seekToNext могла не кончаться (вплоть до вылета).
    private var consecutiveDislikedSkips = 0

    // Crossfade
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeJob: Job? = null
    private var isCrossfading: Boolean = false
    private var crossfadeEnabled: Boolean = false
    private var crossfadeDurationSec: Int = 5

    // Scrobble tracking - для Last.fm / ListenBrainz и playCount
    private var scrobbled50SongId: String? = null
    private var scrobbledEndedSongId: String? = null

    // PlayQueue sync - синхронизация очереди между устройствами
    private var playQueueSyncEnabled: Boolean = true
    private var saveQueueJob: Job? = null
    private var isRestoringQueue: Boolean = false
    private var lastPositionSaveTime: Long = 0L
    private var lastSavedQueueHash: Int = 0
    private var hasRestoredQueue: Boolean = false

    // Защита от зависания. Единственный приём — «подтолкнуть» загрузку на текущей позиции.
    // Корень проблемы и его лечение — в PlaybackEngine; почему здесь больше нет
    // «перепрыгивания битого места» — комментарий у recoverStalledPlayback().
    private var lastPositionForStall: Long = -1L
    private var lastPositionTimeForStall: Long = System.currentTimeMillis()
    private var bufferingStartTime: Long = 0L
    private var retryJob: Job? = null
    private var isRecovering: Boolean = false
    private var stallRecoveryAttempts: Int = 0
    // «Пользователь хочет слышать музыку». Отдельно от player.isPlaying, потому что isPlaying ==
    // false и в нормальном ребаффере, и в STATE_IDLE, когда воспроизведение убили изнутри.
    private var userWantsPlayback: Boolean = false
    private val bufferingTimeoutMs = 20_000L
    private val stallTimeoutMs = 10_000L
    private val maxStallRecoveries = 3
    // Диагностика: состояние плеера пишется раз в 10 с, ПОКА КРУТИТСЯ ЦИКЛ ПРОГРЕССА — то есть
    // и когда музыка играет, и когда стоит. Без этого лог при «молчаливом» зависании пуст.
    private var lastHeartbeatMs: Long = 0L

    init {
        // Прогреваем кэш учётных данных сразу: getStreamUrl() при cachedCredentials == null
        // возвращает ПУСТУЮ строку, и MediaItem с пустым Uri = «трек не грузится» без внятной
        // ошибки. Домашний экран тоже это делает, но играть могут раньше/из другого экрана.
        scope.launch {
            runCatching { repository.refreshCredentialsCache() }
        }
        scope.launch {
            prefs.dislikedIdsFlow.collect { ids -> dislikedIds = ids }
        }
        scope.launch {
            prefs.skipDislikedFlow.collect { enabled -> skipDislikedEnabled = enabled }
        }
        scope.launch {
            prefs.crossfadeFlow.collect { enabled -> crossfadeEnabled = enabled }
        }
        scope.launch {
            prefs.crossfadeDurationFlow.collect { sec -> crossfadeDurationSec = sec.coerceIn(1, 12) }
        }
        scope.launch {
            prefs.playQueueSyncFlow.collect { enabled -> playQueueSyncEnabled = enabled }
        }
        // Пытаемся восстановить очередь с сервера при старте (как в Navidrome Web - очередь сохраняется между устройствами)
        // Оптимизация: увеличена задержка до 5 сек и убран prepare без автоплея чтобы не лагало первые 2-3 минуты
        scope.launch {
            delay(5000)
            if (!hasRestoredQueue && _playerState.value.queue.isEmpty()) {
                tryRestoreQueueFromServerIfNeeded()
            }
        }
    }

    private fun shouldSyncQueue(): Boolean = playQueueSyncEnabled && !isRestoringQueue

    private fun computeQueueHash(queue: List<Song>, currentId: String?, position: Long?): Int {
        var hash = queue.size
        hash = 31 * hash + (currentId?.hashCode() ?: 0)
        // Для позиции берем только секунды чтобы не спамить
        hash = 31 * hash + ((position ?: 0L) / 5000L).toInt()
        if (queue.isNotEmpty()) {
            hash = 31 * hash + queue.first().id.hashCode()
            hash = 31 * hash + queue.last().id.hashCode()
        }
        return hash
    }

    fun saveQueueToServerDebounced(position: Long? = null, immediate: Boolean = false) {
        if (!shouldSyncQueue()) return
        val state = _playerState.value
        if (state.queue.isEmpty()) {
            // Если очередь пустая - все равно сохраняем чтобы очистить на сервере
            if (lastSavedQueueHash == 0) return // уже пусто
        }
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            if (!immediate) delay(1000) // debounce 1 сек
            saveQueueToServerNow(position)
        }
    }

    fun saveQueueToServerNow(position: Long? = null) {
        if (!shouldSyncQueue()) return
        val state = _playerState.value
        val ids = state.queue.map { it.id }
        val currentId = state.currentSong?.id ?: state.queue.getOrNull(state.currentIndex)?.id
        val pos = position ?: state.currentPosition

        val hash = computeQueueHash(state.queue, currentId, pos)
        if (hash == lastSavedQueueHash && position == null) {
            // Очередь не изменилась, не спамим (кроме сохранения позиции)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                repository.savePlayQueue(ids, currentId, pos)
                lastSavedQueueHash = hash
            } catch (_: Exception) {
            }
        }
    }

    private fun savePositionToServerIfNeeded(pos: Long) {
        if (!shouldSyncQueue()) return
        val now = System.currentTimeMillis()
        if (now - lastPositionSaveTime < 10000) return // сохраняем позицию не чаще чем раз в 10 сек
        lastPositionSaveTime = now
        // Позицию сохраняем без дебаунса очереди, но тоже с проверкой
        scope.launch(Dispatchers.IO) {
            try {
                val state = _playerState.value
                val ids = state.queue.map { it.id }
                if (ids.isEmpty()) return@launch
                val currentId = state.currentSong?.id
                repository.savePlayQueue(ids, currentId, pos)
            } catch (_: Exception) {
            }
        }
    }

    // ==================== ВОССТАНОВЛЕНИЕ ПОСЛЕ ЗАВИСАНИЯ ====================
    //
    // Здесь жил «FREEZE FIX v2»: детектор сталла, прыжок на +3.5 с («перепрыгнуть битый кадр»)
    // и повтор с «&format=raw». Он не лечил причину, но сам ломал воспроизведение тремя способами:
    //
    //  • skipCorruptedSegment() молча срезал 3.5 с музыки. «Битого» места в файле нет — тот же
    //    трек играет и в веб-плеере, и после ручной перемотки. Ломалась не данные, а загрузка.
    //  • «&format=raw» — этого значения в Subsonic API нет. Для многих серверов неизвестный
    //    format означает «сделай транскод», а транскод отвечает 200 без Accept-Ranges и без
    //    Content-Length (проверено на живом сервере). На таком ответе перезапрос куска
    //    невозможен в принципе, и плеер залипал навсегда — то самое «дальше никак не грузится».
    //  • повтор через setMediaItem()+prepare() выбрасывал уже скачанный буфер: трек начинал
    //    грузиться с нуля, превращая короткую паузу в длинную.
    //
    // Причина устранена в PlaybackEngine (буферизация по времени + кэш). Ниже остаётся ровно
    // один безопасный приём: seek на ТЕКУЩУЮ позицию. Он отменяет зависшую загрузку и запускает
    // новую, сохранив MediaItem, очередь и уже скачанные байты — то же самое, что слушатель
    // делает руками («перемотать на секунду вперёд»), только без потери секунды звука.

    private fun handlePlayerError(error: PlaybackException?) {
        val errorName = error?.errorCodeName ?: "STALLED_WITHOUT_ERROR"
        val pos = runCatching { exoPlayer?.currentPosition ?: -1L }.getOrDefault(-1L)
        Log.e(
            "PlayerManager",
            "Playback problem: $errorName pos=$pos song=${_playerState.value.currentSong?.id} " +
                "attempt=$stallRecoveryAttempts msg=${error?.message}"
        )
        recoverStalledPlayback(errorName)
    }

    private fun recoverStalledPlayback(reason: String) {
        if (isRecovering) return
        val player = exoPlayer ?: return
        if (isPlayerReleased(player)) return

        // Старый предохранитель «счётчик >= лимита -> сразу следующий трек» убран: теперь
        // лестница ограничена сама (while по maxStallRecoveries), а счётчик всегда обнуляется
        // в конце. Иначе унаследованное значение от прерванной попытки отправляло бы на
        // следующий трек вообще без попыток восстановления.
        isRecovering = true
        retryJob?.cancel()
        retryJob = scope.launch {
            try {
                val pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                // Данные скачаны далеко вперёд, но звук не идёт — значит виноват тракт вывода,
                // и перезапускать загрузку бессмысленно: качать уже нечего. Сразу идём к тому,
                // что лечит именно вывод.
                val sinkStall = reason.startsWith("SINK_STALL")
                Log.w("PlayerManager", "Recovering stall: $reason at $pos (sinkStall=$sinkStall)")
                cancelCrossfade()
                runCatching { player.volume = 1f }

                // Лестница прогоняется целиком за ОДИН заход, между ступенями — 3 с. Раньше
                // между попытками приходилось ждать полный цикл детекции (10 с), и до
                // пересоздания тракта проходило больше полминуты тишины.
                var attempt = 0
                var recovered = false
                while (attempt < maxStallRecoveries && !recovered) {
                    attempt++
                    stallRecoveryAttempts = attempt
                    when (attempt) {
                        1 -> if (sinkStall) flushSinkByNudge(player, pos) else restartLoading(player, pos)
                        2 -> restartAudioTrack(player)
                        else -> recreateAudioTrack(player, pos)
                    }
                    delay(3_000)
                    val newPos = runCatching { player.currentPosition }.getOrDefault(pos)
                    // УСПЕХ — ТОЛЬКО ЕСЛИ ПОЗИЦИЯ РЕАЛЬНО ПОШЛА. Проверка по playbackState тут
                    // категорически недостаточна: при залипшем тракте состояние дёргается
                    // BUFFERING↔READY каждые ~150 мс, «READY» ловится случайно, счётчик
                    // обнулялся — и лестница не поднималась выше бесполезного в этом случае seekTo.
                    recovered = newPos > pos + 800L
                    Log.i(
                        "PlayerManager",
                        "recovery step $attempt: $pos -> $newPos (recovered=$recovered)"
                    )
                }
                stallRecoveryAttempts = 0
                if (recovered) {
                    resetStallDetection()
                } else {
                    Log.w("PlayerManager", "Stall not recoverable after $attempt steps ($reason)")
                    skipToNextOnError()
                }
            } catch (t: Throwable) {
                // Отмену корутины (например, смена трека во время паузы) не считаем ошибкой
                if (t is CancellationException) throw t
                Log.e("PlayerManager", "Stall recovery failed: ${t.message}")
            } finally {
                isRecovering = false
            }
        }
    }

    // Перезапустить загрузку, сохранив буфер и очередь. Лечит нехватку данных (сеть).
    private fun restartLoading(player: Player, pos: Long) {
        Log.w("PlayerManager", "recovery: restart loading at $pos")
        // STATE_IDLE (очередь переписали без prepare()) лечится явным prepare(): seekTo готовит
        // плеер лишь неявно, а на неявное в пути лечения зависаний полагаться нельзя.
        if (runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
            == Player.STATE_IDLE
        ) {
            runCatching { player.prepare() }
        }
        runCatching { player.seekTo(pos) }
        runCatching { player.playWhenReady = true }
    }

    // ГЛАВНОЕ ЛЕЧЕНИЕ ДЛЯ SINK_STALL.
    // При залипшем устройстве Android подменяет время метки AudioTrack на текущее (в логе это
    // «device stall time corrected»), поэтому AudioTrackPositionTracker считает
    // positionUs = timestampPositionUs + ~0 и позиция замирает. getTimestamp() при этом успешен,
    // так что AudioTimestampPoller НИКОГДА не выходит из STATE_TIMESTAMP_ADVANCING — само не
    // рассосётся. Вылечить можно только flush(): по исходнику DefaultAudioSink (1.7.1, строка 1549)
    // он делает audioTrackPositionTracker.reset() и пересоздаёт AudioTrack — поллер возвращается
    // в INITIALIZING и снова считает позицию по playback head.
    // Сдвиг на 250 мс, а не seekTo на ту же позицию: смена позиции гарантирует настоящий
    // discontinuity и сброс. Вручную ты перематывал на секунду — работает ровно этот механизм.
    private fun flushSinkByNudge(player: Player, pos: Long) {
        Log.w("PlayerManager", "recovery: flush audio sink by +250ms nudge")
        runCatching { player.seekTo(pos + 250L) }
        runCatching { player.playWhenReady = true }
    }

    // Перезапустить звуковой тракт без потери буфера: audioTrack.pause()/play().
    private fun restartAudioTrack(player: Player) {
        Log.w("PlayerManager", "recovery: restart audio track (pause/play)")
        runCatching { player.pause() }
        runCatching { player.play() }
    }

    // Полное пересоздание AudioTrack и рендерера. Дорого, но с дисковым кэшем PlaybackEngine
    // перекачивать данные не нужно.
    private fun recreateAudioTrack(player: Player, pos: Long) {
        Log.w("PlayerManager", "recovery: recreate audio track (stop+prepare+seek+play) at $pos")
        runCatching { player.stop() }
        runCatching { player.prepare() }
        runCatching { player.seekTo(pos) }
        runCatching { player.play() }
    }

    private fun skipToNextOnError() {
        retryJob?.cancel()
        retryJob = scope.launch {
            try {
                val state = _playerState.value
                cancelCrossfade()
                stallRecoveryAttempts = 0
                val player = try { getPlayer() } catch (_: Exception) { null } ?: return@launch
                runCatching { player.volume = 1f }
                if (state.currentIndex + 1 < state.queue.size) {
                    Log.i("PlayerManager", "Skipping to next track after unrecoverable playback error")
                    runCatching { player.seekToNextMediaItem() }
                } else {
                    Log.i("PlayerManager", "Last track unrecoverable, restarting it from the beginning")
                    runCatching {
                        player.seekTo(0L)
                        player.play()
                    }
                }
            } catch (t: Throwable) {
                Log.e("PlayerManager", "skipToNextOnError failed: ${t.message}")
            }
        }
    }

    private fun resetStallDetection() {
        lastPositionForStall = -1L
        lastPositionTimeForStall = System.currentTimeMillis()
        bufferingStartTime = 0L
    }

    suspend fun restoreQueueFromServer(autoPlay: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        PerformanceTracer.start("restoreQueue_${if (autoPlay) "autoPlay" else "noAutoPlay"}")
        try {
            if (!playQueueSyncEnabled) return@withContext Result.failure(Exception("Sync disabled"))
            PerformanceTracer.start("getPlayQueue_network")
            val result = repository.getPlayQueue()
            PerformanceTracer.end("getPlayQueue_network")

            result.fold(
                onSuccess = { pq ->
                    if (pq.entry.isEmpty()) {
                        PerformanceTracer.end("restoreQueue_${if (autoPlay) "autoPlay" else "noAutoPlay"}")
                        return@fold Result.failure<Unit>(Exception("Empty server queue"))
                    }
                    isRestoringQueue = true
                    try {
                        val songs = pq.entry
                        val currentId = pq.current
                        val position = pq.position

                        PerformanceTracer.log("restoreQueue", "size=${songs.size} current=$currentId pos=$position autoPlay=$autoPlay")
                        if (songs.size > 50) {
                            PerformanceTracer.log("restoreQueue", "⚠ Large queue ${songs.size} may lag")
                        }

                        val startIndex = if (currentId != null) {
                            songs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                        } else 0

                        // Восстанавливаем локально без сохранения обратно на сервер
                        withContext(Dispatchers.Main) {
                            val player = try { getPlayer() } catch (_: Exception) { null }
                            if (player != null) {
                                try {
                                    synchronized(originalQueue) {
                                        originalQueue.clear()
                                        originalQueue.addAll(songs)
                                    }
                                    PerformanceTracer.start("createMediaItems_${songs.size}")
                                    val mediaItems = songs.map { song ->
                                        try { createMediaItem(song) } catch (_: Exception) {
                                            MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                                        }
                                    }
                                    val createMs = PerformanceTracer.end("createMediaItems_${songs.size}")
                                    if (createMs > 100) {
                                        PerformanceTracer.log("createMediaItems", "🔴 SLOW ${createMs}ms for ${songs.size} items - Uri.parse heavy")
                                    }

                                    // Локальная сессия важнее серверной: если слушатель уже что-то
                                    // слушает, держим ЕГО трек и позицию, а не прыгаем на серверные.
                                    val localPlaying = userWantsPlayback || _playerState.value.isPlaying
                                    var targetIndex = startIndex
                                    var targetPos = position.coerceAtLeast(0L)
                                    if (localPlaying) {
                                        val curId = _playerState.value.currentSong?.id
                                        val idx = curId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1
                                        if (idx >= 0) {
                                            targetIndex = idx
                                            targetPos = runCatching { player.currentPosition }
                                                .getOrDefault(targetPos).coerceAtLeast(0L)
                                        }
                                    }
                                    // Было: prepare() вызывался только при autoPlay, «чтобы не лагало
                                    // 2-3 минуты». setMediaItems() при этом оставляет плеер в STATE_IDLE,
                                    // playWhenReady остаётся true, музыка молчит, а сторож зависания ничего
                                    // не видит (он ждёт либо BUFFERING, либо READY). Это и был главный баг.
                                    // prepare() дорог для ВСЕЙ очереди только на старте приложения — его и
                                    // оставляем отложенным, а живое воспроизведение глушить нельзя.
                                    val mustKeepAudio = localPlaying || autoPlay ||
                                        player.playbackState != Player.STATE_IDLE

                                    PerformanceTracer.start("setMediaItems_${songs.size}")
                                    player.setMediaItems(mediaItems, targetIndex, targetPos)
                                    val setMs = PerformanceTracer.end("setMediaItems_${songs.size}")

                                    if (mustKeepAudio) {
                                        PerformanceTracer.start("prepare_${songs.size}")
                                        player.prepare()
                                        val prepMs = PerformanceTracer.end("prepare_${songs.size}")
                                        if (prepMs > 500 && songs.size > 20) {
                                            PerformanceTracer.log("prepare", "🔴 prepare SLOW ${prepMs}ms for ${songs.size} tracks")
                                        }
                                        if (localPlaying || autoPlay) {
                                            userWantsPlayback = true
                                            player.play()
                                        }
                                    } else {
                                        PerformanceTracer.log(
                                            "restoreQueue",
                                            "Очередь восстановлена без prepare (ничего не играло), setMediaItems=${setMs}ms"
                                        )
                                    }
                                    _playerState.update {
                                        it.copy(
                                            queue = songs,
                                            currentSong = songs.getOrNull(targetIndex),
                                            currentIndex = targetIndex,
                                            currentPosition = runCatching { player.currentPosition }
                                                .getOrDefault(targetPos),
                                            // Не врать UI: было isPlaying = autoPlay, из-за чего
                                            // интерфейс показывал «паузу» у реально играющего трека.
                                            isPlaying = runCatching { player.isPlaying }.getOrDefault(false)
                                        )
                                    }
                                    scrobbled50SongId = null
                                    scrobbledEndedSongId = null
                                    lastSavedQueueHash = computeQueueHash(songs, currentId, position)
                                    hasRestoredQueue = true
                                    if (autoPlay) startProgressUpdates()
                                } catch (e: Exception) {
                                    PerformanceTracer.log("restoreQueue", "FAILED ${e.message}")
                                }
                            }
                        }
                        PerformanceTracer.end("restoreQueue_${if (autoPlay) "autoPlay" else "noAutoPlay"}")
                        Result.success(Unit)
                    } finally {
                        isRestoringQueue = false
                    }
                },
                onFailure = { e ->
                    PerformanceTracer.end("restoreQueue_${if (autoPlay) "autoPlay" else "noAutoPlay"}")
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            PerformanceTracer.end("restoreQueue_${if (autoPlay) "autoPlay" else "noAutoPlay"}")
            isRestoringQueue = false
            Result.failure(e)
        }
    }

    private suspend fun tryRestoreQueueFromServerIfNeeded() {
        try {
            if (!playQueueSyncEnabled) return
            if (_playerState.value.queue.isNotEmpty()) return // локальная очередь уже есть - не перезаписываем
            if (hasRestoredQueue) return
            repository.refreshCredentialsCache()
            val result = repository.getPlayQueue()
            if (result.isSuccess) {
                val pq = result.getOrNull()
                if (pq != null && pq.entry.isNotEmpty()) {
                    // ВАЖНО: проверяем ПОВТОРНО после сетевого запроса. Пока ждали getPlayQueue()
                    // (1-6 с), пользователь успел нажать play. Раньше в этот момент очередь
                    // перезаписывалась на живую и музыка умолкала — вот что выглядело как
                    // «трек завис на одном и том же месте».
                    if (userWantsPlayback || _playerState.value.isPlaying ||
                        _playerState.value.queue.isNotEmpty()
                    ) {
                        hasRestoredQueue = true
                        Log.i("PlayerManager", "Skip server queue restore: local playback already active")
                        return
                    }
                    // Если на сервере есть очередь и локально пусто - восстанавливаем
                    restoreQueueFromServer(autoPlay = false)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun syncQueueFromServer() {
        scope.launch {
            restoreQueueFromServer(autoPlay = false)
        }
    }

    private fun scrobbleSong(songId: String, submission: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                repository.scrobble(songId, submission)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Запускает MusicService (MediaSessionService). ВАЖНО: MediaSessionService НЕ стартует
     * сам — его нужно запустить именно отсюда. Раньше сервис вообще не запускался
     * («сам станет foreground»): не было ни MediaSession, ни плеера в шторке уведомлений,
     * и система убивала/замораживала процесс в фоне и при блокировке экрана.
     *
     * Используем startService, НЕ startForegroundService: последний требует startForeground
     * в течение 5 секунд, а Media3 поднимает foreground только когда реально идёт звук —
     * отсюда и был ForegroundServiceDidNotStartInTimeException при быстром переключении
     * треков. startService + автопереход Media3 в foreground при старте воспроизведения —
     * штатный путь для музыкальных плееров (документация Android media).
     */
    private fun ensurePlaybackServiceStarted() {
        try {
            context.startService(Intent(context, MusicService::class.java))
        } catch (_: Exception) {
            // API 26+: старт сервиса из фона запрещён. Основной сценарий (нажатие play в UI)
            // всегда стартует сервис из foreground, так что здесь тихий fallback нормален.
        }
    }

    fun getPlayer(): ExoPlayer {
        val current = exoPlayer
        if (current == null || isPlayerReleased(current)) {
            exoPlayer = null
        }
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context)
                // Без этого плеер наследует AudioAttributes.DEFAULT (USAGE_UNKNOWN): ОС считает
                // его не-музыкой — нет медиа-маршрута, нет надлежащегоVolume-поведения, и при
                // звонке такой поток могут не приглушить. handleAudioFocus = false сознательно:
                // включённый фокус сам по себе ставит паузу, а паузы мы тут и так ловили.
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                // ПРИЧИНА САМОПРОИЗВОЛЬНЫХ ОСТАНОВОК НА BLUETOOTH.
                // Media3 1.7.1, ExoPlayerImpl.onAudioBecomingNoisy() (строка 3184) делает ровно
                // одно: updatePlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY).
                // ACTION_AUDIO_BECOMING_NOISY прилетает при ЛЮБОМ событии гарнитуры — переподключение,
                // смена дорожки, переговоры кодека A2DP, фантомные события на части устройств.
                // Итог: музыка тихо останавливается, исключения нет, позиция замирает — ровно то,
                // что было в логе. Ниже причина логируется; здесь она просто убрана.
                // Кроссфейдный плеер в этом же файле всегда создавался с false.
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                // Буфер по времени + локальный кэш. Без этого Media3 на дефолтах
                // (min==max==50 с) перезапускал HTTP-загрузку каждые 50 с и зависал,
                // если сервер отвечал 200 вместо 206. См. PlaybackEngine.
                .setMediaSourceFactory(playbackEngine.createMediaSourceFactory())
                .setLoadControl(playbackEngine.createLoadControl())
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _playerState.update { it.copy(isPlaying = isPlaying) }
                            if (isPlaying) {
                                // Страховка: звук пошёл (в т.ч. AutoDJ/внешние кнопки) —
                                // сервис должен жить, чтобы получить foreground+уведомление.
                                ensurePlaybackServiceStarted()
                                userWantsPlayback = true
                                stallRecoveryAttempts = 0
                                resetStallDetection()
                                startProgressUpdates()
                            } else {
                                if (isCrossfading) {
                                    crossfadePlayer?.pause()
                                }
                            }
                        }
                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                            val why = when (reason) {
                                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE(MediaSession)"
                                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                                else -> "reason=$reason"
                            }
                            Log.i(
                                "PlayerManager",
                                "playWhenReady=$playWhenReady ($why) wantPlayback=$userWantsPlayback " +
                                    "pos=${runCatching { exoPlayer?.currentPosition }.getOrNull()} " +
                                    "song=${_playerState.value.currentSong?.id}"
                            )
                            // Явная пауза пользователя снимает намерение. Отказ по фокусу, «шуму»
                            // или внешней команде намерения НЕ снимает: пользователь-то слушать
                            // хочет, и сторож обязан это видеть.
                            if (!playWhenReady &&
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                            ) {
                                userWantsPlayback = false
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val index = currentMediaItemIndex
                            _fullPlayerPosition.value = 0L
                            _fullPlayerProgress.value = 0f
                            stallRecoveryAttempts = 0
                            resetStallDetection()
                            startProgressUpdates()

                            val newSongId = _playerState.value.queue.getOrNull(index)?.id
                            if (newSongId != null && newSongId != _playerState.value.currentSong?.id) {
                                scrobbled50SongId = null
                                scrobbledEndedSongId = null
                            }

                            if (crossfadeEnabled && !isCrossfading && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                                try {
                                    volume = 0f
                                    scope.launch {
                                        val fadeMs = (crossfadeDurationSec * 1000L).coerceIn(500L, 12000L)
                                        val steps = 20
                                        val stepMs = fadeMs / steps
                                        for (i in 0..steps) {
                                            val p = i.toFloat() / steps
                                            try { volume = p } catch (_: Exception) {}
                                            delay(stepMs)
                                        }
                                        try { volume = 1f } catch (_: Exception) {}
                                    }
                                } catch (_: Exception) {}
                            } else if (!isCrossfading) {
                                try { volume = 1f } catch (_: Exception) {}
                            }

                            if (index >= 0 && index < _playerState.value.queue.size) {
                                val nextSong = _playerState.value.queue.getOrNull(index)
                                if (skipDislikedEnabled && nextSong != null && dislikedIds.contains(nextSong.id)) {
                                    consecutiveDislikedSkips++
                                    if (consecutiveDislikedSkips <= _playerState.value.queue.size) {
                                        scope.launch {
                                            delay(100)
                                            try { seekToNextMediaItem() } catch (_: Exception) {}
                                        }
                                    } else {
                                        // Слишком длинная цепочка скипов (дубли в очереди) —
                                        // прекращаем лавину и просто фиксируем позицию.
                                        consecutiveDislikedSkips = 0
                                        _playerState.update { it.copy(currentSong = nextSong, currentIndex = index, currentPosition = 0L, progress = 0f) }
                                    }
                                } else {
                                    consecutiveDislikedSkips = 0
                                    _playerState.update { it.copy(currentSong = nextSong, currentIndex = index, currentPosition = 0L, progress = 0f) }
                                    saveQueueToServerDebounced(position = 0L, immediate = false)
                                    checkAutoDjQueue(nextSong)
                                }
                            } else {
                                if (_playerState.value.autoDjEnabled) {
                                    _playerState.value.currentSong?.let { current ->
                                        scope.launch { addAutoDjTracks(current) }
                                    }
                                }
                            }
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val name = when (playbackState) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "state=$playbackState"
                            }
                            Log.i(
                                "PlayerManager",
                                "state=$name playWhenReady=${runCatching { exoPlayer?.playWhenReady }.getOrNull()} " +
                                    "pos=${runCatching { exoPlayer?.currentPosition }.getOrNull()} " +
                                    "wantPlayback=$userWantsPlayback song=${_playerState.value.currentSong?.id}"
                            )
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    _fullPlayerPosition.value = try { currentPosition } catch (_: Exception) { 0L }
                                    bufferingStartTime = 0L
                                    stallRecoveryAttempts = 0
                                    startProgressUpdates()
                                }
                                Player.STATE_BUFFERING -> {
                                    if (bufferingStartTime == 0L) bufferingStartTime = System.currentTimeMillis()
                                }
                                Player.STATE_IDLE -> {
                                    bufferingStartTime = 0L
                                    // Если IDLE, а слушать хотели — восстанавливаем. Раньше здесь
                                    // стояло только _playerState.value.isPlaying, но при
                                    // becoming-noisy готовность играть снимается, isPlaying=false,
                                    // и выход в IDLE оставался без присмотра.
                                    if ((userWantsPlayback || _playerState.value.isPlaying) && !isRecovering) {
                                        recoverStalledPlayback("player went IDLE while playing")
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    bufferingStartTime = 0L
                                    val endedSongId = _playerState.value.currentSong?.id
                                    if (endedSongId != null && scrobbledEndedSongId != endedSongId) {
                                        scrobbledEndedSongId = endedSongId
                                        scrobbleSong(endedSongId, submission = true)
                                    }
                                    if (_playerState.value.autoDjEnabled) {
                                        _playerState.value.currentSong?.let { current ->
                                            scope.launch { addAutoDjTracks(current) }
                                        }
                                    }
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e("PlayerManager", "onPlayerError: ${error.errorCodeName} ${error.message} cause=${error.cause?.message}")
                            cancelCrossfade()
                            try { volume = 1f } catch (_: Exception) {}
                            handlePlayerError(error)
                        }
                        override fun onPlayerErrorChanged(error: PlaybackException?) {
                            if (error != null) {
                                Log.e("PlayerManager", "onPlayerErrorChanged: ${error.errorCodeName} ${error.message}")
                                // Не вызываем handle здесь чтобы не дублировать, onPlayerError уже вызвался
                            }
                        }
                    })
                }
        }
        return exoPlayer!!
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastStateUpdate = 0L
            var lastUiUpdate = 0L
            while (isActive) {
                try {
                    val player = exoPlayer ?: break
                    if (isPlayerReleased(player)) break
                    val pos = try { player.currentPosition } catch (_: Exception) { 0L }
                    val dur = try { player.duration.coerceAtLeast(0L) } catch (_: Exception) { 0L }
                    val progress = if (dur > 0) pos.toFloat() / dur else 0f
                    val now = System.currentTimeMillis()
                    // UI-потоки прогресса публикуются максимум 10 раз в секунду: поллеру
                    // нужен тик 50 мс только для сторожа зависаний/кроссфейда, а подписчики
                    // (слайдер, тексты) не должны пересобираться 20 раз в секунду — это была
                    // главная нагрузка на CPU при открытом плеере (тепловой троттлинг).
                    if (now - lastUiUpdate >= 100) {
                        _fullPlayerPosition.value = pos
                        _fullPlayerProgress.value = progress
                        lastUiUpdate = now
                    }
                    if (now - lastStateUpdate >= 1000) {
                        _playerState.update { it.copy(currentPosition = pos, duration = dur, progress = progress) }
                        lastStateUpdate = now
                    }

                    if (progress >= 0.5f && dur > 0) {
                        val currentId = _playerState.value.currentSong?.id
                        if (currentId != null && scrobbled50SongId != currentId) {
                            scrobbled50SongId = currentId
                            scrobbleSong(currentId, submission = true)
                        }
                    }

                    if (dur > 0 && pos > 1000) {
                        savePositionToServerIfNeeded(pos)
                    }

                    // === СТОРОЖ ЗАВИСАНИЯ ===
                    // Только детектирует и один раз подталкивает загрузку. Он намеренно НЕ
                    // меняет позицию и НЕ переопределяет MediaItem: и то и другое раньше
                    // сбрасывало уже скачанный буфер и превращало паузу в повторную загрузку.
                    val playbackState = try { player.playbackState } catch (_: Exception) { Player.STATE_IDLE }
                    val isPlaying = try { player.isPlaying } catch (_: Exception) { false }
                    val playWhenReady = try { player.playWhenReady } catch (_: Exception) { false }

                    // Состояние плеера тут намеренно НЕ участвует: прежняя версия требовала
                    // STATE_READY (или STATE_BUFFERING + playWhenReady), поэтому «playWhenReady=true
                    // при STATE_IDLE» — то, во что превращало воспроиз перезаписью очереди без
                    // prepare(), — было для сторожа невидимо: 100 секунд тишины и ни одной строки
                    // в логе. Критерий теперь один: звук хотят, а позиция не движется.
                    // Критерий — намерение слушателя, НЕ playWhenReady. Если что-то сняло
                    // playWhenReady за спиной пользователя (AUDIO_BECOMING_NOISY при отключении
                    // Bluetooth, команда MediaSession, переустановка очереди), прежняя проверка
                    // «userWantsPlayback && playWhenReady» как раз и слепла: позиция стояла,
                    // а сторож молчал. Теперь молчание невозможно.
                    val wantSound = userWantsPlayback
                    if (now - lastHeartbeatMs >= 10_000L) {
                        lastHeartbeatMs = now
                        Log.d(
                            "PlayerManager",
                            "heartbeat: pos=$pos dur=$dur state=$playbackState isPlaying=$isPlaying " +
                                "playWhenReady=$playWhenReady wantPlayback=$wantSound " +
                                "buffered=${runCatching { player.bufferedPosition }.getOrNull()} " +
                                "song=${_playerState.value.currentSong?.id}"
                        )
                    }
                    if (!wantSound) {
                        if (lastPositionForStall != -1L && now - lastPositionTimeForStall > 2000) {
                            resetStallDetection()
                        }
                        bufferingStartTime = 0L
                    } else {
                        if (playbackState == Player.STATE_BUFFERING) {
                            if (bufferingStartTime == 0L) bufferingStartTime = now
                        } else {
                            bufferingStartTime = 0L
                        }
                        val longBuffering = playbackState == Player.STATE_BUFFERING &&
                            now - bufferingStartTime > bufferingTimeoutMs
                        // pos == 0 — это ещё не «зависание», а медленный старт (34 МБ FLAC по
                        // мобильной сети легко идёт 15-20 с). Для него порог больше, иначе
                        // восстановление само бы и запускало бесконечные перезагрузки.
                        val aheadNow = runCatching { player.bufferedPosition }.getOrDefault(pos) - pos
                        val stuckMs = when {
                            // playWhenReady снят при живом намерении слушать — это не медленный
                            // старт, а немедленная неисправность: воспроизведение кто-то
                            // остановил (becoming-noisy, внешняя команда). Ждать 10 с тут нечего.
                            !playWhenReady -> 1_500L
                            // Данных впереди с запасом, а позиция стоит — это НЕ сеть и НЕ
                            // медленный старт, а сломанный тракт вывода. Здесь ложного
                            // срабатывания бояться нечего, поэтому не ждём: при живом запасе
                            // данных сброс буфера нам ничего не стоит.
                            aheadNow > 2_000L -> 5_000L
                            pos > 0L -> stallTimeoutMs
                            else -> bufferingTimeoutMs
                        }
                        val frozen = lastPositionForStall == pos &&
                            now - lastPositionTimeForStall > stuckMs
                        if (pos != lastPositionForStall) {
                            lastPositionForStall = pos
                            lastPositionTimeForStall = now
                        }
                        if (longBuffering || frozen) {
                            lastPositionTimeForStall = now
                            bufferingStartTime = now
                            val buffered = runCatching { player.bufferedPosition }.getOrDefault(-1L)
                            val suppr = runCatching { player.playbackSuppressionReason }.getOrNull()
                            val err = runCatching { player.playerError?.errorCodeName }.getOrNull()
                            // Главный водораздел: данные ЕСТЬ, но звук не идёт — значит дело не в
                            // сети и не в буфере, а в тракте вывода. buffered - pos > 2 с означает
                            // «скачано далеко вперёд, но не проигрывается».
                            val ahead = buffered - pos
                            val kind = when {
                                !playWhenReady -> "PLAY_WHEN_READY_FALSE(кто-то снял готовность играть)"
                                playbackState == Player.STATE_IDLE -> "IDLE(плеер не подготовлен)"
                                playbackState == Player.STATE_ENDED -> "ENDED"
                                ahead > 2_000L -> "SINK_STALL(данные есть, +${ahead}мс вперёд)"
                                else -> "NO_DATA(нехватка данных, +${ahead}мс)"
                            }
                            Log.w(
                                "PlayerManager",
                                "Playback not advancing at $pos ms: $kind state=$playbackState " +
                                    "isPlaying=$isPlaying playWhenReady=$playWhenReady " +
                                    "buffered=$buffered suppression=$suppr error=$err " +
                                    "song=${_playerState.value.currentSong?.id}"
                            )
                            recoverStalledPlayback(kind)
                        }
                    }

                    // Кроссфейд триггер
                    if (crossfadeEnabled && !isCrossfading && dur > 5000 && pos > 1000) {
                        val remaining = dur - pos
                        val cfMs = crossfadeDurationSec * 1000L
                        if (remaining in 200..cfMs && _playerState.value.currentIndex + 1 < _playerState.value.queue.size) {
                            if (isPlaying) {
                                triggerCrossfade(remaining)
                            }
                        }
                    }

                    delay(if (isPlaying) 50 else 300)
                } catch (_: Exception) {
                    delay(300)
                }
            }
        }
    }

    private fun triggerCrossfade(remainingMs: Long) {
        if (isCrossfading) return
        val state = _playerState.value
        val nextSong = state.queue.getOrNull(state.currentIndex + 1) ?: return
        val fadeDuration = remainingMs.coerceIn(500L, (crossfadeDurationSec * 1000L).coerceIn(1000L, 12000L))
        isCrossfading = true

        try {
            if (crossfadePlayer == null || isPlayerReleased(crossfadePlayer!!)) {
                crossfadePlayer = ExoPlayer.Builder(context)
                    .setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        false
                    )
                    .setMediaSourceFactory(playbackEngine.createMediaSourceFactory())
                    .setLoadControl(playbackEngine.createLoadControl())
                    .build().apply {
                        // Копируем аудио атрибуты
                        setHandleAudioBecomingNoisy(false)
                    }
            }
            val cfPlayer = crossfadePlayer!!
            try { cfPlayer.clearMediaItems() } catch (_: Exception) {}
            cfPlayer.setMediaItem(createMediaItem(nextSong))
            cfPlayer.prepare()
            cfPlayer.volume = 0f
            cfPlayer.play()

            crossfadeJob?.cancel()
            crossfadeJob = scope.launch {
                val steps = 30
                val stepMs = fadeDuration / steps
                for (i in 0..steps) {
                    val p = i.toFloat() / steps
                    try {
                        exoPlayer?.volume = (1f - p).coerceIn(0f, 1f)
                        cfPlayer.volume = p.coerceIn(0f, 1f)
                    } catch (_: Exception) {}
                    delay(stepMs)
                }
                try {
                    // После кроссфейда переключаем основной плеер на следующий трек с позицией = длительность кроссфейда
                    val main = exoPlayer
                    if (main != null && !isPlayerReleased(main)) {
                        // Основной плеер уже должен был перейти на следующий трек автоматически, но на всякий случай форсируем
                        if (main.currentMediaItemIndex == state.currentIndex) {
                            main.seekTo(state.currentIndex + 1, fadeDuration.coerceAtMost(10000L))
                        } else {
                            // Если уже перешел, просто синхронизируем позицию
                            main.seekTo(fadeDuration.coerceAtMost(main.duration.coerceAtLeast(fadeDuration)))
                        }
                        main.volume = 1f
                    }
                    cfPlayer.pause()
                    cfPlayer.clearMediaItems()
                    cfPlayer.volume = 0f
                } catch (_: Exception) {}
                isCrossfading = false
            }
        } catch (_: Exception) {
            isCrossfading = false
            try { crossfadePlayer?.volume = 0f } catch (_: Exception) {}
            try { exoPlayer?.volume = 1f } catch (_: Exception) {}
        }
    }

    private fun cancelCrossfade() {
        if (!isCrossfading) {
            try { exoPlayer?.volume = 1f } catch (_: Exception) {}
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfading = false
        try { exoPlayer?.volume = 1f } catch (_: Exception) {}
        try {
            crossfadePlayer?.pause()
            crossfadePlayer?.clearMediaItems()
            crossfadePlayer?.volume = 0f
        } catch (_: Exception) {}
    }

    private fun isPlayerReleased(player: ExoPlayer): Boolean {
        return try {
            // Попытка доступа к состоянию релизнутого плеера кинет exception
            player.playbackState
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun createMediaItem(song: Song): MediaItem {
        // Скачанный трек играем из локального файла — без сети и без трафика.
        val localFile = downloadStore.getLocalFile(song.id)
        val streamUrl = if (localFile != null) {
            android.net.Uri.fromFile(localFile).toString()
        } else {
            repository.getStreamUrl(song.id)
        }
        if (streamUrl.isBlank()) {
            // Пустой URL = кэш учётных данных ещё не прогрет (холодный старт). Греем его прямо
            // сейчас: иначе ExoPlayer получил бы пустой Uri и молча «вечно грузил».
            Log.e("PlayerManager", "Blank stream url for song=${song.id}: credentials cache not ready, warming up")
            scope.launch { runCatching { repository.refreshCredentialsCache() } }
        }
        // FIX: нотификация 200px достаточно, было 500px -> экономия 2.5x трафика на каждый трек в очереди
        val coverUrl = try {
            repository.getCoverArtUrl(song.coverArt, 200)
        } catch (_: Exception) { null }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist ?: "Unknown")
            .setAlbumTitle(song.album)
            .setArtworkUri(coverUrl?.let {
                try { Uri.parse(it) } catch (_: Exception) { null }
            })
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun checkAutoDjQueue(currentSong: Song?) {
        if (!_playerState.value.autoDjEnabled || currentSong == null) return
        val state = _playerState.value
        val remaining = state.queue.size - state.currentIndex - 1
        if (remaining <= 2) {
            autoDjJob?.cancel()
            autoDjJob = scope.launch {
                delay(500)
                addAutoDjTracks(currentSong)
            }
        }
    }

    private suspend fun addAutoDjTracks(basedOn: Song) {
        try {
            val similarResult = when (_playerState.value.radioMode) {
                RadioMode.TRACK -> repository.getSimilarSongs(basedOn.id, 10)
                RadioMode.ARTIST -> repository.getTopSongsForArtist(basedOn.artist ?: "", 10)
                else -> repository.getSimilarSongs(basedOn.id, 10)
            }
            val similar = similarResult.getOrNull() ?: return
            val filtered = similar.filterNot { s -> dislikedIds.contains(s.id) || _playerState.value.queue.any { it.id == s.id } }
            if (filtered.isNotEmpty()) {
                filtered.take(5).forEach { song ->
                    addToQueue(song)
                }
            } else {
                // Fallback to random
                val random = repository.getRandomSongs(10).getOrNull() ?: emptyList()
                random.filterNot { dislikedIds.contains(it.id) }.take(3).forEach { addToQueue(it) }
            }
        } catch (_: Exception) {}
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0, autoDj: Boolean = _playerState.value.autoDjEnabled, radioMode: RadioMode = RadioMode.OFF, radioSource: String? = null) {
        if (songs.isEmpty()) return
        cancelCrossfade()
        retryJob?.cancel()
        isRecovering = false
        stallRecoveryAttempts = 0
        resetStallDetection()
        scrobbled50SongId = null
        scrobbledEndedSongId = null
        // Сервис ОБЯЗАН быть запущен: без него нет MediaSession → нет уведомления-плеера
        // и фонового режима (см. ensurePlaybackServiceStarted).
        ensurePlaybackServiceStarted()
        val player = try {
            getPlayer()
        } catch (e: Exception) {
            exoPlayer = null
            getPlayer()
        }
        try {
            synchronized(originalQueue) {
                originalQueue.clear()
                originalQueue.addAll(songs)
            }
        } catch (_: Exception) {
            synchronized(originalQueue) {
                originalQueue.clear()
                originalQueue.addAll(songs.take(50))
            }
        }
        val clickedSong = songs.getOrNull(startIndex)
        val filteredQueue = if (skipDislikedEnabled) songs.filterNot { dislikedIds.contains(it.id) } else songs
        // Если кликнутый трек сам disliked — не фильтруем, чтобы пользователь мог его проиграть по явному клику
        val useFiltered = skipDislikedEnabled && filteredQueue.isNotEmpty() && (clickedSong == null || !dislikedIds.contains(clickedSong.id))
        val queueToPlay = if (useFiltered) {
            if (_playerState.value.shuffleEnabled) filteredQueue.shuffled() else filteredQueue
        } else {
            if (_playerState.value.shuffleEnabled) songs.shuffled() else songs
        }

        val actualStartIndex = if (clickedSong != null) {
            val idx = queueToPlay.indexOfFirst { it.id == clickedSong.id }
            if (idx >= 0) idx else startIndex.coerceIn(0, queueToPlay.size - 1)
        } else {
            startIndex.coerceIn(0, queueToPlay.size - 1)
        }

        try {
            val mediaItems = queueToPlay.map { song ->
                try { createMediaItem(song) } catch (_: Exception) {
                    MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                }
            }
            userWantsPlayback = true
            stallRecoveryAttempts = 0
            player.setMediaItems(mediaItems, actualStartIndex, 0)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            // Fallback без метаданных если крашится из-за artwork
            try {
                val fallbackItems = queueToPlay.map { song ->
                    MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                }
                player.setMediaItems(fallbackItems, actualStartIndex, 0)
                player.prepare()
                player.play()
            } catch (_: Exception) {}
        }
        _playerState.update {
            it.copy(
                queue = queueToPlay,
                currentSong = queueToPlay.getOrNull(actualStartIndex),
                currentIndex = actualStartIndex,
                isPlaying = true,
                autoDjEnabled = autoDj,
                radioMode = radioMode,
                radioSource = radioSource
            )
        }
        startProgressUpdates()
        // Сохраняем очередь на сервер сразу
        saveQueueToServerDebounced(position = 0L, immediate = true)
    }

    // Radio по треку
    fun startTrackRadio(song: Song) {
        scope.launch {
            val similar = repository.getSimilarSongs(song.id, 30).getOrNull() ?: repository.getRandomSongs(30).getOrNull() ?: emptyList()
            val filtered = similar.filterNot { dislikedIds.contains(it.id) }
            val queue = listOf(song) + filtered
            playSongs(queue, 0, autoDj = true, radioMode = RadioMode.TRACK, radioSource = song.id)
        }
    }

    // Radio по исполнителю
    fun startArtistRadio(artistName: String, artistId: String? = null) {
        scope.launch {
            val songs = repository.getTopSongsForArtist(artistName, 30).getOrNull() ?: repository.getRandomSongs(30).getOrNull() ?: emptyList()
            val filtered = songs.filterNot { dislikedIds.contains(it.id) }
            if (filtered.isNotEmpty()) {
                playSongs(filtered, 0, autoDj = true, radioMode = RadioMode.ARTIST, radioSource = artistName)
            }
        }
    }

    fun toggleAutoDj() {
        val enabled = !_playerState.value.autoDjEnabled
        _playerState.update { it.copy(autoDjEnabled = enabled) }
        if (enabled) {
            _playerState.value.currentSong?.let { current ->
                scope.launch { addAutoDjTracks(current) }
            }
        }
    }

    fun playNext() {
        cancelCrossfade()
        try { getPlayer().seekToNextMediaItem() } catch (_: Exception) {}
        saveQueueToServerDebounced()
    }
    fun playPrevious() {
        cancelCrossfade()
        try {
            val player = getPlayer()
            if (player.currentPosition > 3000) player.seekTo(0) else player.seekToPreviousMediaItem()
        } catch (_: Exception) {}
        saveQueueToServerDebounced()
    }
    fun togglePlayPause() {
        ensurePlaybackServiceStarted()
        try {
            val p = getPlayer()
            if (p.isPlaying) {
                userWantsPlayback = false
                p.pause()
                crossfadePlayer?.pause()
            } else {
                userWantsPlayback = true
                if (p.playbackState == Player.STATE_IDLE) {
                    try { p.prepare() } catch (_: Exception) {}
                }
                p.play()
                if (isCrossfading) crossfadePlayer?.play()
                resetStallDetection()
            }
        } catch (_: Exception) {}
    }
    fun seekTo(position: Long) {
        cancelCrossfade()
        try {
            getPlayer().seekTo(position)
            val dur = _playerState.value.duration
            val progress = if (dur > 0) position.toFloat() / dur else 0f
            _playerState.update { it.copy(currentPosition = position, progress = progress) }
            _fullPlayerProgress.value = progress
            _fullPlayerPosition.value = position
            savePositionToServerIfNeeded(position)
        } catch (_: Exception) {}
    }
    fun toggleShuffle() {
        try {
            val enabled = !_playerState.value.shuffleEnabled
            _playerState.update { it.copy(shuffleEnabled = enabled) }
            if (_playerState.value.queue.isNotEmpty() && originalQueue.isNotEmpty()) {
                val currentSong = _playerState.value.currentSong
                val newQueue = if (enabled) {
                    val shuffled = originalQueue.shuffled().toMutableList()
                    currentSong?.let { shuffled.remove(it); shuffled.add(0, it) }
                    shuffled
                } else originalQueue.toList()
                val newIndex = currentSong?.let { newQueue.indexOf(it) } ?: 0
                val player = getPlayer()
                val mediaItems = newQueue.map { song ->
                    try { createMediaItem(song) } catch (_: Exception) {
                        MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                    }
                }
                player.setMediaItems(mediaItems, newIndex, player.currentPosition.coerceAtLeast(0L))
                player.prepare()
                _playerState.update { it.copy(queue = newQueue, currentIndex = newIndex) }
                saveQueueToServerDebounced()
            }
        } catch (_: Exception) {}
    }
    fun toggleRepeat() {
        try {
            val player = getPlayer()
            val newMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            player.repeatMode = newMode
            _playerState.update { it.copy(repeatMode = newMode) }
        } catch (_: Exception) {}
    }

    // Queue management - с защитой от крашей при быстром переключении треков + синхронизация с сервером
    fun addToQueue(song: Song) {
        try {
            val currentQueue = _playerState.value.queue.toMutableList()
            currentQueue.add(song)
            synchronized(originalQueue) { originalQueue.add(song) }
            getPlayer().addMediaItem(createMediaItem(song))
            _playerState.update { it.copy(queue = currentQueue) }
            saveQueueToServerDebounced()
        } catch (_: Exception) {}
    }

    fun addNext(song: Song) {
        try {
            val state = _playerState.value
            val currentQueue = state.queue.toMutableList()
            val insertIndex = (state.currentIndex + 1).coerceAtMost(currentQueue.size)
            currentQueue.add(insertIndex, song)
            synchronized(originalQueue) { originalQueue.add(insertIndex.coerceAtMost(originalQueue.size), song) }
            val mediaItem = createMediaItem(song)
            getPlayer().addMediaItem(insertIndex, mediaItem)
            _playerState.update { it.copy(queue = currentQueue) }
            saveQueueToServerDebounced()
        } catch (_: Exception) {}
    }

    fun addSongsToQueue(songs: List<Song>) {
        try {
            songs.forEach { song ->
                val currentQueue = _playerState.value.queue.toMutableList()
                currentQueue.add(song)
                synchronized(originalQueue) { originalQueue.add(song) }
                getPlayer().addMediaItem(createMediaItem(song))
                _playerState.update { it.copy(queue = currentQueue) }
            }
            saveQueueToServerDebounced()
        } catch (_: Exception) {
            songs.forEach { addToQueue(it) }
        }
    }

    fun removeFromQueue(index: Int) {
        try {
            if (index < 0 || index >= _playerState.value.queue.size) return
            if (index == _playerState.value.currentIndex) {
                playNext()
                return
            }
            val currentQueue = _playerState.value.queue.toMutableList()
            currentQueue.removeAt(index)
            synchronized(originalQueue) {
                if (index < originalQueue.size) originalQueue.removeAt(index)
            }
            getPlayer().removeMediaItem(index)
            val newIndex = when {
                index < _playerState.value.currentIndex -> _playerState.value.currentIndex - 1
                else -> _playerState.value.currentIndex
            }
            _playerState.update { it.copy(queue = currentQueue, currentIndex = newIndex) }
            saveQueueToServerDebounced()
        } catch (_: Exception) {}
    }

    fun moveQueueItem(from: Int, to: Int) {
        try {
            if (from == to) return
            if (from < 0 || from >= _playerState.value.queue.size) return
            if (to < 0 || to >= _playerState.value.queue.size) return
            val currentQueue = _playerState.value.queue.toMutableList()
            val song = currentQueue.removeAt(from)
            currentQueue.add(to, song)
            getPlayer().moveMediaItem(from, to)
            var newCurrentIndex = _playerState.value.currentIndex
            if (from == newCurrentIndex) newCurrentIndex = to
            else if (from < newCurrentIndex && to >= newCurrentIndex) newCurrentIndex--
            else if (from > newCurrentIndex && to <= newCurrentIndex) newCurrentIndex++
            _playerState.update { it.copy(queue = currentQueue, currentIndex = newCurrentIndex) }
            saveQueueToServerDebounced()
        } catch (_: Exception) {}
    }

    fun clearQueue() {
        try {
            val state = _playerState.value
            val currentSong = state.currentSong
            val currentIndex = state.currentIndex
            if (currentSong != null && currentIndex >= 0) {
                val newQueue = listOf(currentSong)
                synchronized(originalQueue) {
                    originalQueue.clear()
                    originalQueue.add(currentSong)
                }
                val player = getPlayer()
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                player.setMediaItem(createMediaItem(currentSong))
                player.seekTo(currentPos)
                _playerState.update { it.copy(queue = newQueue, currentIndex = 0) }
            } else {
                getPlayer().clearMediaItems()
                synchronized(originalQueue) { originalQueue.clear() }
                _playerState.update { it.copy(queue = emptyList(), currentIndex = -1, currentSong = null) }
            }
            saveQueueToServerDebounced(immediate = true)
        } catch (_: Exception) {}
    }

    fun playQueueIndex(index: Int) {
        try {
            if (index < 0 || index >= _playerState.value.queue.size) return
            getPlayer().seekTo(index, 0)
            saveQueueToServerDebounced(position = 0L)
        } catch (_: Exception) {}
    }

    fun updateProgress(position: Long, duration: Long) {
        try {
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            _playerState.update { it.copy(currentPosition = position, duration = duration, progress = progress) }
        } catch (_: Exception) {}
    }

    // ==================== SLEEP TIMER - красивый таймер сна до 2 часов ====================
    fun setSleepTimer(minutes: Int) {
        val clamped = minutes.coerceIn(1, 120)
        setSleepTimerDuration(clamped * 60 * 1000L)
    }

    fun setSleepTimerDuration(durationMillis: Long) {
        val clamped = durationMillis.coerceIn(60_000L, 120 * 60 * 1000L) // 1 мин - 2 часа
        val endTime = System.currentTimeMillis() + clamped

        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState.Active(
            endTimeMillis = endTime,
            remainingMillis = clamped,
            totalMillis = clamped
        )

        sleepTimerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remaining = endTime - now
                if (remaining <= 0) {
                    // Время вышло - плавно выключаем
                    try {
                        val player = exoPlayer
                        if (player != null && !isPlayerReleased(player)) {
                            // Fade out 2 сек
                            val startVolume = player.volume
                            for (i in 10 downTo 0) {
                                try {
                                    player.volume = startVolume * (i / 10f)
                                } catch (_: Exception) {}
                                delay(200)
                            }
                            try {
                                userWantsPlayback = false
                                player.pause()
                                player.volume = startVolume
                            } catch (_: Exception) {}
                        } else {
                            try {
                                userWantsPlayback = false
                                getPlayer().pause()
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    _sleepTimerState.value = SleepTimerState.Off
                    break
                } else {
                    _sleepTimerState.value = SleepTimerState.Active(
                        endTimeMillis = endTime,
                        remainingMillis = remaining,
                        totalMillis = clamped
                    )
                    delay(1000)
                }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
    }

    fun extendSleepTimer(extraMinutes: Int) {
        val current = _sleepTimerState.value
        if (current is SleepTimerState.Active) {
            val newTotal = (current.totalMillis + extraMinutes * 60 * 1000L).coerceAtMost(120 * 60 * 1000L)
            val newRemaining = current.remainingMillis + extraMinutes * 60 * 1000L
            val newEnd = System.currentTimeMillis() + newRemaining
            setSleepTimerDuration(newRemaining.coerceAtMost(120 * 60 * 1000L))
        }
    }

    fun release() {
        try {
            saveQueueToServerNow()
        } catch (_: Exception) {}
        try {
            progressJob?.cancel()
            autoDjJob?.cancel()
            sleepTimerJob?.cancel()
            crossfadeJob?.cancel()
            saveQueueJob?.cancel()
            retryJob?.cancel()
            exoPlayer?.release()
            crossfadePlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null
        crossfadePlayer = null
        isCrossfading = false
        isRecovering = false
    }
}
