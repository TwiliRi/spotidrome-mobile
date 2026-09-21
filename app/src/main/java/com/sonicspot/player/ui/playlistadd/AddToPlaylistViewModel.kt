package com.sonicspot.player.ui.playlistadd

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** Одна строка списка выбора плейлиста в шторке «Добавить в плейлист». */
@Immutable
data class PlaylistPickItem(
    val id: String,
    val name: String,
    val songCount: Int,
    val isPublic: Boolean,
    val coverArt: String?,
    /** Трек уже лежит в этом плейлисте — рисуем зелёную галочку, повторный тап убирает. */
    val containsTrack: Boolean = false,
    /** Состав плейлиста уже проверен; пока false — в строке крутится маленький индикатор. */
    val membershipKnown: Boolean = false,
    /** Прямо сейчас идёт добавление/удаление — строка заблокирована. */
    val busy: Boolean = false
)

@Immutable
data class AddToPlaylistUiState(
    val isOpen: Boolean = false,
    /** Трек, для которого открыта шторка (показывается в шапке). */
    val song: Song? = null,
    val items: List<PlaylistPickItem> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Всплывающее сообщение: «Добавлено в …», «Удалено из …», текст ошибки. */
    val message: String? = null,
    val isCreating: Boolean = false,
    /** Растёт после каждого успешного изменения — экраны по нему перечитывают свои данные. */
    val changeToken: Int = 0
) {
    /**
     * Список для показа: сначала личные плейлисты, затем общие, внутри групп — порядок сервера
     * (sortedBy стабильна). Своё обычно нужнее, чем общесерверное, поэтому и выше.
     */
    val visibleItems: List<PlaylistPickItem>
        get() {
            val filtered = if (query.isBlank()) items
            else items.filter { it.name.contains(query.trim(), ignoreCase = true) }
            return filtered.sortedBy { if (it.isPublic) 1 else 0 }
        }

    /** Личные плейлисты (без флага «Общий») и общие — для заголовков секций в шторке. */
    val personalItems: List<PlaylistPickItem> get() = visibleItems.filter { !it.isPublic }
    val publicItems: List<PlaylistPickItem> get() = visibleItems.filter { it.isPublic }

    /** Сколько плейлистов ещё проверяется — для подписи под заголовком. */
    val pendingChecks: Int get() = items.count { !it.membershipKnown }
}

/**
 * Логика шторки «Добавить в плейлист» — одна на все экраны.
 *
 * Шторка открывается из меню трека (альбом, плейлист, избранное, поиск, главная, плеер, исполнитель):
 * экран передаёт трек в [open], а UI рисует [com.sonicspot.player.ui.components.AddToPlaylistHost].
 *
 * Две особенности, из-за которых это отдельная вью-модель, а не пара вызовов репозитория:
 *  1. Принадлежность трека плейлистам неизвестна сразу — Subsonic отдаёт состав только в
 *     getPlaylist.view, по запросу на плейлист. Поэтому список показывается сразу (галочки
 *     доезжают по мере проверки, не больше [SCAN_PARALLELISM] запросов одновременно), а тап по
 *     ещё не проверенной строке сначала уточняет её состояние, чтобы не создать дубль трека.
 *  2. Добавление и удаление применяются оптимистично: галочка переключается мгновенно, а если
 *     сервер отказал — состояние откатывается и пользователь видит сообщение.
 */
@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddToPlaylistUiState())
    val uiState: StateFlow<AddToPlaylistUiState> = _uiState.asStateFlow()

    /** Проверка состава плейлистов — перезапускается при каждом открытии шторки. */
    private var scanJob: Job? = null
    private var actionJob: Job? = null

    fun open(song: Song) {
        scanJob?.cancel()
        actionJob?.cancel()
        _uiState.value = AddToPlaylistUiState(isOpen = true, song = song, isLoading = true)
        loadPlaylists(song)
    }

    fun close() {
        scanJob?.cancel()
        actionJob?.cancel()
        _uiState.value = AddToPlaylistUiState()
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun coverUrl(coverArt: String?, size: Int = 112): String? = repository.getCoverArtUrl(coverArt, size)

    // ---------- загрузка списка и проверка принадлежности ----------

    private fun loadPlaylists(song: Song) {
        viewModelScope.launch {
            val playlists = repository.getPlaylists().getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Не удалось загрузить плейлисты") }
                return@launch
            }
            // Системный плейлист исключённых треков — не место для ручного добавления:
            // им управляет логика дизлайков, трек там появится сам.
            val visible = playlists.filter { it.name != DislikedRepository.EXCLUDED_PLAYLIST_NAME }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    items = visible.map { playlist ->
                        PlaylistPickItem(
                            id = playlist.id,
                            name = playlist.name,
                            songCount = playlist.songCount,
                            isPublic = playlist.public,
                            coverArt = playlist.coverArt
                        )
                    }
                )
            }
            checkMembership(visible.map { it.id }, song.id)
        }
    }

    /** Параллельно (но с ограничением) читает состав плейлистов и проставляет галочки. */
    private fun checkMembership(playlistIds: List<String>, songId: String) {
        scanJob = viewModelScope.launch {
            val semaphore = Semaphore(SCAN_PARALLELISM)
            playlistIds.forEach { playlistId ->
                launch {
                    semaphore.withPermit {
                        val contains = repository.getPlaylist(playlistId)
                            .map { detail -> detail.entry.any { it.id == songId } }
                            .getOrNull()
                        // null — прочитать не удалось: снимаем индикатор, но галочку не выдумываем.
                        _uiState.update { current ->
                            current.copy(
                                items = current.items.map { item ->
                                    if (item.id == playlistId) {
                                        item.copy(containsTrack = contains == true, membershipKnown = contains != null)
                                    } else item
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- добавление и удаление ----------

    /**
     * Тап по плейлисту: трека нет — добавляем, трек уже есть — убираем.
     * Состояние строки переключается сразу, сервер догоняет; при ошибке переключаем обратно.
     */
    fun toggle(item: PlaylistPickItem) {
        val song = _uiState.value.song ?: return
        if (item.busy) return
        actionJob = viewModelScope.launch {
            // Ещё не проверяли этот плейлист — уточняем, чтобы не положить второй экземпляр трека.
            var known = item.membershipKnown
            var contains = item.containsTrack
            if (!known) {
                setBusy(item.id, true)
                val detail = repository.getPlaylist(item.id, forceRefresh = true).getOrNull()
                setBusy(item.id, false)
                if (detail == null) {
                    _uiState.update { it.copy(message = "Не удалось проверить «${item.name}»") }
                    return@launch
                }
                contains = detail.entry.any { it.id == song.id }
                known = true
                _uiState.update { current ->
                    current.copy(items = current.items.map { if (it.id == item.id) it.copy(containsTrack = contains, membershipKnown = true) else it })
                }
            }

            setBusy(item.id, true)
            if (contains) {
                setContains(item.id, false) // оптимистично снимаем галочку
                val result = repository.removeSongFromPlaylist(item.id, song.id)
                setBusy(item.id, false)
                result.onSuccess {
                    announce("Удалено из «${item.name}»")
                    bumpChange()
                }.onFailure { error ->
                    setContains(item.id, true)
                    _uiState.update { it.copy(message = error.message ?: "Не удалось удалить из «${item.name}»") }
                }
            } else {
                setContains(item.id, true) // оптимистично ставим галочку
                val result = repository.addToPlaylist(item.id, song.id)
                setBusy(item.id, false)
                result.onSuccess {
                    announce("Добавлено в «${item.name}»")
                    bumpChange()
                }.onFailure { error ->
                    setContains(item.id, false)
                    _uiState.update { it.copy(message = error.message ?: "Не удалось добавить в «${item.name}»") }
                }
            }
        }
    }

    /** «Новый плейлист»: создаём плейлист, кладём в него трек и показываем его отмеченным. */
    fun createAndAdd(name: String, isPublic: Boolean) {
        val song = _uiState.value.song ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true) }
            val result = repository.createPlaylistWithSong(trimmed, song.id, isPublic)
            _uiState.update { it.copy(isCreating = false) }
            result.onSuccess { created ->
                _uiState.update { current ->
                    current.copy(
                        items = listOf(
                            PlaylistPickItem(
                                id = created.id,
                                name = created.name,
                                songCount = created.songCount.coerceAtLeast(1),
                                isPublic = created.public,
                                coverArt = created.coverArt,
                                containsTrack = true,
                                membershipKnown = true
                            )
                        ) + current.items,
                        message = "Создан плейлист «${created.name}»"
                    )
                }
                bumpChange()
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "Не удалось создать плейлист") }
            }
        }
    }

    /** Сообщение показывается поверх шторки и гасится хостом после показа. */
    fun announce(text: String) = _uiState.update { it.copy(message = text) }

    private fun setBusy(playlistId: String, busy: Boolean) {
        _uiState.update { current ->
            current.copy(items = current.items.map { if (it.id == playlistId) it.copy(busy = busy) else it })
        }
    }

    private fun setContains(playlistId: String, contains: Boolean) {
        _uiState.update { current ->
            current.copy(items = current.items.map { if (it.id == playlistId) it.copy(containsTrack = contains) else it })
        }
    }

    private fun bumpChange() {
        _uiState.update { it.copy(changeToken = it.changeToken + 1) }
    }

    private companion object {
        /**
         * Сколько составов плейлистов читаем одновременно. Больше — быстрее, но Navidrome на
         * слабом железе начинает заметно тормозить на параллельных getPlaylist.view.
         */
        const val SCAN_PARALLELISM = 4
    }
}
