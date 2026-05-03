package com.webgallery.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.db.PhotoEntity
import com.webgallery.model.PhotoCounts
import com.webgallery.model.SyncStatus
import com.webgallery.model.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val repository: PhotoRepository
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    private var syncJob: Job? = null
    private var initialSyncTriggered = false

    fun triggerInitialSync() {
        if (initialSyncTriggered) return
        initialSyncTriggered = true
        triggerSync()
    }

    fun triggerSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            repository.sync()
        }
    }

    fun getCountsForMonth(year: Int, month: Int): Flow<PhotoCounts> =
        repository.getCountsByYearMonth(year, month)
}
