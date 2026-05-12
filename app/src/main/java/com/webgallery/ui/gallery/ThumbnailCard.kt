// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.webgallery.data.db.MutationEntity
import com.webgallery.data.db.PhotoEntity
import com.webgallery.model.MediaType
import com.webgallery.ui.theme.FavoriteRed
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThumbnailCard(
    photo: PhotoEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    pendingMutationType: String? = null,
    isSelected: Boolean = false
) {
    val context = LocalContext.current
    val isVideo = photo.mediaType == MediaType.VIDEO.name
    val request = remember(photo.localThumbnailPath, photo.id) {
        ImageRequest.Builder(context)
            .data(photo.localThumbnailPath?.let { File(it) })
            .crossfade(false)
            .build()
    }
    val selectionBorder = if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .then(selectionBorder)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (photo.thumbnailDownloaded && photo.localThumbnailPath != null) {
            AsyncImage(
                model = request,
                contentDescription = photo.filenameStem,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Selection indicator
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp)
            )
        }

        // Pending mutation overlay
        if (pendingMutationType != null) {
            val (icon, tint) = when (pendingMutationType) {
                MutationEntity.TYPE_DELETE -> Icons.Outlined.Delete to MaterialTheme.colorScheme.error
                MutationEntity.TYPE_CHANGE_DATE -> Icons.Outlined.CalendarMonth to Color(0xFFFF9800)
                MutationEntity.TYPE_SET_TAGS -> Icons.Outlined.Label to Color(0xFF4CAF50)
                else -> Icons.Outlined.Label to MaterialTheme.colorScheme.onSurface
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(3.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Pending $pendingMutationType",
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (photo.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = FavoriteRed,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(14.dp)
            )
        } else if (photo.localFullPath != null || photo.localFavoritePath != null) {
            Icon(
                imageVector = Icons.Filled.OfflinePin,
                contentDescription = "Available offline",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(12.dp)
            )
        }
    }
}
