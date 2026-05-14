// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data

import com.webgallery.data.db.UploadDao
import com.webgallery.data.db.UploadEntity
import com.webgallery.data.db.WatchedFolderDao
import java.io.File

class FolderScanner(
    private val uploadDao: UploadDao,
    private val watchedFolderDao: WatchedFolderDao
) {
    companion object {
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif",
            "mp4", "mov", "mkv", "webm", "avi", "3gp"
        )
    }

    suspend fun scanAll(): List<UploadEntity> {
        val folders = watchedFolderDao.getEnabled()
        val newFiles = mutableListOf<UploadEntity>()
        for (folder in folders) {
            newFiles += scanFolder(folder.id, folder.path)
        }
        return newFiles
    }

    private suspend fun scanFolder(folderId: Long, path: String): List<UploadEntity> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val knownPaths = uploadDao.getKnownPathsForFolder(folderId).toSet()
        val now = System.currentTimeMillis()
        val newFiles = mutableListOf<UploadEntity>()

        dir.walkTopDown().filter { f ->
            f.isFile && isMediaFile(f) && f.absolutePath !in knownPaths
        }.forEach { file ->
            val entity = UploadEntity(
                folderId = folderId,
                localPath = file.absolutePath,
                fileName = file.name,
                fileSize = file.length(),
                mimeType = guessMimeType(file),
                createdAt = now
            )
            val id = uploadDao.insert(entity)
            if (id > 0) {
                newFiles += entity.copy(id = id)
            }
        }

        return newFiles
    }

    private fun isMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    private fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}
