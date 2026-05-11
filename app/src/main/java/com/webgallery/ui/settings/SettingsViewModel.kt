// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.CredentialStore
import com.webgallery.data.PhotoRepository
import com.webgallery.data.SettingsRepository
import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.cache.ThumbnailStore
import com.webgallery.data.webdav.WebDavClient
import com.webgallery.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
    private val photoRepository: PhotoRepository,
    private val thumbnailStore: ThumbnailStore,
    private val imageCacheManager: ImageCacheManager,
    private val webDavClient: WebDavClient
) : ViewModel() {

    private val _serverUrl = MutableStateFlow(credentialStore.loadConfig()?.url.orEmpty())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(credentialStore.loadConfig()?.username.orEmpty())
    val username: StateFlow<String> = _username.asStateFlow()

    val cacheLimit: StateFlow<Long> = settingsRepository.cacheLimitBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_CACHE_LIMIT)

    val themeMode: StateFlow<String> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    private val _thumbnailCacheSize = MutableStateFlow("…")
    val thumbnailCacheSize: StateFlow<String> = _thumbnailCacheSize.asStateFlow()

    private val _imageCacheSize = MutableStateFlow("…")
    val imageCacheSize: StateFlow<String> = _imageCacheSize.asStateFlow()

    private val _favoritesSize = MutableStateFlow("…")
    val favoritesSize: StateFlow<String> = _favoritesSize.asStateFlow()

    fun recalculateSizes() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _thumbnailCacheSize.value = FileUtils.formatFileSize(thumbnailStore.calculateTotalSize())
                _imageCacheSize.value = FileUtils.formatFileSize(imageCacheManager.calculateCacheSize())
                _favoritesSize.value = FileUtils.formatFileSize(imageCacheManager.calculateFavoritesSize())
            }
        }
    }

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch {
            settingsRepository.setCacheLimit(bytes)
            photoRepository.evictCacheIfNeeded(bytes)
            recalculateSizes()
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun forceResync() {
        viewModelScope.launch {
            photoRepository.forceSyncStateReset()
        }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            photoRepository.clearThumbnailCache()
            recalculateSizes()
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            photoRepository.clearImageCache()
            recalculateSizes()
        }
    }

    fun disconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            photoRepository.clearAllData()
            credentialStore.clear()
            settingsRepository.clearAll()
            webDavClient.configure(null)
            onDone()
        }
    }
}
