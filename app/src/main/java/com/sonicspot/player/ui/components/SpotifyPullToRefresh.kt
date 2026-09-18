package com.sonicspot.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sonicspot.player.ui.theme.SpotifyColors

/**
 * Spotify-style Pull-to-Refresh
 * Зеленый индикатор как в Spotify, потягивание высоко вверх для насильного обновления
 * Используется на главной, медиатеке, недавно добавленных и т.д.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                isRefreshing = isRefreshing,
                containerColor = SpotifyColors.Green,
                color = SpotifyColors.Black,
                modifier = Modifier.align(Alignment.TopCenter),
                state = state
            )
        },
        content = content
    )
}
