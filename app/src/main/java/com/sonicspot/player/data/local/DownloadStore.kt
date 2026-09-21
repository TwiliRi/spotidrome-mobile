package com.sonicspot.player.data.local

import android.content.Context
import com.sonicspot.player.data.model.Song
import com.sonicspot.player.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    private val _inProgress = MutableStateFlow<Set<String>>(emptySet())
    val inProgress: StateFlow<Set<String>> = _inProgress.asStateFlow()

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
        if (_inProgress.value.contains(song.id) || isDownloaded(song.id)) {
            return@withContext Result.success(Unit)
        }
        _inProgress.value = _inProgress.value + song.id
        try {
            val url = repository.getStreamUrl(song.id)
            if (url.isBlank()) return@withContext Result.failure(Exception("Сервер недоступен"))
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
                val target = File(dir, "${song.id}$ext")
                val tmp = File(dir, "${song.id}.part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
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
            Result.failure(e)
        } finally {
            _inProgress.value = _inProgress.value - song.id
        }
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
