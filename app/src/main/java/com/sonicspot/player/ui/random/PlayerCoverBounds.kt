package com.sonicspot.player.ui.random

import androidx.compose.ui.geometry.Rect

/**
 * Экранный прямоугольник обложки полноэкранного плеера — «финиш» перелёта обложки
 * случайного трека. Полноэкранный плеер сообщает свои границы, а сцена чёрной дыры
 * читает их в фазе EXPAND и уводит туда обложку-героя (в десктопе это делал запрос
 * `.np2-cover` из DOM).
 */
object PlayerCoverBounds {

    @Volatile
    var rect: Rect? = null
        private set

    /** Радиус скругления цели в пикселях — обложка приземляется точно по форме плеера. */
    @Volatile
    var cornerRadius: Float = 0f
        private set

    fun update(bounds: Rect, radiusPx: Float) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        rect = bounds
        cornerRadius = radiusPx
    }

    fun clear() {
        rect = null
        cornerRadius = 0f
    }
}
