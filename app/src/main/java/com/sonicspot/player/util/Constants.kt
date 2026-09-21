package com.sonicspot.player.util

object Constants {
    const val CLIENT_NAME = "Spotidrome"
    const val API_VERSION = "1.16.1"
    const val PREFS_NAME = "spotidrome_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_SALT = "salt"
    const val KEY_TOKEN = "token"
}

/**
 * Размеры обложек.
 *
 * Проблема, которую это решает: каждый элемент UI передавал свой sizePx (в коде их восемь —
 * 112, 128, 176, 200, 256, 300, 304, 500), сервер отдавал картинку ровно этого размера, а ключ
 * дискового кэша содержал sizePx. Итог: одна и та же обложка скачивалась из сети и попадала на
 * диск до восьми раз — восемь запросов и восемь копий на каждый альбом.
 *
 * Теперь с сервера запрашивается только один из двух размеров, а под конкретный sizePx
 * картинку уменьшает Coil (ImageRequest.Builder.size). Сеть и диск — одна запись на обложку
 * для списков плюс одна для полноэкранного плеера.
 */
object CoverArt {
    /** Списки, сетки, превью. 300px закрывает все эти размеры. */
    const val LIST = 300
    /** Полноэкранный плеер: на экране 1080p обложка занимает около 900px. */
    const val LARGE = 600

    /**
     * Порог отделяет «карточку в списке» от «обложки на весь экран».
     *
     * Он НЕ равен LIST: в UI используется и 304px (сетка альбомов, 152dp), и это всё ещё
     * списочный размер. С порогом, равным LIST, 304 уходил бы в корзину 600 — и самая частая
     * в приложении карточка тянула бы картинку вдвое больше нужной.
     */
    private const val LARGE_THRESHOLD = 400

    /** К какой корзине отнести запрошенный размер. */
    fun bucket(requestedPx: Int): Int = if (requestedPx > LARGE_THRESHOLD) LARGE else LIST
}
