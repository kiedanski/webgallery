package com.webgallery.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.webgallery.model.SyncStatus
import com.webgallery.ui.AppViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    factory: AppViewModelFactory,
    onPhotoClick: (Long, Boolean) -> Unit
) {
    val viewModel: GalleryViewModel = viewModel(factory = factory)
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.triggerInitialSync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebGallery") },
                actions = { SyncStatusIndicator(syncStatus, onRetry = { viewModel.triggerSync() }) }
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
                PhotoGrid(
                    sections = sections,
                    countsFor = { y, m -> viewModel.getCountsForMonth(y, m) },
                    onPhotoClick = onPhotoClick,
                    modifier = Modifier.fillMaxSize()
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
                    text = if (status.total > 0) "${status.current}/${status.total}" else "Syncing…",
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
                        text = "Syncing photos from server...",
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
