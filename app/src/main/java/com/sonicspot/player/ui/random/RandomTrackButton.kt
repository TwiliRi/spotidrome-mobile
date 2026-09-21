package com.sonicspot.player.ui.random

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.sonicspot.player.ui.theme.SpotifyColors

/**
 * Кнопка «случайный трек» — кубик в шапке «Главной» (в десктопном Spotidrome он живёт
 * в титульной панели рядом с «Домой»).
 *
 * Пока идёт бросок, кубик крутится и не принимает повторные нажатия — как `.dice.busy`
 * в стилях десктопа. Диаметр и фон — как у соседних кнопок шапки.
 */
@Composable
fun RandomTrackButton(
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spin = rememberInfiniteTransition(label = "dice")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing), RepeatMode.Restart),
        label = "diceAngle"
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SpotifyColors.Gray)
            .clickable(enabled = !isBusy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Casino,
            contentDescription = "Случайный трек",
            tint = if (isBusy) SpotifyColors.White.copy(alpha = 0.55f) else SpotifyColors.White,
            modifier = Modifier
                .size(22.dp)
                .rotate(if (isBusy) angle else 0f)
        )
    }
}
