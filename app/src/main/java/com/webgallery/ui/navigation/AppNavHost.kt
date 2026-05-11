// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.webgallery.WebGalleryApp
import com.webgallery.ui.AppViewModelFactory
import com.webgallery.ui.favorites.FavoritesScreen
import com.webgallery.ui.flagged.FlaggedScreen
import com.webgallery.ui.gallery.GalleryScreen
import com.webgallery.ui.queue.QueueScreen
import com.webgallery.ui.upload.WatchedFoldersScreen
import com.webgallery.ui.settings.SettingsScreen
import com.webgallery.ui.setup.SetupScreen
import com.webgallery.ui.video.VideoPlayerScreen
import com.webgallery.ui.viewer.PhotoViewerScreen

object Routes {
    const val SETUP = "setup"
    const val GALLERY = "gallery"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val FLAGGED = "flagged"
    const val QUEUE = "queue"
    const val WATCHED_FOLDERS = "watched_folders"
    const val PHOTO_VIEWER = "photo_viewer/{photoId}"
    const val VIDEO_PLAYER = "video_player/{photoId}"
    fun photoViewer(id: Long) = "photo_viewer/$id"
    fun videoPlayer(id: Long) = "video_player/$id"
}

private data class BottomDest(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    BottomDest(Routes.GALLERY, "Gallery", Icons.Outlined.PhotoLibrary),
    BottomDest(Routes.FAVORITES, "Favorites", Icons.Outlined.Favorite),
    BottomDest(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppNavHost(app: WebGalleryApp) {
    val factory = remember { AppViewModelFactory.fromApp(app) }
    val navController = rememberNavController()
    val setupComplete by app.container.settingsRepository.setupComplete.collectAsState(initial = null)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                if (currentRoute == dest.route) return@NavigationBarItem
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (setupComplete == null) return@Scaffold
        val startRoute = if (setupComplete == true) Routes.GALLERY else Routes.SETUP
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(Routes.SETUP) {
                SetupScreen(
                    factory = factory,
                    onConnected = {
                        navController.navigate(Routes.GALLERY) {
                            popUpTo(Routes.SETUP) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.GALLERY) {
                GalleryScreen(
                    factory = factory,
                    onPhotoClick = { id, isVideo ->
                        if (isVideo) navController.navigate(Routes.videoPlayer(id))
                        else navController.navigate(Routes.photoViewer(id))
                    }
                )
            }
            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    factory = factory,
                    onPhotoClick = { id, isVideo ->
                        if (isVideo) navController.navigate(Routes.videoPlayer(id))
                        else navController.navigate(Routes.photoViewer(id))
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    factory = factory,
                    onDisconnected = {
                        navController.navigate(Routes.SETUP) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    },
                    onNavigateToFlagged = { navController.navigate(Routes.FLAGGED) },
                    onNavigateToQueue = { navController.navigate(Routes.QUEUE) },
                    onNavigateToWatchedFolders = { navController.navigate(Routes.WATCHED_FOLDERS) }
                )
            }
            composable(Routes.WATCHED_FOLDERS) {
                WatchedFoldersScreen(
                    factory = factory,
                    onClose = { navController.popBackStack() }
                )
            }
            composable(Routes.QUEUE) {
                QueueScreen(
                    factory = factory,
                    onClose = { navController.popBackStack() }
                )
            }
            composable(Routes.FLAGGED) {
                FlaggedScreen(
                    factory = factory,
                    onClose = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.PHOTO_VIEWER,
                arguments = listOf(navArgument("photoId") { type = NavType.LongType })
            ) { backStack ->
                val id = backStack.arguments?.getLong("photoId") ?: return@composable
                PhotoViewerScreen(
                    photoId = id,
                    factory = factory,
                    onClose = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.VIDEO_PLAYER,
                arguments = listOf(navArgument("photoId") { type = NavType.LongType })
            ) { backStack ->
                val id = backStack.arguments?.getLong("photoId") ?: return@composable
                VideoPlayerScreen(
                    photoId = id,
                    factory = factory,
                    onClose = { navController.popBackStack() }
                )
            }
        }
    }
}
