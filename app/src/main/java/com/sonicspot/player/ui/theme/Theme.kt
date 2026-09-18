package com.sonicspot.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SpotifyColorScheme = darkColorScheme(
    primary = SpotifyColors.Green,
    onPrimary = SpotifyColors.Black,
    primaryContainer = SpotifyColors.Green,
    onPrimaryContainer = SpotifyColors.Black,
    secondary = SpotifyColors.LightGray,
    onSecondary = SpotifyColors.Black,
    background = SpotifyColors.Black,
    onBackground = SpotifyColors.White,
    surface = SpotifyColors.DarkGray,
    onSurface = SpotifyColors.White,
    surfaceVariant = SpotifyColors.Gray,
    onSurfaceVariant = SpotifyColors.LightGray,
    surfaceContainer = SpotifyColors.Gray,
    surfaceContainerHigh = SpotifyColors.GrayLight,
    error = SpotifyColors.Red,
    onError = SpotifyColors.White,
    outline = SpotifyColors.GrayLighter,
    outlineVariant = SpotifyColors.GrayDivider,
    scrim = Color.Black.copy(alpha = 0.6f)
)

@Composable
fun SonicSpotTheme(
    darkTheme: Boolean = true, // Spotify всегда темный
    content: @Composable () -> Unit
) {
    val colorScheme = SpotifyColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SpotifyColors.Black.toArgb()
            window.navigationBarColor = SpotifyColors.Black.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
