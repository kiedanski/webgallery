// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.webgallery.data.db.PhotoEntity
import com.webgallery.ui.theme.FavoriteRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoActionsSheet(
    photo: PhotoEntity,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "${photo.filenameStem}.${photo.originalExtension}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                icon = if (photo.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                tint = if (photo.isFavorite) FavoriteRed else MaterialTheme.colorScheme.onSurface,
                label = if (photo.isFavorite) "Remove from favorites" else "Mark as favorite",
                onClick = {
                    onToggleFavorite()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
