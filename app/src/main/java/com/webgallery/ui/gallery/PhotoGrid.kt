// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.webgallery.data.db.PhotoEntity
import com.webgallery.model.PhotoCounts
import com.webgallery.model.YearMonth

import com.webgallery.util.FileUtils
import kotlinx.coroutines.flow.Flow

data class GallerySection(
    val yearMonth: YearMonth,
    val photos: List<PhotoEntity>
)

/**
 * Returns the flat item index of the header for the given year (first month of that year).
 * Each section contributes 1 header item + N photo items.
 */
fun sectionIndexForYear(sections: List<GallerySection>, year: Int): Int? {
    var index = 0
    for (section in sections) {
        if (section.yearMonth.year == year) return index
        index += 1 + section.photos.size // 1 header + photos
    }
    return null
}

@Composable
fun PhotoGrid(
    sections: List<GallerySection>,
    countsFor: (Int, Int) -> Flow<PhotoCounts>,
    onPhotoClick: (Long, Boolean) -> Unit,
    onPhotoLongPress: (PhotoEntity) -> Unit = {},
    modifier: Modifier = Modifier,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    pendingMutations: Map<Long, String> = emptyMap(),
    selectedIds: Set<Long> = emptySet(),
) {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val columnCount = when {
        widthDp >= 600 -> 5
        isLandscape -> 4
        else -> 3
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columnCount),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalItemSpacing = 2.dp,
        contentPadding = PaddingValues(2.dp),
        modifier = modifier
    ) {
        for (section in sections) {
            val ym = section.yearMonth
            item(
                key = "header-${ym.key}",
                span = StaggeredGridItemSpan.FullLine
            ) {
                MonthHeader(yearMonth = ym, countsFlow = countsFor(ym.year, ym.month))
            }
            items(
                count = section.photos.size,
                key = { idx -> "photo-${ym.key}-${section.photos[idx].id}" }
            ) { idx ->
                val photo = section.photos[idx]
                ThumbnailCard(
                    photo = photo,
                    onClick = {
                        val isVideo = photo.mediaType == "VIDEO"
                        onPhotoClick(photo.id, isVideo)
                    },
                    onLongClick = { onPhotoLongPress(photo) },
                    pendingMutationType = pendingMutations[photo.id],
                    isSelected = photo.id in selectedIds
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(yearMonth: YearMonth, countsFlow: Flow<PhotoCounts>) {
    val counts by countsFlow.collectAsState(initial = PhotoCounts())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${FileUtils.monthName(yearMonth.month)} ${yearMonth.year}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${counts.photoCount} photos, ${counts.videoCount} videos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
