// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.db.PhotoEntity
import com.webgallery.model.PhotoCounts
import com.webgallery.model.YearMonth
import com.webgallery.ui.gallery.GallerySection
import com.webgallery.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesViewModel(
    private val repository: PhotoRepository,
    private val imageCacheManager: ImageCacheManager
) : ViewModel() {

    private val favoritesFlow: Flow<List<PhotoEntity>> = repository.getFavorites()

    val sections: StateFlow<List<GallerySection>> = favoritesFlow
        .map { list ->
            list.groupBy { YearMonth(it.year, it.month) }
                .toSortedMap(compareByDescending<YearMonth> { it.year }.thenByDescending { it.month })
                .map { (ym, photos) -> GallerySection(ym, photos) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _totalSize = MutableStateFlow("0 B")
    val totalSize: StateFlow<String> = _totalSize.asStateFlow()

    init {
        recalculateSize()
    }

    fun recalculateSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { imageCacheManager.calculateFavoritesSize() }
            _totalSize.value = FileUtils.formatFileSize(bytes)
        }
    }

    fun countsFor(year: Int, month: Int): Flow<PhotoCounts> {
        return favoritesFlow.map { list ->
            val matching = list.filter { it.year == year && it.month == month }
            PhotoCounts(
                total = matching.size,
                photoCount = matching.count { it.mediaType == "PHOTO" },
                videoCount = matching.count { it.mediaType == "VIDEO" }
            )
        }
    }

    fun toggleFavorite(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(photo)
            recalculateSize()
        }
    }
}
