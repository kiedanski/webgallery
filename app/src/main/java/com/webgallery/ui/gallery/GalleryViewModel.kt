// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.gallery

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.db.PhotoEntity
import com.webgallery.model.PhotoCounts
import com.webgallery.model.SyncStatus
import com.webgallery.model.YearMonth
import com.webgallery.model.YearStats
import com.webgallery.sync.SyncService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel(
    private val repository: PhotoRepository,
    private val app: Application
) : ViewModel() {

    val yearMonths: StateFlow<List<YearMonth>> = repository.getAllYearMonths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sections: StateFlow<List<GallerySection>> = yearMonths
        .flatMapLatest { months ->
            if (months.isEmpty()) flowOf(emptyList())
            else combine(months.map { ym ->
                repository.getPhotosAndVideosByMonth(ym.year, ym.month)
            }) { arrays ->
                months.mapIndexed { index, ym ->
                    GallerySection(ym, arrays[index])
                }.filter { it.photos.isNotEmpty() }
            }
        }
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val yearStats: StateFlow<List<YearStats>> = repository.getYearStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingMutationsByPhoto: StateFlow<Map<Long, String>> = repository.getPendingPhotoMutations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pendingMutations: StateFlow<Int> = repository.getPendingMutationCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    private var initialSyncTriggered = false

    fun triggerInitialSync() {
        if (initialSyncTriggered) return
        initialSyncTriggered = true
        triggerSync()
    }

    fun triggerSync() {
        if (syncStatus.value is SyncStatus.Syncing) return
        SyncService.start(app)
    }

    fun getCountsForMonth(year: Int, month: Int): Flow<PhotoCounts> =
        repository.getCountsByYearMonth(year, month)

    fun toggleFavorite(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(photo)
        }
    }

    fun toggleFlagged(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.toggleFlagged(photo)
        }
    }

    fun enqueueDateChange(photo: PhotoEntity, exifDateStr: String) {
        viewModelScope.launch {
            repository.enqueueDateChange(photo, exifDateStr)
        }
    }

    fun enqueueTagChange(photo: PhotoEntity, tags: String) {
        viewModelScope.launch {
            repository.enqueueTagChange(photo, tags)
        }
    }

    fun enqueueDelete(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.enqueueDelete(photo)
        }
    }
}
