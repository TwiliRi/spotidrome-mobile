package com.sonicspot.player.data.local

import android.content.Context
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DownloadedEntry(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val addedAt: Long = 0L
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        album = album,
        artist = artist,
        coverArt = coverArt,
        duration = duration
    )
}

/** Активная загрузка: трек + сколько байт уже скачано. totalBytes < 0 — размер неизвестен. */
data class ActiveDownload(
    val song: Song,
    val receivedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val startedAt: Long = System.currentTimeMillis()
) {
    /** Доля скачанного 0..1 или null, если сервер не отдал размер */
    val progress: Float? get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** Скачивание отменено пользователем (крестик). */
class DownloadCancelledException : Exception("Скачивание отменено")

/** Трек в очереди скачивания — ждёт, пока освободится «качающий» слот (треки качаются по одному). */
data class DownloadQueueItem(
    val song: Song,
    val enqueuedAt: Long = System.currentTimeMillis()
)

/**
 * Хранилище скачанных треков: аудиофайлы в filesDir/downloaded + реестр метаданных
 * (index.json). Удаление записи стирает и файл — память реально освобождается.
 */
@Singleton
class DownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository
) {
    private val dir: File = File(context.filesDir, "downloaded").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val http = OkHttpClient()

    private val _downloaded = MutableStateFlow(loadIndex())
    val downloaded: StateFlow<Map<String, DownloadedEntry>> = _downloaded.asStateFlow()

    private val _activeDownloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())

    /** Текущие загрузки: id трека -> состояние (трек, прогресс в байтах). */
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = _activeDownloads.asStateFlow()

    /** Id треков, которые сейчас качаются (для старых мест использования). */
    val inProgress: StateFlow<Set<String>>
        get() = _inProgress

    private val _inProgress = MutableStateFlow<Set<String>>(emptySet())

    /** Вызовы okhttp активных загрузок — чтобы можно было отменить по крестика. */
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()

    /** Скоуп пакетных скачиваний — живёт, пока живёт процесс приложения. */
    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queue = MutableStateFlow<List<DownloadQueueItem>>(emptyList())

    /** Очередь скачивания: треки, которые ждут своей очереди (качающийся сейчас — в activeDownloads). */
    val queue: StateFlow<List<DownloadQueueItem>> = _queue.asStateFlow()

    /** Защита списка очереди: добавление/изъятие/просмотр атомарны. */
    private val queueMutex = Mutex()

    /** Гарантирует единственного обработчика очереди — треки качаются строго по одному. */
    private val processorMutex = Mutex()

    private fun loadIndex(): Map<String, DownloadedEntry> = try {
        if (!indexFile.exists()) emptyMap()
        else json.decodeFromString<Map<String, DownloadedEntry>>(indexFile.readText())
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveIndex(map: Map<String, DownloadedEntry>) {
        try {
            indexFile.writeText(json.encodeToString(map))
        } catch (_: Exception) {
        }
    }

    fun getLocalFile(songId: String): File? {
        val entry = _downloaded.value[songId] ?: return null
        val f = File(dir, entry.fileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun isDownloaded(songId: String): Boolean = getLocalFile(songId) != null

    val totalBytes: Long get() = _downloaded.value.values.sumOf { it.sizeBytes }

    /** Скачивает трек. Идемпотентно: уже скачанные пропускаются, повторный вызов догружает недостающее. */
    suspend fun download(song: Song): Result<Unit> = withContext(Dispatchers.IO) {
        if (_activeDownloads.value.containsKey(song.id) || isDownloaded(song.id)) {
            return@withContext Result.success(Unit)
        }
        putActive(ActiveDownload(song))
        try {
            val url = repository.getStreamUrl(song.id)
            if (url.isBlank()) return@withContext Result.failure(Exception("Сервер недоступен"))
            val call = http.newCall(Request.Builder().url(url).build())
            activeCalls[song.id] = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body ?: return@withContext Result.failure(Exception("Пустой ответ"))
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                val ext = when {
                    "flac" in ct -> ".flac"
                    "ogg" in ct || "opus" in ct -> ".ogg"
                    "mp4" in ct || "m4a" in ct || "aac" in ct -> ".m4a"
                    "wav" in ct -> ".wav"
                    else -> ".mp3"
                }
                val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val target = File(dir, "${song.id}$ext")
                val tmp = File(dir, "${song.id}.part")
                // Копирование чанками с обновлением прогресса (не чаще раза в 256 КБ)
                val buf = ByteArray(64 * 1024)
                var received = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            received += read
                            if (received - lastReported >= 256 * 1024L) {
                                lastReported = received
                                updateActive(song.id) { it.copy(receivedBytes = received, totalBytes = total) }
                            }
                        }
                        updateActive(song.id) { it.copy(receivedBytes = received, totalBytes = total) }
                    }
                }
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("Не удалось сохранить файл"))
                }
                // подчистить возможные старые файлы этого трека с другими расширениями
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(song.id) && f.name != target.name && f.name != "index.json") f.delete()
                }
                val entry = DownloadedEntry(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverArt = song.coverArt,
                    duration = song.duration,
                    fileName = target.name,
                    sizeBytes = target.length(),
                    addedAt = System.currentTimeMillis()
                )
                mutex.withLock {
                    val map = _downloaded.value + (song.id to entry)
                    _downloaded.value = map
                    saveIndex(map)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            File(dir, "${song.id}.part").delete()
            if (e.message?.contains("cancel", ignoreCase = true) == true) {
                Result.failure(DownloadCancelledException())
            } else {
                Result.failure(e)
            }
        } finally {
            activeCalls.remove(song.id)
            _activeDownloads.value = _activeDownloads.value - song.id
            _inProgress.value = _inProgress.value - song.id
        }
    }

    /** Отменяет скачивание трека: убирает из очереди и прерывает активную загрузку (крестик). */
    fun cancelDownload(songId: String) {
        removeFromQueue(songId)
        activeCalls[songId]?.cancel()
    }

    /** Отменяет скачивание набора треков: чистит очередь и прерывает активные (крестик на экране плейлиста/альбома). */
    fun cancelDownloads(ids: Set<String>) {
        if (ids.isEmpty()) return
        batchScope.launch {
            queueMutex.withLock {
                _queue.value = _queue.value.filterNot { it.song.id in ids }
            }
        }
        ids.forEach { id -> activeCalls[id]?.cancel() }
    }

    /** Отменяет всё: очередь очищается, активные загрузки прерываются. */
    fun cancelAll() {
        batchScope.launch {
            queueMutex.withLock { _queue.value = emptyList() }
        }
        activeCalls.values.forEach { it.cancel() }
    }

    /**
     * Ставит треки в очередь скачивания. Очередь обрабатывается по одному в фоне
     * и живёт в синглтоне — продолжает работать, даже если пользователь ушёл
     * с экрана плейлиста/альбома. Уже скачанные и уже стоящие в очереди не дублируются.
     */
    fun downloadAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        batchScope.launch {
            queueMutex.withLock {
                val queuedIds = _queue.value.map { it.song.id }.toSet()
                val activeIds = _activeDownloads.value.keys
                val toAdd = songs.filter { it.id !in queuedIds && it.id !in activeIds && !isDownloaded(it.id) }
                if (toAdd.isNotEmpty()) _queue.value = _queue.value + toAdd.map { DownloadQueueItem(it) }
            }
            pumpQueue()
        }
    }

    /**
     * Обработчик очереди: берёт первый трек, качает, переходит к следующему.
     * Отменённый или упавший трек не останавливает очередь — качается следующий.
     */
    private fun pumpQueue() {
        batchScope.launch {
            if (!processorMutex.tryLock()) return@launch
            try {
                while (true) {
                    val next = queueMutex.withLock {
                        val first = _queue.value.firstOrNull()
                        if (first != null) _queue.value = _queue.value.drop(1)
                        first
                    } ?: break
                    try { download(next.song) } catch (_: Exception) {}
                }
            } finally {
                processorMutex.unlock()
                // Если в очередь что-то добавили, пока обработчик завершался — запускаем снова
                if (_queue.value.isNotEmpty()) pumpQueue()
            }
        }
    }

    /** Убирает трек из очереди ожидания (крестик у строки «В очереди»). */
    fun removeFromQueue(songId: String) {
        batchScope.launch {
            queueMutex.withLock {
                _queue.value = _queue.value.filterNot { it.song.id == songId }
            }
        }
    }

    private fun putActive(entry: ActiveDownload) {
        _activeDownloads.value = _activeDownloads.value + (entry.song.id to entry)
        _inProgress.value = _activeDownloads.value.keys
    }

    private inline fun updateActive(songId: String, transform: (ActiveDownload) -> ActiveDownload) {
        val cur = _activeDownloads.value[songId] ?: return
        _activeDownloads.value = _activeDownloads.value + (songId to transform(cur))
        _inProgress.value = _activeDownloads.value.keys
    }

    /** Удаляет трек из скачанных: стирает файл (память освобождается) и запись реестра. */
    suspend fun delete(songId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _downloaded.value[songId]?.let { File(dir, it.fileName).delete() }
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(songId) && f.name != "index.json") f.delete()
            }
            mutex.withLock {
                val map = _downloaded.value - songId
                _downloaded.value = map
                saveIndex(map)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAll(songIds: List<String>) {
        songIds.forEach { id -> delete(id) }
    }
}
