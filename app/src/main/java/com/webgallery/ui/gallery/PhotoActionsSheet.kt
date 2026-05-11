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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
    onToggleFlag: () -> Unit,
    onChangeDate: () -> Unit,
    onEditTags: () -> Unit,
    onDelete: () -> Unit,
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
            if (!photo.tags.isNullOrBlank()) {
                Text(
                    text = photo.tags,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
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
            ActionRow(
                icon = if (photo.isFlagged) Icons.Filled.Flag else Icons.Outlined.Flag,
                tint = if (photo.isFlagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                label = if (photo.isFlagged) "Remove flag" else "Flag for inspection",
                onClick = {
                    onToggleFlag()
                    onDismiss()
                }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ActionRow(
                icon = Icons.Outlined.CalendarMonth,
                tint = MaterialTheme.colorScheme.onSurface,
                label = "Change date",
                onClick = {
                    onDismiss()
                    onChangeDate()
                }
            )
            ActionRow(
                icon = Icons.Outlined.Label,
                tint = MaterialTheme.colorScheme.onSurface,
                label = if (photo.tags.isNullOrBlank()) "Add tags" else "Edit tags",
                onClick = {
                    onDismiss()
                    onEditTags()
                }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ActionRow(
                icon = Icons.Outlined.Delete,
                tint = MaterialTheme.colorScheme.error,
                label = "Delete",
                onClick = {
                    onDismiss()
                    onDelete()
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
