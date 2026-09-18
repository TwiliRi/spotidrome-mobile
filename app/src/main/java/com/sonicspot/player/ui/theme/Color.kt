package com.sonicspot.player.ui.theme

import androidx.compose.ui.graphics.Color

// Spotify 2024-2025 Palette - максимально точная
object SpotifyColors {
    // Core
    val Green = Color(0xFF1DB954)
    val GreenLight = Color(0xFF1ED760)
    val GreenDark = Color(0xFF169C46)
    val GreenBright = Color(0xFF1FDF64) // для акцентов

    // Backgrounds - Spotify использует чистый черный + оттенки
    val Black = Color(0xFF000000)
    val Black90 = Color(0xFF0A0A0A)
    val DarkGray = Color(0xFF121212) // основной фон карточек
    val DarkGrayElevated = Color(0xFF1A1A1A)
    val Gray = Color(0xFF242424) // для карточек Quick Access
    val GrayLight = Color(0xFF2A2A2A) // hover
    val GrayLighter = Color(0xFF3E3E3E)
    val GrayDivider = Color(0xFF2A2A2A)

    // Text
    val White = Color(0xFFFFFFFF)
    val White90 = Color(0xFFE8E8E8)
    val LightGray = Color(0xFFB3B3B3) // вторичный текст
    val MediumGray = Color(0xFF727272)
    val DarkGrayText = Color(0xFF535353)

    // Semantic
    val Red = Color(0xFFE22134)
    val Blue = Color(0xFF2D46B9)
    val Purple = Color(0xFF8D67AB)
    val Orange = Color(0xFFE8115B)

    // Gradients - как в Spotify Home
    val GradientTop = Color(0xFF3A3A3A)
    val GradientBottom = Black

    // Category colors - для Browse
    val CategoryRed = Color(0xFF8D67AB)
    val CategoryBlue = Color(0xFF477D95)
    val CategoryGreen = Color(0xFF1E3264)
    val CategoryOrange = Color(0xFF8C1932)
    val CategoryPink = Color(0xFFE13300)
    val CategoryPurple = Color(0xFF8D67AB)
}

// Для обратной совместимости
val SpotifyGreen = SpotifyColors.Green
val SpotifyGreenLight = SpotifyColors.GreenLight
val SpotifyBlack = SpotifyColors.Black
val SpotifyDarkGray = SpotifyColors.DarkGray
val SpotifyLightGray = SpotifyColors.Gray
val SpotifyLighterGray = SpotifyColors.GrayLighter
val SpotifyWhite = SpotifyColors.White
val SpotifyOffWhite = SpotifyColors.LightGray
val SpotifyRed = SpotifyColors.Red

val Background = SpotifyColors.Black
val Surface = SpotifyColors.DarkGray
val SurfaceVariant = SpotifyColors.Gray
val SurfaceElevated = SpotifyColors.GrayLight
