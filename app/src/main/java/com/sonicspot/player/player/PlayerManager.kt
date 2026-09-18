package com.sonicspot.player.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.player.AudioCacheManager
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import okhttp3.OkHttpClient
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
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val prefs: PreferencesManager,
    private val audioCacheManager: AudioCacheManager,
    private val okHttpClient: OkHttpClient
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

    // FIX: freeze protection
    private var consecutiveErrors: Int = 0
    private var lastPositionForStall: Long = -1L
    private var lastPositionTimeForStall: Long = System.currentTimeMillis()
    private var bufferingStartTime: Long = 0L
    private var retryJob: Job? = null
    private var isRecovering: Boolean = false
    private val maxRetriesPerTrack = 3
    private val bufferingTimeoutMs = 15_000L
    private val stallTimeoutMs = 12_000L
    // Для треков которые падают в одном месте
    private var lastErrorPosition: Long = -1L
    private var samePositionErrorCount: Int = 0
    private var lastErrorSongId: String? = null

    init {
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
            if (!immediate) delay(2000) // FIX: Tempus debounce 2с вместо 1с, меньше спама сети -> меньше фризов
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

    // ==================== FREEZE FIX v2 - для битых треков на одной секунде ====================
    private fun handlePlayerError(error: PlaybackException?) {
        if (isRecovering) return
        val code = error?.errorCode ?: -1
        val msg = error?.message ?: "unknown"
        val errorName = error?.errorCodeName ?: "UNKNOWN"
        val currentSongId = _playerState.value.currentSong?.id
        val currentPos = try { exoPlayer?.currentPosition ?: -1L } catch (_: Exception) { -1L }

        Log.e("PlayerManager", "onPlayerError code=$code name=$errorName msg=$msg song=$currentSongId pos=$currentPos retries=$consecutiveErrors samePosCount=$samePositionErrorCount lastErrPos=$lastErrorPosition")

        // Детекция битого места: если ошибка в том же треке на той же секунде (±2с)
        if (currentSongId != null && currentSongId == lastErrorSongId && currentPos >= 0 && lastErrorPosition >= 0) {
            if (kotlin.math.abs(currentPos - lastErrorPosition) < 2500L) {
                samePositionErrorCount++
                Log.w("PlayerManager", "Same position error $samePositionErrorCount at $currentPos")
            } else {
                samePositionErrorCount = 0
            }
        } else {
            samePositionErrorCount = 0
        }
        lastErrorPosition = currentPos
        lastErrorSongId = currentSongId

        consecutiveErrors++

        // Если 2 раза падает в одном месте - пробуем перепрыгнуть битый кусок +3с
        if (samePositionErrorCount >= 1 && currentPos >= 0) {
            Log.w("PlayerManager", "Trying to skip corrupted segment at $currentPos")
            skipCorruptedSegment(currentPos)
            return
        }

        // Если уже 2 раза пытались перепрыгнуть и все равно падает - скипаем трек
        if (samePositionErrorCount >= 2) {
            Log.w("PlayerManager", "Corrupted segment cannot be skipped, skipping track")
            samePositionErrorCount = 0
            consecutiveErrors = 0
            scope.launch {
                delay(300)
                skipToNextOnError()
            }
            return
        }

        if (consecutiveErrors <= maxRetriesPerTrack) {
            retryCurrentTrackWithFreshUrl(delayMs = 800L * consecutiveErrors)
        } else {
            Log.w("PlayerManager", "Max retries reached, skipping to next")
            consecutiveErrors = 0
            samePositionErrorCount = 0
            scope.launch {
                delay(300)
                skipToNextOnError()
            }
        }
    }

    private fun skipCorruptedSegment(failedPos: Long) {
        if (isRecovering) return
        isRecovering = true
        retryJob?.cancel()
        retryJob = scope.launch {
            try {
                delay(500)
                cancelCrossfade()
                try { exoPlayer?.volume = 1f } catch (_: Exception) {}
                val player = exoPlayer ?: run { isRecovering = false; return@launch }
                if (isPlayerReleased(player)) { isRecovering = false; return@launch }

                val state = _playerState.value
                val dur = state.duration
                // Прыгаем на +3.5 сек вперед чтобы перепрыгнуть битый фрейм
                val skipForward = failedPos + 3500L
                if (dur > 0 && skipForward < dur - 1000) {
                    Log.i("PlayerManager", "Skipping corrupted segment: $failedPos -> $skipForward (dur $dur)")
                    try {
                        player.seekTo(skipForward)
                        player.prepare()
                        player.play()
                        // Не сбрасываем isRecovering сразу, даем шанс
                        delay(1000)
                        // Если после скипа все еще ошибка - handlePlayerError вызовется снова и скипнет трек
                    } catch (e: Exception) {
                        Log.e("PlayerManager", "Skip segment failed ${e.message}")
                        skipToNextOnError()
                    }
                } else {
                    Log.w("PlayerManager", "Cannot skip forward, near end, skipping track")
                    skipToNextOnError()
                }
            } catch (_: Exception) {
            } finally {
                isRecovering = false
            }
        }
    }

    private fun retryCurrentTrackWithFreshUrl(delayMs: Long = 500L) {
        if (isRecovering) return
        isRecovering = true
        retryJob?.cancel()
        retryJob = scope.launch {
            try {
                delay(delayMs)
                cancelCrossfade()
                try { exoPlayer?.volume = 1f } catch (_: Exception) {}
                try { repository.refreshCredentialsCache() } catch (_: Exception) {}
                val state = _playerState.value
                val current = state.currentSong ?: run { isRecovering = false; return@launch }
                val player = exoPlayer
                if (player == null || isPlayerReleased(player)) {
                    isRecovering = false
                    return@launch
                }
                val freshItem = try { createMediaItem(current) } catch (_: Exception) {
                    MediaItem.Builder().setMediaId(current.id).setUri(repository.getStreamUrl(current.id)).build()
                }
                val pos = try { player.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }

                // Если это повторная ошибка на том же месте - пробуем +3с, иначе та же позиция
                val safePos = when {
                    samePositionErrorCount >= 1 && pos >= 0 -> {
                        val jumped = pos + 3500L
                        if (state.duration > 0 && jumped < state.duration - 1000) jumped else pos
                    }
                    pos > 0 && state.duration > 0 && pos > state.duration - 2000 -> 0L
                    else -> pos
                }

                try {
                    // Для битого файла пробуем альтернативный URL: оригинальный формат без транскода
                    // Если обычный URL падает, пробуем с format=raw
                    val useRawFormat = samePositionErrorCount >= 1 || consecutiveErrors >= 2
                    val finalItem = if (useRawFormat) {
                        try {
                            val rawUrl = repository.getStreamUrl(current.id) + "&format=raw"
                            Log.i("PlayerManager", "Trying raw format for corrupted track: $rawUrl")
                            MediaItem.Builder().setMediaId(current.id).setUri(rawUrl).setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder().setTitle(current.title).build()
                            ).build()
                        } catch (_: Exception) { freshItem }
                    } else freshItem

                    player.setMediaItem(finalItem, safePos)
                    player.prepare()
                    player.play()
                    Log.i("PlayerManager", "Retry track ${current.id} at $safePos (raw=$useRawFormat)")
                } catch (e: Exception) {
                    Log.e("PlayerManager", "Retry failed ${e.message}")
                    skipToNextOnError()
                }
            } catch (_: Exception) {
            } finally {
                isRecovering = false
            }
        }
    }

    private fun skipToNextOnError() {
        scope.launch {
            try {
                val state = _playerState.value
                if (state.currentIndex + 1 < state.queue.size) {
                    Log.i("PlayerManager", "Skipping to next due to error")
                    cancelCrossfade()
                    try { exoPlayer?.volume = 1f } catch (_: Exception) {}
                    samePositionErrorCount = 0
                    lastErrorPosition = -1L
                    getPlayer().seekToNextMediaItem()
                } else {
                    Log.i("PlayerManager", "Last track error, retry from 0")
                    samePositionErrorCount = 0
                    lastErrorPosition = -1L
                    // Последняя попытка с 0 и raw форматом
                    val current = state.currentSong
                    if (current != null) {
                        try {
                            val player = getPlayer()
                            val rawUrl = repository.getStreamUrl(current.id) + "&format=raw"
                            player.setMediaItem(MediaItem.Builder().setMediaId(current.id).setUri(rawUrl).build(), 0L)
                            player.prepare()
                            player.play()
                        } catch (_: Exception) {
                            retryCurrentTrackWithFreshUrl(300L)
                        }
                    } else {
                        retryCurrentTrackWithFreshUrl(300L)
                    }
                }
            } catch (_: Exception) {
                try {
                    val state = _playerState.value
                    if (state.queue.isNotEmpty()) {
                        playSongs(state.queue, state.currentIndex.coerceAtLeast(0))
                    }
                } catch (_: Exception) {}
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
                        // FIX: createMediaItems на Main для 50 треков -> фриз 100ms+
                        // Было: map с Uri.parse на Main
                        // Стало: создаем в IO, setMediaItems на Main
                        val mediaItems = withContext(Dispatchers.IO) {
                            PerformanceTracer.start("createMediaItems_${songs.size}")
                            val items = songs.map { song ->
                                try { createMediaItem(song) } catch (_: Exception) {
                                    MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                                }
                            }
                            val createMs = PerformanceTracer.end("createMediaItems_${songs.size}")
                            if (createMs > 100) {
                                PerformanceTracer.log("createMediaItems", "SLOW ${createMs}ms for ${songs.size} items")
                            }
                            items
                        }
                        withContext(Dispatchers.Main) {
                            val player = try { getPlayer() } catch (_: Exception) { null }
                            if (player != null) {
                                try {
                                    synchronized(originalQueue) {
                                        originalQueue.clear()
                                        originalQueue.addAll(songs)
                                    }
                                    PerformanceTracer.start("setMediaItems_${songs.size}")
                                    player.setMediaItems(mediaItems, startIndex, position.coerceAtLeast(0L))
                                    val setMs = PerformanceTracer.end("setMediaItems_${songs.size}")

                                    if (autoPlay) {
                                        PerformanceTracer.start("prepare_${songs.size}")
                                        player.prepare()
                                        val prepMs = PerformanceTracer.end("prepare_${songs.size}")
                                        PerformanceTracer.log("prepare", "autoPlay=true ${songs.size} tracks ${prepMs}ms")
                                        if (prepMs > 500 && songs.size > 20) {
                                            PerformanceTracer.log("prepare", "🔴 prepare SLOW ${prepMs}ms for ${songs.size} tracks")
                                        }
                                        player.play()
                                    } else {
                                        PerformanceTracer.log("restoreQueue", "Skipping prepare() for autoPlay=false - FIX FOR 2-3 MIN LAG! setMediaItems=${setMs}ms")
                                    }
                                    // Если не автоплей - не вызываем prepare, только seek, подготовка будет при первом play
                                    _playerState.update {
                                        it.copy(
                                            queue = songs,
                                            currentSong = songs.getOrNull(startIndex),
                                            currentIndex = startIndex,
                                            currentPosition = position,
                                            isPlaying = autoPlay
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

    fun getPlayer(): ExoPlayer {
        val current = exoPlayer
        if (current == null || isPlayerReleased(current)) {
            exoPlayer = null
        }
        if (exoPlayer == null) {
            // FIX: Tempus-inspired optimized LoadControl + audio cache
            // Было: DefaultLoadControl default (min 50s, max 50s, playback 2.5s) -> много памяти + медленный старт
            // Стало: Tempus style 20s min, 60s max, 2s playback, 5s after rebuffer -> быстрый старт + стабильность
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    20_000, // minBuffer 20s - предотвращает drip-feeding
                    60_000, // maxBuffer 60s - стабильно на WiFi
                    2_000,  // bufferForPlayback 2s - быстрый старт
                    5_000   // bufferForPlaybackAfterRebuffer 5s
                )
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // Audio cache как в Tempus: SimpleCache 256MB LRU
            val cache = audioCacheManager.getCache()
            val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(okHttpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(cacheDataSourceFactory)

            exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _playerState.update { it.copy(isPlaying = isPlaying) }
                            if (isPlaying) {
                                consecutiveErrors = 0
                                resetStallDetection()
                                startProgressUpdates()
                            } else {
                                if (isCrossfading) {
                                    crossfadePlayer?.pause()
                                }
                            }
                        }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val index = currentMediaItemIndex
                            _fullPlayerPosition.value = 0L
                            _fullPlayerProgress.value = 0f
                            consecutiveErrors = 0
                            samePositionErrorCount = 0
                            lastErrorPosition = -1L
                            lastErrorSongId = null
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
                                    scope.launch {
                                        delay(100)
                                        try { seekToNextMediaItem() } catch (_: Exception) {}
                                    }
                                } else {
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
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    _fullPlayerPosition.value = try { currentPosition } catch (_: Exception) { 0L }
                                    bufferingStartTime = 0L
                                    consecutiveErrors = 0
                                    startProgressUpdates()
                                }
                                Player.STATE_BUFFERING -> {
                                    if (bufferingStartTime == 0L) bufferingStartTime = System.currentTimeMillis()
                                }
                                Player.STATE_IDLE -> {
                                    bufferingStartTime = 0L
                                    // Если IDLE во время попытки играть - пробуем восстановить
                                    if (_playerState.value.isPlaying && !isRecovering) {
                                        Log.w("PlayerManager", "Player went IDLE while playing, trying recover")
                                        retryCurrentTrackWithFreshUrl(500L)
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
            var lastFullUpdate = 0L
            var lastPosForFull = -1L
            while (isActive) {
                try {
                    val player = exoPlayer ?: break
                    if (isPlayerReleased(player)) break
                    val pos = try { player.currentPosition } catch (_: Exception) { 0L }
                    val dur = try { player.duration.coerceAtLeast(0L) } catch (_: Exception) { 0L }
                    val progress = if (dur > 0) pos.toFloat() / dur else 0f
                    val now = System.currentTimeMillis()
                    // FIX: throttle full player to 200ms and only if position changed >200ms or progress >0.005
                    // Было: 50ms -> 20 рекомпозиций/сек FullPlayer + lyrics binary search каждую 50ms -> лаг
                    // Стало: 200ms -> 5 рекомпозиций/сек, достаточно для слайдера и текста
                    if (now - lastFullUpdate > 200 && kotlin.math.abs(pos - lastPosForFull) > 150) {
                        _fullPlayerPosition.value = pos
                        // distinct until changed 0.005 to avoid slider jitter
                        if (kotlin.math.abs(progress - _fullPlayerProgress.value) > 0.003f) {
                            _fullPlayerProgress.value = progress
                        }
                        lastFullUpdate = now
                        lastPosForFull = pos
                    }
                    if (now - lastStateUpdate > 500) {
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

                    // === FREEZE PROTECTION ===
                    val playbackState = try { player.playbackState } catch (_: Exception) { Player.STATE_IDLE }
                    val isPlaying = try { player.isPlaying } catch (_: Exception) { false }
                    val playWhenReady = try { player.playWhenReady } catch (_: Exception) { false }

                    // Buffering timeout
                    if (playbackState == Player.STATE_BUFFERING && playWhenReady) {
                        if (bufferingStartTime == 0L) bufferingStartTime = now
                        else if (now - bufferingStartTime > bufferingTimeoutMs && !isRecovering) {
                            Log.w("PlayerManager", "Buffering timeout ${bufferingTimeoutMs}ms, recovering")
                            bufferingStartTime = 0L
                            handlePlayerError(null)
                        }
                    } else if (playbackState == Player.STATE_READY) {
                        if (bufferingStartTime != 0L) bufferingStartTime = 0L
                    }

                    // Stall detection: position не двигается хотя должен играть - часто битый файл
                    if (isPlaying && playbackState == Player.STATE_READY && dur > 0 && pos > 0) {
                        if (lastPositionForStall == pos) {
                            if (now - lastPositionTimeForStall > stallTimeoutMs && !isRecovering) {
                                Log.w("PlayerManager", "Stall detected pos=$pos stuck for ${stallTimeoutMs}ms")
                                resetStallDetection()
                                // Если сталл на одном месте - пробуем перепрыгнуть
                                if (lastErrorPosition >= 0 && kotlin.math.abs(pos - lastErrorPosition) < 2500) {
                                    skipCorruptedSegment(pos)
                                } else {
                                    retryCurrentTrackWithFreshUrl(300L)
                                }
                            }
                        } else {
                            lastPositionForStall = pos
                            lastPositionTimeForStall = now
                        }
                    } else if (!isPlaying) {
                        // На паузе сбрасываем детекцию чтобы не триггерить
                        if (lastPositionForStall != -1L && now - lastPositionTimeForStall > 2000) {
                            resetStallDetection()
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

                    delay(if (isPlaying) 200 else 500)
                } catch (_: Exception) {
                    delay(300)
                }
            }
        }
    }

    private fun triggerCrossfade(remainingMs: Long) {
        if (isCrossfading) return
        if (!crossfadeEnabled) return
        val state = _playerState.value
        if (state.queue.size > 100) return // FIX: не делаем кроссфейд для огромных очередей
        val nextSong = state.queue.getOrNull(state.currentIndex + 1) ?: return
        val fadeDuration = remainingMs.coerceIn(500L, (crossfadeDurationSec * 1000L).coerceIn(1000L, 12000L))
        isCrossfading = true

        try {
            if (crossfadePlayer == null || isPlayerReleased(crossfadePlayer!!)) {
                crossfadePlayer = ExoPlayer.Builder(context).build().apply {
                    setHandleAudioBecomingNoisy(false)
                }
            }
            val cfPlayer = crossfadePlayer!!
            try { cfPlayer.clearMediaItems() } catch (_: Exception) {}
            // FIX: createMediaItem в IO чтобы не фризить
            scope.launch(Dispatchers.IO) {
                try {
                    val nextItem = try { createMediaItem(nextSong) } catch (_: Exception) {
                        MediaItem.Builder().setMediaId(nextSong.id).setUri(repository.getStreamUrl(nextSong.id)).build()
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            cfPlayer.setMediaItem(nextItem)
                            cfPlayer.prepare()
                            cfPlayer.volume = 0f
                            cfPlayer.play()
                        } catch (_: Exception) {
                            isCrossfading = false
                            return@withContext
                        }

                        crossfadeJob?.cancel()
                        crossfadeJob = scope.launch {
                            val steps = 15 // FIX: 30 -> 15 шагов, меньше нагрузки на Main
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
                                val main = exoPlayer
                                if (main != null && !isPlayerReleased(main)) {
                                    if (main.currentMediaItemIndex == state.currentIndex) {
                                        main.seekTo(state.currentIndex + 1, fadeDuration.coerceAtMost(10000L))
                                    } else {
                                        main.seekTo(fadeDuration.coerceAtMost(main.duration.coerceAtLeast(fadeDuration)))
                                    }
                                    main.volume = 1f
                                }
                                cfPlayer.pause()
                                cfPlayer.clearMediaItems()
                                cfPlayer.volume = 0f
                            } catch (_: Exception) {}
                            isCrossfading = false
                            // FIX: освобождаем кроссфейд плеер через 10с если не используется
                            scope.launch {
                                delay(10000)
                                if (!isCrossfading) {
                                    try {
                                        crossfadePlayer?.release()
                                        crossfadePlayer = null
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { isCrossfading = false }
                }
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
            .setUri(repository.getStreamUrl(song.id))
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
        consecutiveErrors = 0
        samePositionErrorCount = 0
        lastErrorPosition = -1L
        lastErrorSongId = null
        resetStallDetection()
        scrobbled50SongId = null
        scrobbledEndedSongId = null
        // Не стартуем сервис вручную - MediaSessionService сам станет foreground когда появится нотификация
        // Ручной startForegroundService вызывал ForegroundServiceDidNotStartInTimeException при быстром переключении треков
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

        // FIX: createMediaItem делает Uri.parse + getCoverArtUrl на Main -> фриз 100ms+ для 50 треков
        // Было: map на Main потоке
        // Стало: создаем fallback items мгновенно на Main, а полные с artwork в IO и обновляем потом
        try {
            // Быстрый fallback без artwork чтобы начать играть мгновенно <16ms
            val quickItems = queueToPlay.map { song ->
                MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
            }
            player.setMediaItems(quickItems, actualStartIndex, 0)
            player.prepare()
            player.play()
            // Догружаем artwork в фоне чтобы не фризить
            scope.launch(Dispatchers.IO) {
                try {
                    val fullItems = queueToPlay.map { song ->
                        try { createMediaItem(song) } catch (_: Exception) {
                            MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            val currentPos = player.currentPosition
                            val currentIdx = player.currentMediaItemIndex
                            if (currentIdx >= 0) {
                                // Обновляем метаданные без сброса позиции
                                for (i in fullItems.indices) {
                                    if (i < player.mediaItemCount) {
                                        // Media3 не позволяет обновить метаданные одного item без пересоздания,
                                        // поэтому оставляем как есть - artwork подтянется через MediaSession bitmapLoader
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
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
        try {
            val p = getPlayer()
            if (p.isPlaying) {
                p.pause()
                crossfadePlayer?.pause()
            } else {
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
        // FIX: было N раз addMediaItem + update state -> N рекомпозиций + N setCustomLayout -> фриз
        // Стало: батч добавление 1 раз
        // FIX: Tempus number_tracks_keep_in_queue - лимит очереди 200 чтобы не лагало
        try {
            if (songs.isEmpty()) return
            val currentQueue = _playerState.value.queue.toMutableList()
            // Tempus-style: если очередь >200, удаляем старые треки до текущего индекса кроме 10 предыдущих
            if (currentQueue.size > 200) {
                val currentIdx = _playerState.value.currentIndex
                val keepFrom = (currentIdx - 10).coerceAtLeast(0)
                val toRemove = keepFrom
                if (toRemove > 0) {
                    currentQueue.subList(0, toRemove).clear()
                    synchronized(originalQueue) {
                        if (originalQueue.size > toRemove) {
                            originalQueue.subList(0, toRemove).clear()
                        }
                    }
                    try {
                        val player = getPlayer()
                        if (toRemove <= player.mediaItemCount) {
                            player.removeMediaItems(0, toRemove)
                            _playerState.update { it.copy(currentIndex = (currentIdx - toRemove).coerceAtLeast(0)) }
                        }
                    } catch (_: Exception) {}
                }
            }
            currentQueue.addAll(songs)
            synchronized(originalQueue) { originalQueue.addAll(songs) }
            scope.launch(Dispatchers.IO) {
                try {
                    val mediaItems = songs.map { song ->
                        try { createMediaItem(song) } catch (_: Exception) {
                            MediaItem.Builder().setMediaId(song.id).setUri(repository.getStreamUrl(song.id)).build()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            getPlayer().addMediaItems(mediaItems)
                            _playerState.update { it.copy(queue = currentQueue) }
                            saveQueueToServerDebounced()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        songs.forEach { addToQueue(it) }
                    }
                }
            }
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
                                player.pause()
                                player.volume = startVolume
                            } catch (_: Exception) {}
                        } else {
                            try { getPlayer().pause() } catch (_: Exception) {}
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
