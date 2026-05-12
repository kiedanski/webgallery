// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.upload

import android.app.Application
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.db.UploadDao
import com.webgallery.data.db.UploadEntity
import com.webgallery.data.db.WatchedFolderDao
import com.webgallery.data.db.WatchedFolderEntity
import com.webgallery.sync.UploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FolderFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val isVideo: Boolean,
    val uploadStatus: String?
)

class WatchedFoldersViewModel(
    private val watchedFolderDao: WatchedFolderDao,
    private val uploadDao: UploadDao,
    private val app: Application
) : ViewModel() {

    val folders: StateFlow<List<WatchedFolderEntity>> = watchedFolderDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Emits an IntentSender when cleanup needs user confirmation (Android 11+)
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest: StateFlow<IntentSender?> = _deleteRequest.asStateFlow()

    // Track which upload IDs are pending deletion confirmation
    private var pendingDeleteIds: List<Long> = emptyList()

    fun uploadsForFolder(folderId: Long): Flow<List<UploadEntity>> =
        uploadDao.getByFolder(folderId)

    fun addFolder(path: String, displayName: String) {
        viewModelScope.launch {
            watchedFolderDao.insert(
                WatchedFolderEntity(
                    path = path,
                    displayName = displayName,
                    createdAt = System.currentTimeMillis()
                )
            )
            UploadService.start(app)
        }
    }

    fun removeFolder(folderId: Long) {
        viewModelScope.launch {
            uploadDao.deleteByFolder(folderId)
            watchedFolderDao.delete(folderId)
        }
    }

    fun toggleEnabled(folderId: Long, enabled: Boolean) {
        viewModelScope.launch { watchedFolderDao.setEnabled(folderId, enabled) }
    }

    fun toggleDeleteAfterUpload(folderId: Long, delete: Boolean) {
        viewModelScope.launch { watchedFolderDao.setDeleteAfterUpload(folderId, delete) }
    }

    fun triggerUpload() {
        UploadService.start(app)
    }

    fun cleanupUploadedFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val uploaded = uploadDao.getUploadedNotDeleted()
                if (uploaded.isEmpty()) {
                    Log.d(TAG, "No uploaded files to clean up")
                    return@withContext
                }

                // First pass: mark entries whose files are already gone
                val stillExist = mutableListOf<UploadEntity>()
                for (entry in uploaded) {
                    if (!File(entry.localPath).exists()) {
                        uploadDao.markDeleted(entry.id)
                    } else {
                        stillExist.add(entry)
                    }
                }

                if (stillExist.isEmpty()) {
                    Log.d(TAG, "All uploaded files already removed")
                    return@withContext
                }

                // Resolve MediaStore URIs for remaining files
                val urisAndIds = stillExist.mapNotNull { entry ->
                    val uri = resolveMediaUri(entry.localPath)
                    if (uri != null) uri to entry.id else null
                }

                // Files not found in MediaStore — try direct File.delete()
                val notInMediaStore = stillExist.filter { entry ->
                    urisAndIds.none { it.second == entry.id }
                }
                for (entry in notInMediaStore) {
                    val f = File(entry.localPath)
                    if (f.delete()) {
                        uploadDao.markDeleted(entry.id)
                        Log.d(TAG, "Direct-deleted ${entry.fileName}")
                    }
                }

                if (urisAndIds.isEmpty()) {
                    Log.d(TAG, "No MediaStore URIs to delete")
                    return@withContext
                }

                if (Build.VERSION.SDK_INT >= 30) {
                    // Android 11+: batch delete request with system confirmation
                    val uris = urisAndIds.map { it.first }
                    pendingDeleteIds = urisAndIds.map { it.second }
                    val intent = MediaStore.createDeleteRequest(app.contentResolver, uris)
                    _deleteRequest.value = intent.intentSender
                } else {
                    for ((uri, id) in urisAndIds) {
                        val deleted = app.contentResolver.delete(uri, null, null)
                        if (deleted > 0) uploadDao.markDeleted(id)
                    }
                    Log.d(TAG, "Cleaned up ${urisAndIds.size} files")
                }
            }
        }
    }

    fun onDeleteRequestResult(granted: Boolean) {
        _deleteRequest.value = null
        if (granted) {
            viewModelScope.launch {
                for (id in pendingDeleteIds) {
                    uploadDao.markDeleted(id)
                }
                Log.d(TAG, "User approved deletion of ${pendingDeleteIds.size} files")
                pendingDeleteIds = emptyList()
            }
        } else {
            Log.d(TAG, "User denied deletion")
            pendingDeleteIds = emptyList()
        }
    }

    private fun resolveMediaUri(filePath: String): Uri? {
        val resolver = app.contentResolver
        val ext = filePath.substringAfterLast('.').lowercase()
        val videoExts = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp")
        val collection = if (ext in videoExts)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val args = arrayOf(filePath)
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    fun getFolderContents(path: String): List<FolderFileInfo> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val mediaExts = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif",
            "mp4", "mov", "mkv", "webm", "avi", "3gp")
        val videoExts = setOf("mp4", "mov", "mkv", "webm", "avi", "3gp")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in mediaExts }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                FolderFileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    isVideo = file.extension.lowercase() in videoExts,
                    uploadStatus = null
                )
            }
            ?: emptyList()
    }

    companion object {
        private const val TAG = "WatchedFolders"
    }
}
