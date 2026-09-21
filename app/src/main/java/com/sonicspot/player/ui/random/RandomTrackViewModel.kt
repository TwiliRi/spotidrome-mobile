package com.sonicspot.player.ui.random

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Фазы броска «случайного трека» — та же машина состояний, что в десктопном Spotidrome
 * (store.rollDice → сцена «Чёрная дыра»):
 *
 *  IDLE    — дыра дышит, диск раскручивается, обложек нет;
 *  SPIN    — выброс: обложки вылетают из горизонта и раскручиваются по спирали;
 *  BALANCE — поиск затянулся: поток переходит в дрейф, дыра не гаснет;
 *  SETTLE  — разворот потока, обложки падают внутрь и вытягиваются в нити;
 *  TOP     — из горизонта поднимается обложка выпавшего трека, музыка стартует;
 *  EXPAND  — обложка улетает в полноэкранный плеер, оверлей растворяется.
 */
enum class RollPhase { IDLE, SPIN, BALANCE, SETTLE, TOP, EXPAND }

/** Длительности фаз, мс — один в один как BLACKHOLE в десктопном сторе. */
object RollTimings {
    const val INTRO = 420L          // 1. дыра проявляется, диск раскручивается
    const val MIN_SPIN = 1600L      // 2. минимум выброса: обложки летят от горизонта
    const val BALANCE_AFTER = 2600L // 3. долгий поиск → поток переходит в дрейф
    const val SETTLE = 1250L        // 4. разворот потока и всасывание
    const val CAMERA = 950L         // 5. обложка поднимается из горизонта
    const val REVEAL = 320L
    const val HOLD = 320L
    const val EXPAND = 420L         // 6. перелёт обложки в плеер
}

@Immutable
data class RandomRollState(
    /** Оверлей сцены показан. */
    val isActive: Boolean = false,
    /** Кнопка «случайный трек» в состоянии «крутится», повторный тап не нужен. */
    val isBusy: Boolean = false,
    val phase: RollPhase = RollPhase.IDLE,
    /** Выпавший трек — появляется, как только сервер ответил. */
    val track: Song? = null,
    /** Обложка выпавшего трека (большая, для «героя» сцены). */
    val coverUrl: String? = null,
    /** Обложки тронутых треков — из них сцена берёт то, что летает вокруг дыры. */
    val poolUrls: List<String> = emptyList(),
    /**
     * Счётчик-триггер «показать полноэкранный плеер». Растёт, когда трек уже играет —
     * хост слушает изменения и открывает плеер (в т.ч. при пропуске анимации).
     */
    val openPlayerToken: Int = 0,
    val error: String? = null
)

/**
 * Кнопка «случайный трек»: тянет случайные треки у Navidrome, играет первый из них
 * (сервер уже вернул случайный порядок) и ведёт оверлей по фазам анимации.
 *
 * Фазы живут здесь, а не в UI: сцена только рисует то, что ей сообщили, и ничего не знает
 * про корутины. Поэтому анимацию можно прервать в любой момент, а трек всё равно заиграет.
 */
@HiltViewModel
class RandomTrackViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val dislikedRepository: DislikedRepository,
    private val playerManager: PlayerManager,
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _state = MutableStateFlow(RandomRollState())
    val state: StateFlow<RandomRollState> = _state.asStateFlow()

    private var rollJob: Job? = null
    private var queue: List<Song> = emptyList()
    private var playbackStarted = false

    /**
     * Нажатие кнопки. Если анимация выключена в настройках — трек просто играет сразу,
     * без сцены (как rollAnim = off в десктопе).
     */
    fun roll() {
        if (_state.value.isActive) return
        rollJob?.cancel()
        queue = emptyList()
        playbackStarted = false
        rollJob = viewModelScope.launch {
            val mode = prefs.randomTrackAnimFlow.first()
            if (mode != PreferencesManager.RANDOM_ANIM_BLACKHOLE) {
                playImmediately()
                return@launch
            }
            playRoll()
        }
    }

    /** Досрочно прервать анимацию: трек всё равно включится (Esc/тап в десктопе). */
    fun skip() {
        val current = _state.value
        if (!current.isActive) return
        rollJob?.cancel()
        val next = if (queue.isNotEmpty() && !playbackStarted) {
            startPlayback(queue)
            current.openPlayerToken + 1
        } else {
            current.openPlayerToken
        }
        _state.value = RandomRollState(openPlayerToken = next)
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    /** Полный прогон по фазам (mode = blackhole). */
    private suspend fun playRoll() = coroutineScope {
        // coroutineScope даёт фазам собственный скоуп: async/launch ниже — его дети,
        // поэтому отмена броска (skip) гасит и запросы, и таймер баланса.
        _state.value = RandomRollState(isActive = true, isBusy = true, phase = RollPhase.IDLE)

        // Запрос стартует сразу: вращение живёт ровно столько, сколько идёт поиск
        val songsDeferred = async(Dispatchers.IO) { repository.getRandomSongs(POOL_SIZE).getOrDefault(emptyList()) }

        try {
            delay(RollTimings.INTRO)
            if (!_state.value.isActive) return@coroutineScope
            _state.update { it.copy(phase = RollPhase.SPIN) }

            val spinStart = System.currentTimeMillis()
            val balanceJob = launch {
                delay(RollTimings.BALANCE_AFTER)
                // поиск затянулся — поток уходит в дрейф, дыра не гаснет
                if (_state.value.phase == RollPhase.SPIN) _state.update { it.copy(phase = RollPhase.BALANCE) }
            }

            val disliked = withContext(Dispatchers.IO) { dislikedRepository.dislikedIdsFlow.first() }
            val songs = songsDeferred.await().filterNot { disliked.contains(it.id) }
            if (songs.isEmpty()) throw IllegalStateException("В библиотеке нечего играть")

            // Порядок уже случайный (getRandomSongs), поэтому играем с первого трека
            queue = songs
            val track = songs.first()
            _state.update {
                it.copy(
                    track = track,
                    coverUrl = repository.getCoverArtUrl(track.coverArt ?: track.albumId, 600),
                    poolUrls = songs.take(ART_POOL).mapNotNull { song ->
                        repository.getCoverArtUrl(song.coverArt ?: song.albumId, 240)
                    }
                )
            }

            balanceJob.cancel()
            val elapsed = System.currentTimeMillis() - spinStart
            delay((RollTimings.MIN_SPIN - elapsed).coerceAtLeast(0L))
            if (!_state.value.isActive) return@coroutineScope
            _state.update { it.copy(phase = RollPhase.SETTLE) }

            delay(RollTimings.SETTLE)
            if (!_state.value.isActive) return@coroutineScope
            _state.update { it.copy(phase = RollPhase.TOP) }

            // Музыка стартует, когда обложка пошла вверх; плеер хост откроет под оверлеем
            startPlayback(queue)
            _state.update { it.copy(openPlayerToken = it.openPlayerToken + 1) }

            delay(RollTimings.CAMERA + RollTimings.REVEAL + RollTimings.HOLD)
            if (!_state.value.isActive) return@coroutineScope
            _state.update { it.copy(phase = RollPhase.EXPAND) }

            delay(RollTimings.EXPAND)
            _state.value = RandomRollState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = RandomRollState(error = e.message ?: "Не удалось получить случайные треки")
        }
    }

    /** Анимация выключена: трек играет сразу, без сцены. */
    private suspend fun playImmediately() {
        try {
            val disliked = withContext(Dispatchers.IO) { dislikedRepository.dislikedIdsFlow.first() }
            val songs = withContext(Dispatchers.IO) {
                repository.getRandomSongs(POOL_SIZE).getOrDefault(emptyList())
            }.filterNot { disliked.contains(it.id) }
            if (songs.isEmpty()) throw IllegalStateException("В библиотеке нечего играть")
            queue = songs
            startPlayback(queue)
            _state.value = RandomRollState(openPlayerToken = _state.value.openPlayerToken + 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = RandomRollState(error = e.message ?: "Не удалось получить случайные треки")
        }
    }

    private fun startPlayback(songs: List<Song>) {
        if (songs.isEmpty()) return
        playbackStarted = true
        playerManager.playSongs(songs, 0)
    }

    private companion object {
        /** Сколько случайных треков просим у сервера — из них же сцена берёт обложки. */
        const val POOL_SIZE = 50

        /** Сколько обложек реально грузим в сцену (остальные — запас на подмену). */
        const val ART_POOL = 24
    }
}
