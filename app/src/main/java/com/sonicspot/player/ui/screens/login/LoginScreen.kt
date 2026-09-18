package com.sonicspot.player.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicspot.player.ui.theme.*

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onLoginSuccess()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyColors.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // Logo - Spotify style
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SpotifyColors.White),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SpotifyColors.Green),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = SpotifyColors.Black
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Spotidrome",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    letterSpacing = (-1).sp
                ),
                color = SpotifyColors.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Миллионы треков. Твой Navidrome.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SpotifyColors.LightGray,
                    fontSize = 14.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            // Server URL field - Spotify style dark
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Адрес сервера",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = SpotifyColors.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    placeholder = {
                        Text(
                            "https://music.example.com",
                            color = SpotifyColors.MediumGray
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SpotifyColors.Gray,
                        unfocusedContainerColor = SpotifyColors.Gray,
                        focusedBorderColor = SpotifyColors.White,
                        unfocusedBorderColor = SpotifyColors.Gray,
                        focusedTextColor = SpotifyColors.White,
                        unfocusedTextColor = SpotifyColors.White,
                        cursorColor = SpotifyColors.White
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Имя пользователя",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = SpotifyColors.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = { Text("username", color = SpotifyColors.MediumGray) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SpotifyColors.Gray,
                        unfocusedContainerColor = SpotifyColors.Gray,
                        focusedBorderColor = SpotifyColors.White,
                        unfocusedBorderColor = SpotifyColors.Gray,
                        focusedTextColor = SpotifyColors.White,
                        unfocusedTextColor = SpotifyColors.White,
                        cursorColor = SpotifyColors.White
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Пароль",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = SpotifyColors.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    placeholder = { Text("••••••••", color = SpotifyColors.MediumGray) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SpotifyColors.Gray,
                        unfocusedContainerColor = SpotifyColors.Gray,
                        focusedBorderColor = SpotifyColors.White,
                        unfocusedBorderColor = SpotifyColors.Gray,
                        focusedTextColor = SpotifyColors.White,
                        unfocusedTextColor = SpotifyColors.White,
                        cursorColor = SpotifyColors.White
                    )
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SpotifyColors.Red.copy(alpha = 0.15f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.error!!,
                        color = SpotifyColors.White,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Primary button - Spotify white pill
            Button(
                onClick = viewModel::login,
                enabled = !state.isLoading && state.serverUrl.isNotBlank() && state.username.isNotBlank() && state.password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyColors.White,
                    contentColor = SpotifyColors.Black,
                    disabledContainerColor = SpotifyColors.Gray,
                    disabledContentColor = SpotifyColors.MediumGray
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SpotifyColors.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "ВОЙТИ",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            fontSize = 15.sp
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(SpotifyColors.Gray))
                Text(
                    text = "  Subsonic API  ",
                    style = MaterialTheme.typography.labelSmall.copy(color = SpotifyColors.MediumGray),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Box(modifier = Modifier.weight(1f).height(1.dp).background(SpotifyColors.Gray))
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Поддерживает Navidrome, Gonic, Airsonic, LMS.\nТвоя музыка остается у тебя.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = SpotifyColors.MediumGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
