package com.sonicspot.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.repository.MusicRepository
import com.sonicspot.player.player.PlayerManager
import com.sonicspot.player.ui.components.MiniPlayerModern
import com.sonicspot.player.ui.components.SpotifyBottomNavModern
import com.sonicspot.player.ui.navigation.AppNavGraph
import com.sonicspot.player.ui.navigation.DeepLinks
import com.sonicspot.player.ui.navigation.Screen
import com.sonicspot.player.ui.screens.player.FullPlayerScreen
import com.sonicspot.player.ui.theme.Background
import com.sonicspot.player.ui.theme.SonicSpotTheme
import com.sonicspot.player.ui.theme.SpotifyColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var repository: MusicRepository

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Открытие ссылки Navidrome (например, https://server/app/#/playlist/123/show)
        DeepLinks.handleIntent(intent)

        setContent {
            SonicSpotTheme {
                // Android 13+: без POST_NOTIFICATIONS медиа-уведомление (плеер в шторке)
                // не показывается. Спрашиваем один раз при запуске.
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* пользователь решил — идём дальше в любом случае */ }
                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                var startDestination by remember { mutableStateOf<String?>(null) }
                var showFullPlayer by remember { mutableStateOf(false) }

                val currentSong by playerManager.currentSongFlow.collectAsState()

                LaunchedEffect(Unit) {
                    val isLoggedIn = prefs.isLoggedIn.first()
                    startDestination = if (isLoggedIn) Screen.Home.route else Screen.Login.route
                }

                if (startDestination == null) {
                    Box(modifier = Modifier.fillMaxSize().background(SpotifyColors.Black)) {
                        CircularProgressIndicator(color = SpotifyColors.White, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                    }
                    return@SonicSpotTheme
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route
                val showBottomBar = currentRoute in listOf(Screen.Home.route, Screen.Search.route, Screen.Library.route)

                // Правильная обработка кнопки назад для полноэкранного плеера
                BackHandler(enabled = showFullPlayer) {
                    showFullPlayer = false
                }

                // Грамотная обработка back на главных вкладках: Search/Library -> Home, Home -> двойное нажатие для выхода
                var backPressedOnce by remember { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }

                BackHandler(enabled = !showFullPlayer && showBottomBar) {
                    when {
                        currentRoute == Screen.Search.route || currentRoute == Screen.Library.route -> {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        currentRoute == Screen.Home.route -> {
                            if (backPressedOnce) {
                                // Выходим
                                (navController.context as? ComponentActivity)?.finish()
                            } else {
                                backPressedOnce = true
                                scope.launch {
                                    snackbarHostState.showSnackbar("Нажми ещё раз чтобы выйти")
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(backPressedOnce) {
                    if (backPressedOnce) {
                        kotlinx.coroutines.delay(2000)
                        backPressedOnce = false
                    }
                }

                Scaffold(
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = SpotifyColors.Gray,
                                contentColor = SpotifyColors.White,
                                actionColor = SpotifyColors.Green
                            )
                        }
                    },
                    bottomBar = {
                        Column(modifier = Modifier.background(SpotifyColors.Black)) {
                            AnimatedVisibility(
                                visible = currentSong != null && !showFullPlayer,
                                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(tween(200)),
                                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(tween(200))
                            ) {
                                IsolatedMiniPlayer(
                                    playerManager = playerManager,
                                    repository = repository,
                                    onPlayPause = { playerManager.togglePlayPause() },
                                    onNext = { playerManager.playNext() },
                                    onClick = { showFullPlayer = true }
                                )
                            }
                            AnimatedVisibility(
                                visible = showBottomBar,
                                enter = slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(250)) + fadeIn(tween(200)),
                                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) + fadeOut(tween(200))
                            ) {
                                SpotifyBottomNavModern(
                                    currentRoute = when {
                                        currentRoute.startsWith("home") -> "home"
                                        currentRoute.startsWith("search") -> "search"
                                        currentRoute.startsWith("library") -> "library"
                                        else -> "home"
                                    },
                                    onNavigate = { route ->
                                        navController.navigate(route) {
                                            popUpTo(Screen.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    },
                    containerColor = Background,
                    contentWindowInsets = WindowInsets(0)
                ) { padding ->
                    Box(modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                        AppNavGraph(
                            navController = navController,
                            startDestination = startDestination!!,
                            onLoginSuccess = { scope.launch { } }
                        )
                    }
                }

                // Full player с анимацией снизу как в Spotify
                AnimatedVisibility(
                    visible = showFullPlayer,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(400)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(350)
                    ) + fadeOut(animationSpec = tween(250))
                ) {
                    // BackHandler уже выше, но дублируем для надежности
                    BackHandler(enabled = showFullPlayer) {
                        showFullPlayer = false
                    }
                    FullPlayerScreen(onClose = { showFullPlayer = false })
                }
            }
        }
    }
}

@Composable
private fun IsolatedMiniPlayer(
    playerManager: PlayerManager,
    repository: MusicRepository,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    val currentSong by playerManager.currentSongFlow.collectAsState()
    val isPlaying by playerManager.isPlayingFlow.collectAsState()
    val progress by playerManager.progressFlow.collectAsState()

    if (currentSong == null) return

    val coverUrl = remember(currentSong?.coverArt) {
        repository.getCoverArtUrl(currentSong?.coverArt, 200)
    }

    MiniPlayerModern(
        song = currentSong,
        coverUrl = coverUrl,
        isPlaying = isPlaying,
        progress = progress,
        onPlayPause = onPlayPause,
        onNext = onNext,
        onClick = onClick
    )
}
