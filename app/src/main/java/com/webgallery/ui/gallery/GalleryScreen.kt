// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.webgallery.data.db.PhotoEntity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.webgallery.model.SyncStatus
import com.webgallery.model.YearStats
import com.webgallery.ui.AppViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    factory: AppViewModelFactory,
    onPhotoClick: (Long, Boolean) -> Unit
) {
    val viewModel: GalleryViewModel = viewModel(factory = factory)
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val yearStats by viewModel.yearStats.collectAsStateWithLifecycle()
    val pendingMutations by viewModel.pendingMutations.collectAsStateWithLifecycle()
    val pendingMutationsByPhoto by viewModel.pendingMutationsByPhoto.collectAsStateWithLifecycle()
    var sheetPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    var scrubberVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.triggerInitialSync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebGallery") },
                actions = {
                    if (pendingMutations > 0) {
                        Text(
                            text = "$pendingMutations pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    SyncStatusIndicator(syncStatus, onRetry = { viewModel.triggerSync() })
                }
            )
        }
    ) { padding ->
        val isRefreshing = syncStatus is SyncStatus.Syncing
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.triggerSync() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            if (sections.isEmpty()) {
                EmptyGalleryState(syncStatus)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (yearStats.size > 1) {
                                    if (dragAmount < -30) scrubberVisible = true
                                    if (dragAmount > 30) scrubberVisible = false
                                }
                            }
                        }
                ) {
                    PhotoGrid(
                        sections = sections,
                        countsFor = { y, m -> viewModel.getCountsForMonth(y, m) },
                        onPhotoClick = onPhotoClick,
                        onPhotoLongPress = { sheetPhoto = it },
                        modifier = Modifier.fillMaxSize(),
                        gridState = gridState,
                        pendingMutations = pendingMutationsByPhoto
                    )

                    if (yearStats.size > 1) {
                        // Small tab to open scrubber when hidden
                        if (!scrubberVisible) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                                    .clickable { scrubberVisible = true }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.ChevronLeft,
                                    contentDescription = "Show timeline",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = scrubberVisible,
                            enter = slideInHorizontally(tween(200)) { it },
                            exit = slideOutHorizontally(tween(200)) { it },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            YearScrubber(
                                yearStats = yearStats,
                                onYearSelected = { year ->
                                    val idx = sectionIndexForYear(sections, year)
                                    if (idx != null) {
                                        scope.launch { gridState.scrollToItem(idx) }
                                    }
                                },
                                onDismiss = { scrubberVisible = false },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    var datePickerPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var tagEditorPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var deleteConfirmPhoto by remember { mutableStateOf<PhotoEntity?>(null) }

    sheetPhoto?.let { photo ->
        PhotoActionsSheet(
            photo = photo,
            onDismiss = { sheetPhoto = null },
            onToggleFavorite = { viewModel.toggleFavorite(photo) },
            onToggleFlag = { viewModel.toggleFlagged(photo) },
            onChangeDate = { datePickerPhoto = photo },
            onEditTags = { tagEditorPhoto = photo },
            onDelete = { deleteConfirmPhoto = photo }
        )
    }

    datePickerPhoto?.let { photo ->
        DateChangeDialog(
            onDismiss = { datePickerPhoto = null },
            onConfirm = { year, month, day ->
                val dateStr = "%04d:%02d:%02d 12:00:00".format(year, month, day)
                viewModel.enqueueDateChange(photo, dateStr)
                datePickerPhoto = null
            }
        )
    }

    tagEditorPhoto?.let { photo ->
        TagEditorDialog(
            currentTags = photo.tags ?: "",
            onDismiss = { tagEditorPhoto = null },
            onConfirm = { tags ->
                viewModel.enqueueTagChange(photo, tags)
                tagEditorPhoto = null
            }
        )
    }

    deleteConfirmPhoto?.let { photo ->
        DeleteConfirmDialog(
            photoName = "${photo.filenameStem}.${photo.originalExtension}",
            onDismiss = { deleteConfirmPhoto = null },
            onConfirm = {
                viewModel.enqueueDelete(photo)
                deleteConfirmPhoto = null
            }
        )
    }
}

@Composable
private fun YearScrubber(
    yearStats: List<YearStats>,
    onYearSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 30) onDismiss()
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (stats in yearStats) {
            Column(
                modifier = Modifier
                    .clickable { onYearSelected(stats.year) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stats.year.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${stats.thumbnailsDownloaded}/${stats.totalCount}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SyncStatusIndicator(status: SyncStatus, onRetry: () -> Unit) {
    when (status) {
        is SyncStatus.Syncing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(2.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (status.total > 0) "${status.current}/${status.total}" else "Syncing\u2026",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }
        is SyncStatus.Offline -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("Offline", style = MaterialTheme.typography.labelMedium)
            }
        }
        is SyncStatus.Complete -> {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Sync complete",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        is SyncStatus.Error -> {
            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = "Retry sync",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        is SyncStatus.Idle -> {}
    }
}

@Composable
private fun EmptyGalleryState(syncStatus: SyncStatus) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (syncStatus) {
                is SyncStatus.Syncing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Syncing photos from server\u2026",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    if (syncStatus.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${syncStatus.current} / ${syncStatus.total}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No photos found on server", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit
) {
    val datePickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = datePickerState.selectedDateMillis
                if (millis != null) {
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
                    onConfirm(
                        cal.get(java.util.Calendar.YEAR),
                        cal.get(java.util.Calendar.MONTH) + 1,
                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                    )
                }
            }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@Composable
private fun TagEditorDialog(
    currentTags: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentTags) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Tags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    photoName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete photo?") },
        text = { Text("\"$photoName\" will be moved to trash on the server.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
