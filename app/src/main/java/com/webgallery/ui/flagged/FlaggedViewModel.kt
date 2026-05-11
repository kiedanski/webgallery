// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.flagged

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.SettingsRepository
import com.webgallery.data.db.PhotoEntity
import com.webgallery.data.db.PhotoErrorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FlaggedViewModel(
    private val repository: PhotoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val flaggedPhotos: StateFlow<List<PhotoEntity>> = repository.getFlaggedPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>> =
        repository.getErrorsForPhoto(photoId)

    fun unflag(photo: PhotoEntity) {
        viewModelScope.launch { repository.toggleFlagged(photo) }
    }

    fun ensureFullImage(photo: PhotoEntity, onProgress: ((Long, Long) -> Unit)? = null) {
        viewModelScope.launch {
            val limit = settingsRepository.cacheLimitBytes.first()
            repository.ensureFullImage(photo, limit, onProgress)
        }
    }
}
