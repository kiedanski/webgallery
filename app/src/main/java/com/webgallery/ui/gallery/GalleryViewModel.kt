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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Filter: null = all, "PHOTO" = photos only, "VIDEO" = videos only
    private val _mediaFilter = MutableStateFlow<String?>(null)
    val mediaFilter: StateFlow<String?> = _mediaFilter.asStateFlow()

    fun setMediaFilter(filter: String?) {
        _mediaFilter.value = filter
    }

    val sections: StateFlow<List<GallerySection>> = combine(
        yearMonths.flatMapLatest { months ->
            if (months.isEmpty()) flowOf(emptyList())
            else combine(months.map { ym ->
                repository.getPhotosAndVideosByMonth(ym.year, ym.month)
            }) { arrays ->
                months.mapIndexed { index, ym ->
                    GallerySection(ym, arrays[index])
                }
            }
        },
        _mediaFilter
    ) { allSections, filter ->
        if (filter == null) allSections.filter { it.photos.isNotEmpty() }
        else allSections.map { section ->
            section.copy(photos = section.photos.filter { it.mediaType == filter })
        }.filter { it.photos.isNotEmpty() }
    }
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val yearStats: StateFlow<List<YearStats>> = repository.getYearStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingMutationsByPhoto: StateFlow<Map<Long, String>> = repository.getPendingPhotoMutations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pendingMutations: StateFlow<Int> = repository.getPendingMutationCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalCount: StateFlow<Int> = repository.getTotalCount()
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<PhotoEntity>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else repository.search(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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

    fun sharePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            val limit = repository.getCacheLimitBytes()
            val file = repository.ensureFullImage(photo, limit)
            if (file != null) {
                com.webgallery.util.ShareUtils.shareFile(app, file, photo.mimeType)
            }
        }
    }

    fun enqueueDelete(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.enqueueDelete(photo)
        }
    }
}
