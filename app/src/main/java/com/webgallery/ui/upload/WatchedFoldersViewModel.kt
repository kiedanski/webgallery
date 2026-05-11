// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.upload

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.db.UploadDao
import com.webgallery.data.db.UploadEntity
import com.webgallery.data.db.WatchedFolderDao
import com.webgallery.data.db.WatchedFolderEntity
import com.webgallery.sync.UploadService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class FolderFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val isVideo: Boolean,
    val uploadStatus: String?  // null = not tracked, or UploadEntity status
)

class WatchedFoldersViewModel(
    private val watchedFolderDao: WatchedFolderDao,
    private val uploadDao: UploadDao,
    private val app: Application
) : ViewModel() {

    val folders: StateFlow<List<WatchedFolderEntity>> = watchedFolderDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                    uploadStatus = null  // populated by UI from uploads flow
                )
            }
            ?: emptyList()
    }
}
