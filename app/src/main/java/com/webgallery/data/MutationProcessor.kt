// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data

import android.util.Log
import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.db.MutationDao
import com.webgallery.data.db.MutationEntity
import com.webgallery.data.db.PhotoDao
import com.webgallery.data.webdav.WebDavClient
import org.json.JSONObject

class MutationProcessor(
    private val webDavClient: WebDavClient,
    private val photoDao: PhotoDao,
    private val mutationDao: MutationDao,
    private val settingsRepository: SettingsRepository,
    private val photoRepository: PhotoRepository,
    private val imageCacheManager: ImageCacheManager
) {
    companion object {
        private const val TAG = "MutationProcessor"
        const val FRESHNESS_THRESHOLD_MS = 10 * 60 * 1000L
    }

    suspend fun processQueue() {
        val pending = mutationDao.getPending()
        if (pending.isEmpty()) return

        Log.d(TAG, "Processing ${pending.size} pending mutations")

        // Check sync freshness
        val lastSync = settingsRepository.getLastSyncTimestamp()
        if (System.currentTimeMillis() - lastSync > FRESHNESS_THRESHOLD_MS) {
            Log.d(TAG, "Sync is stale, syncing first")
            photoRepository.sync()
        }

        for (mutation in pending) {
            mutationDao.updateStatus(mutation.id, MutationEntity.STATUS_PROCESSING)
            try {
                when (mutation.mutationType) {
                    MutationEntity.TYPE_CHANGE_DATE -> processDateChange(mutation)
                    MutationEntity.TYPE_SET_TAGS -> processTagChange(mutation)
                    MutationEntity.TYPE_DELETE -> processDelete(mutation)
                    else -> Log.w(TAG, "Unknown mutation type: ${mutation.mutationType}")
                }
                mutationDao.delete(mutation.id)
                Log.d(TAG, "Mutation ${mutation.id} (${mutation.mutationType}) completed")
            } catch (e: Exception) {
                Log.e(TAG, "Mutation ${mutation.id} failed", e)
                mutationDao.updateStatus(mutation.id, MutationEntity.STATUS_FAILED, e.message)
            }
        }
    }

    private suspend fun processDateChange(mutation: MutationEntity) {
        val photo = photoDao.getPhotoByIdOnce(mutation.photoId)
            ?: throw IllegalStateException("Photo ${mutation.photoId} not found")
        val payload = JSONObject(mutation.payload)
        val newDateExif = payload.getString("date")

        // Download the full image to a temp file
        val filename = "${photo.filenameStem}.${photo.originalExtension}"
        val tempFile = imageCacheManager.getCachedFile(photo.year, photo.month, "$filename.exif_tmp")
        val downloadResult = webDavClient.downloadFile("/dav/photos/${photo.remoteOriginalPath}", tempFile)
        downloadResult.getOrThrow()

        try {
            // Modify EXIF DateTimeOriginal
            if (!ExifEditor.writeDate(tempFile, newDateExif)) {
                throw IllegalStateException("Failed to write EXIF date to $filename")
            }

            // PUT back to same remote path — server handles reorganization
            val contentType = photo.mimeType.ifEmpty { "application/octet-stream" }
            webDavClient.putFile("/dav/photos/${photo.remoteOriginalPath}", tempFile, contentType).getOrThrow()
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun processTagChange(mutation: MutationEntity) {
        val payload = JSONObject(mutation.payload)
        val tags = payload.getString("tags")

        val props = if (tags.isBlank()) {
            webDavClient.proppatch(
                "/dav/photos/${mutation.remotePath}",
                removeProperties = listOf("tags")
            )
        } else {
            webDavClient.proppatch(
                "/dav/photos/${mutation.remotePath}",
                setProperties = mapOf("tags" to tags)
            )
        }
        props.getOrThrow()

        // Update local tags
        photoDao.updateTags(mutation.photoId, tags.ifBlank { null })
    }

    private suspend fun processDelete(mutation: MutationEntity) {
        val result = webDavClient.delete("/dav/photos/${mutation.remotePath}")
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            // 404 = already gone, treat as success
            if (err is WebDavClient.HttpException && err.code == 404) {
                Log.d(TAG, "DELETE 404 for ${mutation.remotePath} — already gone")
            } else {
                throw err!!
            }
        }
    }
}
