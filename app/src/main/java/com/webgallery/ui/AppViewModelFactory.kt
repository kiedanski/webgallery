// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.webgallery.AppContainer
import com.webgallery.WebGalleryApp
import com.webgallery.ui.favorites.FavoritesViewModel
import com.webgallery.ui.flagged.FlaggedViewModel
import com.webgallery.ui.gallery.GalleryViewModel
import com.webgallery.ui.queue.QueueViewModel
import com.webgallery.ui.upload.WatchedFoldersViewModel
import com.webgallery.ui.settings.SettingsViewModel
import com.webgallery.ui.setup.SetupViewModel
import com.webgallery.ui.video.VideoPlayerViewModel
import com.webgallery.ui.viewer.PhotoViewerViewModel

class AppViewModelFactory(private val container: AppContainer, private val app: WebGalleryApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return when (modelClass) {
            SetupViewModel::class.java -> SetupViewModel(
                container.webDavClient,
                container.credentialStore,
                container.settingsRepository
            ) as T
            GalleryViewModel::class.java -> GalleryViewModel(container.photoRepository, app) as T
            FavoritesViewModel::class.java -> FavoritesViewModel(
                container.photoRepository,
                container.imageCacheManager
            ) as T
            PhotoViewerViewModel::class.java -> PhotoViewerViewModel(
                container.photoRepository,
                container.settingsRepository
            ) as T
            VideoPlayerViewModel::class.java -> VideoPlayerViewModel(
                container.photoRepository,
                container.webDavClient,
                container.okHttpClient
            ) as T
            WatchedFoldersViewModel::class.java -> WatchedFoldersViewModel(
                container.watchedFolderDao,
                container.uploadDao,
                app
            ) as T
            QueueViewModel::class.java -> QueueViewModel(
                container.photoRepository
            ) as T
            FlaggedViewModel::class.java -> FlaggedViewModel(
                container.photoRepository,
                container.settingsRepository
            ) as T
            SettingsViewModel::class.java -> SettingsViewModel(
                container.credentialStore,
                container.settingsRepository,
                container.photoRepository,
                container.thumbnailStore,
                container.imageCacheManager,
                container.webDavClient
            ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }

    companion object {
        fun fromApp(app: WebGalleryApp): AppViewModelFactory = AppViewModelFactory(app.container, app)
    }
}
