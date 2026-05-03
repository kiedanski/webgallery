// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.video

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.webgallery.data.PhotoRepository
import com.webgallery.data.db.PhotoEntity
import com.webgallery.data.webdav.WebDavClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class VideoPlayerViewModel(
    private val repository: PhotoRepository,
    private val webDavClient: WebDavClient,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _photoId = MutableStateFlow<Long?>(null)
    val photo: StateFlow<PhotoEntity?> = _photoId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.getPhotoById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _playbackError = MutableStateFlow(false)
    val playbackError: StateFlow<Boolean> = _playbackError.asStateFlow()

    private var player: ExoPlayer? = null

    fun setPhoto(id: Long) {
        if (_photoId.value == id) return
        _photoId.value = id
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun buildPlayer(context: Context, photo: PhotoEntity): ExoPlayer {
        releasePlayer()
        _playbackError.value = false
        val factory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, factory))
        val mediaUri: String = photo.localFavoritePath?.let { File(it) }
            ?.takeIf { it.exists() }?.toUri()
            ?: webDavClient.currentConfig()?.let { cfg ->
                "${cfg.baseUrl}/dav/photos/${photo.remoteOriginalPath}"
            } ?: ""
        val item = MediaItem.fromUri(mediaUri)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(item)
                playWhenReady = true
                prepare()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isBuffering.value = playbackState == Player.STATE_BUFFERING
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        _playbackError.value = true
                        _isBuffering.value = false
                    }
                })
            }
        player = newPlayer
        return newPlayer
    }

    fun releasePlayer() {
        player?.release()
        player = null
    }

    fun toggleFavorite(photo: PhotoEntity) {
        viewModelScope.launch {
            if (photo.isFavorite) {
                repository.toggleFavorite(photo)
                _downloadProgress.value = null
            } else {
                _downloadProgress.value = 0f
                repository.toggleFavorite(photo) { read, total ->
                    _downloadProgress.value = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                }
                _downloadProgress.value = null
            }
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}

private fun File.toUri(): String = "file://${absolutePath}"
