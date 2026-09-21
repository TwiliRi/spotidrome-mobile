package com.sonicspot.player.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sonicspot.player.ui.screens.album.AlbumDetailScreen
import com.sonicspot.player.ui.screens.artist.ArtistDetailScreen
import com.sonicspot.player.ui.screens.downloads.DownloadedScreen
import com.sonicspot.player.ui.screens.favorites.FavoritesScreen
import com.sonicspot.player.ui.screens.home.HomeScreen
import com.sonicspot.player.ui.screens.library.LibraryScreen
import com.sonicspot.player.ui.screens.login.LoginScreen
import com.sonicspot.player.ui.screens.memory.MemoryScreen
import com.sonicspot.player.ui.screens.playlist.PlaylistDetailScreen
import com.sonicspot.player.ui.screens.queue.QueueScreen
import com.sonicspot.player.ui.screens.recently.RecentlyAddedScreen
import com.sonicspot.player.ui.screens.search.SearchScreen
import com.sonicspot.player.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Memory : Screen("memory")
    object Favorites : Screen("favorites")
    object Downloads : Screen("downloads")
    object Queue : Screen("queue")
    object RecentlyAdded : Screen("recently_added")
    object AlbumDetail : Screen("album/{albumId}") {
        fun createRoute(albumId: String) = "album/$albumId"
    }
    object ArtistDetail : Screen("artist/{artistId}") {
        fun createRoute(artistId: String) = "artist/$artistId"
    }
    object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    onLoginSuccess: () -> Unit = {},
    /** Бросок случайного трека идёт прямо сейчас (кнопка-кубик крутится). */
    randomBusy: Boolean = false,
    onRandomTrackClick: () -> Unit = {}
) {
    // Плавные переходы как в Spotify
    val slideDuration = 300
    val fadeDuration = 200

    // Ссылка Navidrome открыла приложение — переходим на нужный экран
    val pendingRoute by DeepLinks.pendingRoute.collectAsState()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        DeepLinks.consume()
        navController.navigate(route)
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 3 },
                animationSpec = tween(slideDuration)
            ) + fadeIn(animationSpec = tween(fadeDuration))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(slideDuration)
            ) + fadeOut(animationSpec = tween(fadeDuration))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(slideDuration)
            ) + fadeIn(animationSpec = tween(fadeDuration))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 2 },
                animationSpec = tween(slideDuration)
            ) + fadeOut(animationSpec = tween(fadeDuration))
        }
    ) {
        composable(
            Screen.Login.route,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(200)) }
        ) {
            LoginScreen(
                onLoginSuccess = {
                    onLoginSuccess()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Bottom bar screens - без слайда, только fade для плавности переключения табов
        composable(
            Screen.Home.route,
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(200)) }
        ) {
            HomeScreen(
                onAlbumClick = { id -> navController.navigate(Screen.AlbumDetail.createRoute(id)) },
                onArtistClick = { id -> navController.navigate(Screen.ArtistDetail.createRoute(id)) },
                onPlaylistClick = { id -> navController.navigate(Screen.PlaylistDetail.createRoute(id)) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onRecentlyAddedClick = { navController.navigate(Screen.RecentlyAdded.route) },
                onRandomTrackClick = onRandomTrackClick,
                randomBusy = randomBusy
            )
        }

        composable(
            Screen.Search.route,
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(200)) }
        ) {
            SearchScreen(
                onAlbumClick = { id -> navController.navigate(Screen.AlbumDetail.createRoute(id)) },
                onArtistClick = { id -> navController.navigate(Screen.ArtistDetail.createRoute(id)) }
            )
        }

        composable(
            Screen.Library.route,
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(200)) }
        ) {
            LibraryScreen(
                onAlbumClick = { id -> navController.navigate(Screen.AlbumDetail.createRoute(id)) },
                onArtistClick = { id -> navController.navigate(Screen.ArtistDetail.createRoute(id)) },
                onPlaylistClick = { id -> navController.navigate(Screen.PlaylistDetail.createRoute(id)) },
                onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                onDownloadsClick = { navController.navigate(Screen.Downloads.route) }
            )
        }

        // Detail screens - слайд + fade как в Spotify
        composable(
            Screen.Settings.route,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onMemoryClick = { navController.navigate(Screen.Memory.route) },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            Screen.Memory.route,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Screen.Favorites.route,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) {
            FavoritesScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Screen.Downloads.route,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) {
            DownloadedScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Screen.Queue.route,
            enterTransition = {
                slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)) + fadeIn(tween(250))
            },
            exitTransition = {
                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350)) + fadeOut(tween(250))
            },
            popEnterTransition = {
                slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(350)) + fadeIn(tween(250))
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350)) + fadeOut(tween(250))
            }
        ) {
            QueueScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Screen.RecentlyAdded.route,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) {
            RecentlyAddedScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { id -> navController.navigate(Screen.AlbumDetail.createRoute(id)) }
            )
        }

        composable(
            route = Screen.AlbumDetail.route,
            arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) { backStack ->
            val albumId = backStack.arguments?.getString("albumId") ?: ""
            AlbumDetailScreen(albumId = albumId, onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.ArtistDetail.route,
            arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) { backStack ->
            val artistId = backStack.arguments?.getString("artistId") ?: ""
            ArtistDetailScreen(artistId = artistId, onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeIn(tween(fadeDuration))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(slideDuration)) + fadeOut(tween(fadeDuration))
            }
        ) { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            PlaylistDetailScreen(playlistId = playlistId, onBack = { navController.popBackStack() })
        }
    }
}
