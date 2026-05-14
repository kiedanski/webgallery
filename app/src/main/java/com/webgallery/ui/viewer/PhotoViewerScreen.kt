// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.webgallery.data.db.PhotoEntity
import com.webgallery.ui.AppViewModelFactory
import com.webgallery.ui.theme.FavoriteRed
import com.webgallery.util.FileUtils
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun PhotoViewerScreen(
    photoId: Long,
    factory: AppViewModelFactory,
    onClose: () -> Unit
) {
    val viewModel: PhotoViewerViewModel = viewModel(factory = factory)
    LaunchedEffect(photoId) { viewModel.setInitialPhoto(photoId) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            restoreSystemBars(view)
        }
    }

    val photos by viewModel.photosInMonth.collectAsStateWithLifecycle()
    val initialPhoto by viewModel.initialPhoto.collectAsStateWithLifecycle()
    val fullImageStates by viewModel.fullImageStates.collectAsStateWithLifecycle()

    BackHandler {
        restoreSystemBars(view)
        onClose()
    }

    if (photos.isEmpty() || initialPhoto == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    val initialIndex = photos.indexOfFirst { it.id == initialPhoto?.id }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })

    var barsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(barsVisible) {
        if (barsVisible) {
            delay(3_000)
            barsVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val photo = photos[page]
            LaunchedEffect(photo.id) { viewModel.ensureFullImage(photo) }
            val state = fullImageStates[photo.id]
            val fullFile = (state as? PhotoViewerViewModel.FullImageState.Loaded)?.file
            val thumb = photo.localThumbnailPath?.let { File(it) }
            ZoomableImage(
                primaryFile = fullFile,
                fallbackFile = thumb,
                onSingleTap = { barsVisible = !barsVisible },
                modifier = Modifier.fillMaxSize()
            )
            if (state is PhotoViewerViewModel.FullImageState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                }
            } else if (state is PhotoViewerViewModel.FullImageState.Failed && (thumb == null || !thumb.exists())) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Full image not available offline", color = Color.White)
                }
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val currentPhoto = photos.getOrNull(pagerState.currentPage)
            TopBar(
                title = currentPhoto?.let { "${it.filenameStem}.${it.originalExtension}" } ?: "",
                isFavorite = currentPhoto?.isFavorite == true,
                onBack = {
                    restoreSystemBars(view)
                    onClose()
                },
                onFavoriteToggle = {
                    currentPhoto?.let { viewModel.toggleFavorite(it) }
                }
            )
        }
        // Bottom bar
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val currentPhoto = photos.getOrNull(pagerState.currentPage)
            val info = currentPhoto?.let { photo ->
                val state = fullImageStates[photo.id]
                if (state is PhotoViewerViewModel.FullImageState.Loaded) {
                    val date = parseDate(photo.lastModified)
                    "${state.width} × ${state.height} · ${FileUtils.formatFileSize(photo.fileSize)} · $date"
                } else {
                    "Loading..."
                }
            } ?: ""
            BottomBar(text = info)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isFavorite) 1.2f else 1f,
        animationSpec = tween(200),
        label = "favscale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onFavoriteToggle) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) FavoriteRed else Color.White,
                modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
            )
        }
    }
}

@Composable
private fun BottomBar(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = text, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

private fun parseDate(lastModified: String?): String {
    if (lastModified == null) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        val date = sdf.parse(lastModified) ?: return lastModified
        val cal = java.util.Calendar.getInstance().apply { time = date }
        FileUtils.formatDate(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    } catch (e: Exception) {
        lastModified
    }
}

private fun restoreSystemBars(view: android.view.View) {
    val window = (view.context as? android.app.Activity)?.window ?: return
    WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
}
