package com.webgallery.ui.viewer

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.PhotoRepository
import com.webgallery.data.SettingsRepository
import com.webgallery.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerViewModel(
    private val repository: PhotoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _initialPhotoId = MutableStateFlow<Long?>(null)
    val initialPhoto: StateFlow<PhotoEntity?> = _initialPhotoId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.getPhotoById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val photosInMonth: StateFlow<List<PhotoEntity>> = initialPhoto
        .flatMapLatest { p ->
            if (p == null) flowOf(emptyList())
            else repository.getPhotosAndVideosByMonth(p.year, p.month)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _fullImageStates = MutableStateFlow<Map<Long, FullImageState>>(emptyMap())
    val fullImageStates: StateFlow<Map<Long, FullImageState>> = _fullImageStates.asStateFlow()

    fun setInitialPhoto(id: Long) {
        if (_initialPhotoId.value == id) return
        _initialPhotoId.value = id
    }

    fun ensureFullImage(photo: PhotoEntity) {
        val current = _fullImageStates.value[photo.id]
        if (current is FullImageState.Loading || current is FullImageState.Loaded) return
        viewModelScope.launch {
            _fullImageStates.value = _fullImageStates.value + (photo.id to FullImageState.Loading)
            val limit = settingsRepository.cacheLimitBytes.first()
            val file = repository.ensureFullImage(photo, limit)
            if (file != null && file.exists()) {
                val (w, h) = withContext(Dispatchers.IO) { measure(file) }
                _fullImageStates.value = _fullImageStates.value + (photo.id to FullImageState.Loaded(file, w, h))
            } else {
                _fullImageStates.value = _fullImageStates.value + (photo.id to FullImageState.Failed)
            }
        }
    }

    fun toggleFavorite(photo: PhotoEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(photo)
        }
    }

    private fun measure(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    sealed class FullImageState {
        data object Loading : FullImageState()
        data class Loaded(val file: File, val width: Int, val height: Int) : FullImageState()
        data object Failed : FullImageState()
    }
}
