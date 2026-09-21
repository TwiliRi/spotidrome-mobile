package com.sonicspot.player.player

import android.app.NotificationManager
import android.app.NotificationChannel
import android.util.Log
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sonicspot.player.R
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.data.repository.StarredRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var dislikedRepository: DislikedRepository
    @Inject lateinit var starredRepository: StarredRepository
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var prefs: PreferencesManager

    private var mediaSession: MediaSession? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var coilImageLoader: ImageLoader? = null

    companion object {
        const val CHANNEL_ID = "sonicspot_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_FAVORITE = "action_toggle_favorite"
        const val ACTION_TOGGLE_DISLIKE = "action_toggle_dislike"
        const val ACTION_TOGGLE_SHUFFLE = "action_toggle_shuffle"
    }

    @Suppress("DEPRECATION")
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        coilImageLoader = ImageLoader.Builder(this).build()

        val player = playerManager.getPlayer()

        val bitmapLoader = object : BitmapLoader {
            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                val future = com.google.common.util.concurrent.SettableFuture.create<Bitmap>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val request = ImageRequest.Builder(this@MusicService)
                            .data(uri)
                            .size(512)
                            .allowHardware(false)
                            .build()
                        val result = coilImageLoader?.execute(request)?.drawable
                        val bitmap = (result as? BitmapDrawable)?.bitmap ?: result?.toBitmap()
                        if (bitmap != null) {
                            future.set(bitmap)
                        } else {
                            future.setException(Exception("Failed to load bitmap"))
                        }
                    } catch (e: Exception) {
                        future.setException(e)
                    }
                }
                return future
            }

            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                return try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) Futures.immediateFuture(bitmap)
                    else Futures.immediateFailedFuture(Exception("decode failed"))
                } catch (e: Exception) {
                    Futures.immediateFailedFuture(e)
                }
            }

            override fun supportsMimeType(mimeType: String): Boolean {
                return mimeType.startsWith("image/")
            }
        }

        val sessionCallback = object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_TOGGLE_FAVORITE -> {
                        serviceScope.launch {
                            val songId = playerManager.playerState.value.currentSong?.id ?: return@launch
                            starredRepository.toggleLike(songId)
                            updateCustomLayout()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_TOGGLE_DISLIKE -> {
                        serviceScope.launch {
                            val songId = playerManager.playerState.value.currentSong?.id ?: return@launch
                            dislikedRepository.toggleDislike(songId)
                            updateCustomLayout()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_TOGGLE_SHUFFLE -> {
                        playerManager.toggleShuffle()
                        serviceScope.launch { updateCustomLayout() }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val availableCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_TOGGLE_DISLIKE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.accept(
                    availableCommands,
                    connectionResult.availablePlayerCommands
                )
            }
        }

        // Тап по уведомлению открывает приложение (иначе по уведомлению ничего не происходит)
        val sessionActivity = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, com.sonicspot.player.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setBitmapLoader(bitmapLoader)
            .setSessionActivity(sessionActivity)
            .build()

        val notificationIdProvider = DefaultMediaNotificationProvider.NotificationIdProvider { NOTIFICATION_ID }
        val notificationProvider = DefaultMediaNotificationProvider(
            this,
            notificationIdProvider,
            CHANNEL_ID,
            R.string.app_name
        )
        notificationProvider.setSmallIcon(R.drawable.ic_music)
        setMediaNotificationProvider(notificationProvider)

        // ============ ГЛАВНЫЙ ФИКС ФОНОВОГО РЕЖИМА И УВЕДОМЛЕНИЯ ============
        // Сеанс ОБЯЗАН быть добавлен в сервис через addSession(). В Media3 1.7.1
        // MediaNotificationManager.updateNotification() первым делом проверяет
        // isSessionAdded(session) и при отсутствии сеанса в сервисе сразу вызывает
        // removeNotification(). Сеанс сам по себе (просто MediaSession.Builder().build())
        // НЕ регистрируется — регистрация происходит только когда контроллер подключается
        // к сервису (onBind -> onGetSession -> addSession) или когда приложение вызывает
        // addSession() явно. У приложения нет MediaController -> никто не подключался ->
        // не было НИ уведомления, НИ foreground-статуса -> система убивала процесс при
        // сворачивании. addSession() внутри создаёт служебный MediaController и оживляет
        // всю цепочку: уведомление-плеер, foreground, кнопки гарнитуры/Bluetooth.
        // Повторный addSession того же сеанса (из onGetSession при подключении внешних
        // контроллеров) безопасен — он идемпотентен.
        mediaSession?.let { addSession(it) }

        // Логируем отказ Android 12+ в foreground (ограничение while-in-use), чтобы
        // такие случаи были видны в logcat, а не происходили молча.
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w("MusicService", "ForegroundServiceStartNotAllowedException: не удалось поднять foreground")
            }
        })

        serviceScope.launch {
            playerManager.currentSongFlow.collect {
                updateCustomLayout()
            }
        }
        serviceScope.launch {
            playerManager.playerState.map { it.shuffleEnabled }.distinctUntilChanged().collect {
                updateCustomLayout()
            }
        }
        serviceScope.launch {
            prefs.dislikedIdsFlow.collect {
                updateCustomLayout()
            }
        }
        serviceScope.launch {
            prefs.likedIdsFlow.collect {
                updateCustomLayout()
            }
        }
        serviceScope.launch {
            prefs.notificationButtonsFlow.collect {
                updateCustomLayout()
            }
        }

        updateCustomLayout()
    }

    private fun updateCustomLayout() {
        serviceScope.launch {
            updateCustomLayoutInternal()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun updateCustomLayoutInternal() {
        val session = mediaSession ?: return
        val state = playerManager.playerState.value
        val isShuffle = state.shuffleEnabled
        val currentSongId = state.currentSong?.id

        val isFavorite = try {
            val ids = prefs.likedIdsFlow.first()
            ids.contains(currentSongId)
        } catch (_: Exception) { false }

        val isDisliked = try {
            val ids = prefs.dislikedIdsFlow.first()
            ids.contains(currentSongId)
        } catch (_: Exception) { false }

        val dislikeButton = CommandButton.Builder()
            .setDisplayName(if (isDisliked) "Убрать из исключенных" else "Исключить")
            .setIconResId(if (isDisliked) R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_DISLIKE, Bundle.EMPTY))
            .build()

        val shuffleButton = CommandButton.Builder()
            .setDisplayName(if (isShuffle) "Выключить перемешивание" else "Перемешивание")
            .setIconResId(if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .build()

        val favoriteButton = CommandButton.Builder()
            .setDisplayName(if (isFavorite) "Убрать из избранного" else "В избранное")
            .setIconResId(if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()

        // Состав кнопок настраивается пользователем (Настройки → «Кнопки в уведомлении»):
        // любые комбинации лайк/дизлайк/шамбл. Порядок в шторке фиксированный.
        val enabledButtons = try {
            prefs.notificationButtonsFlow.first()
        } catch (_: Exception) {
            PreferencesManager.NOTIF_BUTTONS_ALL
        }
        val buttons = buildList {
            if (PreferencesManager.NOTIF_BTN_DISLIKE in enabledButtons) add(dislikeButton)
            if (PreferencesManager.NOTIF_BTN_SHUFFLE in enabledButtons) add(shuffleButton)
            if (PreferencesManager.NOTIF_BTN_LIKE in enabledButtons) add(favoriteButton)
        }
        session.setCustomLayout(buttons)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Воспроизведение",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Управление воспроизведением как в Spotify"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null
        serviceJob.cancel()
        super.onDestroy()
    }
}
