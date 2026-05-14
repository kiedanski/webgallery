// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.upload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.webgallery.data.db.UploadEntity
import com.webgallery.data.db.WatchedFolderEntity
import com.webgallery.ui.AppViewModelFactory
import com.webgallery.util.FileUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchedFoldersScreen(
    factory: AppViewModelFactory,
    onClose: () -> Unit
) {
    val viewModel: WatchedFoldersViewModel = viewModel(factory = factory)
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var showAddOptions by remember { mutableStateOf(false) }
    var browsingFolder by remember { mutableStateOf<WatchedFolderEntity?>(null) }
    val context = LocalContext.current
    var hasMediaPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasMediaPermission = grants.values.any { it }
    }

    // Request permissions on first open
    LaunchedEffect(Unit) {
        if (!hasMediaPermission) {
            if (Build.VERSION.SDK_INT >= 33) {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                ))
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = uriToPath(it)
            if (path != null) {
                val name = path.substringAfterLast('/')
                viewModel.addFolder(path, name)
            }
        }
    }

    // Handle system delete confirmation (Android 11+)
    val deleteRequestSender by viewModel.deleteRequest.collectAsStateWithLifecycle()
    val deleteConfirmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeleteRequestResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(deleteRequestSender) {
        deleteRequestSender?.let { sender ->
            deleteConfirmLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val cleanupCount by viewModel.needsCleanupCount.collectAsStateWithLifecycle()
                    Column {
                        Text(if (browsingFolder != null) browsingFolder!!.displayName else "Watched Folders")
                        if (browsingFolder == null && cleanupCount > 0) {
                            Text(
                                "$cleanupCount uploaded, tap broom to free space",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (browsingFolder != null) browsingFolder = null else onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (browsingFolder == null) {
                        IconButton(onClick = { viewModel.cleanupUploadedFiles() }) {
                            Icon(Icons.Filled.CleaningServices, contentDescription = "Clean up uploaded files")
                        }
                        IconButton(onClick = { viewModel.triggerUpload() }) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = "Upload now")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (browsingFolder == null) {
                FloatingActionButton(onClick = { showAddOptions = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add folder")
                }
            }
        }
    ) { padding ->
        if (browsingFolder != null) {
            FolderBrowser(
                folder = browsingFolder!!,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("No watched folders", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add folders to automatically upload photos to your server",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(folders, key = { it.id }) { folder ->
                    FolderCard(
                        folder = folder,
                        viewModel = viewModel,
                        onBrowse = { browsingFolder = folder },
                        onRemove = { viewModel.removeFolder(folder.id) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAddOptions) {
        AddFolderDialog(
            onDismiss = { showAddOptions = false },
            onBrowse = {
                showAddOptions = false
                folderPickerLauncher.launch(null)
            },
            onAddSuggestion = { path, name ->
                viewModel.addFolder(path, name)
                showAddOptions = false
            }
        )
    }
}

@Composable
private fun FolderCard(
    folder: WatchedFolderEntity,
    viewModel: WatchedFoldersViewModel,
    onBrowse: () -> Unit,
    onRemove: () -> Unit
) {
    val uploads by viewModel.uploadsForFolder(folder.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val pending = uploads.count { it.status == UploadEntity.STATUS_PENDING }
    val uploaded = uploads.count { it.status in listOf(UploadEntity.STATUS_UPLOADED, UploadEntity.STATUS_DELETED) }
    val failed = uploads.count { it.status == UploadEntity.STATUS_FAILED }
    var dirExists by remember { mutableStateOf(true) }
    var fileCount by remember { mutableStateOf(0) }
    // Re-count files when upload statuses change (e.g., after cleanup deletes local files)
    val uploadCount = uploads.size
    LaunchedEffect(folder.path, uploadCount) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dirExists = File(folder.path).exists()
            fileCount = File(folder.path).listFiles()?.count { it.isFile } ?: 0
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onBrowse),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        folder.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = folder.enabled,
                    onCheckedChange = { viewModel.toggleEnabled(folder.id, it) }
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete after upload", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = folder.deleteAfterUpload,
                    onCheckedChange = { viewModel.toggleDeleteAfterUpload(folder.id, it) }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$fileCount files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pending > 0) Text("$pending pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                if (uploaded > 0) Text("$uploaded uploaded", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                if (failed > 0) Text("$failed failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (!dirExists) Text("Folder not found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderBrowser(
    folder: WatchedFolderEntity,
    viewModel: WatchedFoldersViewModel,
    modifier: Modifier = Modifier
) {
    val files = remember(folder.path) { viewModel.getFolderContents(folder.path) }
    val uploads by viewModel.uploadsForFolder(folder.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val uploadsByPath = remember(uploads) { uploads.associateBy { it.localPath } }

    if (files.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No media files found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(files, key = { it.path }) { fileInfo ->
                val upload = uploadsByPath[fileInfo.path]
                FolderFileItem(fileInfo = fileInfo, uploadStatus = upload?.status)
            }
        }
    }
}

@Composable
private fun FolderFileItem(
    fileInfo: FolderFileInfo,
    uploadStatus: String?
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
    ) {
        AsyncImage(
            model = File(fileInfo.path),
            contentDescription = fileInfo.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Upload status overlay
        val statusIcon = when (uploadStatus) {
            UploadEntity.STATUS_UPLOADED, UploadEntity.STATUS_DELETED ->
                Icons.Outlined.CheckCircle to Color(0xFF4CAF50)
            UploadEntity.STATUS_PENDING ->
                Icons.Outlined.HourglassEmpty to Color.White
            UploadEntity.STATUS_UPLOADING ->
                Icons.Outlined.CloudUpload to Color.White
            UploadEntity.STATUS_FAILED ->
                Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            else -> null
        }

        if (statusIcon != null) {
            Icon(
                imageVector = statusIcon.first,
                contentDescription = uploadStatus,
                tint = statusIcon.second,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }

        if (fileInfo.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun AddFolderDialog(
    onDismiss: () -> Unit,
    onBrowse: () -> Unit,
    onAddSuggestion: (path: String, name: String) -> Unit
) {
    val suggestions = listOf(
        "/storage/emulated/0/DCIM/Camera" to "Camera",
        "/storage/emulated/0/Pictures/Screenshots" to "Screenshots",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" to "WhatsApp Images",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to "WhatsApp Video",
    )
    val availableSuggestions = suggestions.filter { File(it.first).exists() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add watched folder") },
        text = {
            Column {
                TextButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse for folder\u2026")
                }
                if (availableSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Quick add:", style = MaterialTheme.typography.labelMedium)
                    availableSuggestions.forEach { (sugPath, sugName) ->
                        TextButton(
                            onClick = { onAddSuggestion(sugPath, sugName) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(sugName, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun uriToPath(uri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    // Format: "primary:DCIM/Camera" or "home:Documents"
    val parts = docId.split(":")
    if (parts.size < 2) return null
    val type = parts[0]
    val relativePath = parts[1]
    return when (type) {
        "primary" -> "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
        else -> "/storage/$type/$relativePath"
    }
}
