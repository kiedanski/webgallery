// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data

import android.util.Log
import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.cache.ThumbnailStore
import com.webgallery.data.db.MutationDao
import com.webgallery.data.db.MutationEntity
import com.webgallery.data.db.PhotoDao
import com.webgallery.data.db.PhotoEntity
import com.webgallery.data.db.ThumbnailUpdate
import com.webgallery.data.db.PhotoErrorDao
import com.webgallery.data.db.PhotoErrorEntity
import com.webgallery.data.db.SyncStateDao
import com.webgallery.data.db.SyncStateEntity
import com.webgallery.data.webdav.WebDavClient
import com.webgallery.model.MediaType
import com.webgallery.model.PhotoCounts
import com.webgallery.model.SyncStatus
import com.webgallery.model.YearMonth
import com.webgallery.model.YearStats
import com.webgallery.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PhotoRepository(
    private val webDavClient: WebDavClient,
    private val photoDao: PhotoDao,
    private val syncStateDao: SyncStateDao,
    private val photoErrorDao: PhotoErrorDao,
    private val mutationDao: MutationDao,
    private val thumbnailStore: ThumbnailStore,
    private val imageCacheManager: ImageCacheManager,
    private val settingsRepository: SettingsRepository
) {

    /** Called after a mutation is enqueued. Set by the app layer to trigger sync. */
    var onMutationEnqueued: (() -> Unit)? = null

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    fun setSyncStatus(status: SyncStatus) {
        _syncStatus.value = status
    }

    fun getAllYearMonths(): Flow<List<YearMonth>> = photoDao.getAllYearMonths()
    fun getPhotosByYearMonth(year: Int, month: Int): Flow<List<PhotoEntity>> =
        photoDao.getPhotosByYearMonth(year, month)
    fun getPhotosAndVideosByMonth(year: Int, month: Int): Flow<List<PhotoEntity>> =
        photoDao.getPhotosAndVideosByMonth(year, month)
    fun getCountsByYearMonth(year: Int, month: Int): Flow<PhotoCounts> =
        photoDao.getCountByYearMonth(year, month)
    fun getFavorites(): Flow<List<PhotoEntity>> = photoDao.getFavorites()
    fun getFlaggedPhotos(): Flow<List<PhotoEntity>> = photoDao.getFlaggedPhotos()
    fun getYearStats(): Flow<List<YearStats>> = photoDao.getYearStats()
    fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>> =
        photoErrorDao.getErrorsForPhoto(photoId)
    suspend fun getCacheLimitBytes(): Long = settingsRepository.cacheLimitBytes.first()
    fun search(query: String): Flow<List<PhotoEntity>> = photoDao.search(query)
    fun getPhotoById(id: Long): Flow<PhotoEntity?> = photoDao.getPhotoById(id)
    suspend fun getPhotoOnce(id: Long): PhotoEntity? = photoDao.getPhotoByIdOnce(id)

    suspend fun toggleFlagged(photo: PhotoEntity): Boolean = withContext(Dispatchers.IO) {
        val newState = !photo.isFlagged
        photoDao.updateFlagged(photo.id, newState)
        newState
    }

    fun getPendingMutationCount(): Flow<Int> = mutationDao.getPendingCount()
    fun getPendingPhotoMutations(): Flow<Map<Long, String>> = mutationDao.getPendingPhotoMutations()
        .map { list -> list.associate { it.photoId to it.mutationType } }
    fun getAllMutations(): Flow<List<MutationEntity>> = mutationDao.getAll()

    suspend fun enqueueDateChange(photo: PhotoEntity, exifDateStr: String) = withContext(Dispatchers.IO) {
        val payload = org.json.JSONObject().put("date", exifDateStr).toString()
        mutationDao.insert(
            MutationEntity(
                photoId = photo.id,
                mutationType = MutationEntity.TYPE_CHANGE_DATE,
                payload = payload,
                remotePath = photo.remoteOriginalPath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        onMutationEnqueued?.invoke()
    }

    suspend fun enqueueTagChange(photo: PhotoEntity, tags: String) = withContext(Dispatchers.IO) {
        val payload = org.json.JSONObject().put("tags", tags).toString()
        mutationDao.insert(
            MutationEntity(
                photoId = photo.id,
                mutationType = MutationEntity.TYPE_SET_TAGS,
                payload = payload,
                remotePath = photo.remoteOriginalPath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        // Optimistic local update
        photoDao.updateTags(photo.id, tags.ifBlank { null })
        onMutationEnqueued?.invoke()
    }

    suspend fun enqueueDelete(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        mutationDao.insert(
            MutationEntity(
                photoId = photo.id,
                mutationType = MutationEntity.TYPE_DELETE,
                payload = "{}",
                remotePath = photo.remoteOriginalPath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        // Optimistic local delete
        photoDao.upsertPhoto(photo.copy(isDeleted = true, updatedAt = System.currentTimeMillis()))
        onMutationEnqueued?.invoke()
    }

    suspend fun retryMutation(mutationId: Long) = withContext(Dispatchers.IO) {
        mutationDao.updateStatus(mutationId, MutationEntity.STATUS_PENDING)
    }

    suspend fun discardMutation(mutationId: Long) = withContext(Dispatchers.IO) {
        mutationDao.delete(mutationId)
    }

    suspend fun sync(onDownloadProgress: ((Int, Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (!webDavClient.isConfigured()) {
            _syncStatus.value = SyncStatus.Error("Not configured")
            return@withContext
        }
        _syncStatus.value = SyncStatus.Syncing(0, 0)
        try {
            // Phase 1: Discover years (1 PROPFIND)
            val yearsResult = webDavClient.propfind("/dav/photos/_thumbnails/", depth = 1)
            val yearResources = yearsResult.getOrElse {
                handleSyncError(it)
                return@withContext
            }

            val yearInfos = yearResources
                .filter { it.isCollection }
                .mapNotNull { res ->
                    val year = extractYearFromPath(res.href) ?: return@mapNotNull null
                    Log.d("Sync", "Year $year etag=${res.etag}")
                    YearInfo(year, res.etag)
                }
                .distinctBy { it.year }
                .sortedByDescending { it.year }

            // Phase 2: Discover months only for changed years (parallel)
            // Skip years where ETag or content hash hasn't changed
            val pendingMonths = coroutineScope {
                yearInfos.map { yearInfo ->
                    async {
                        if (!currentCoroutineContext().isActive) return@async emptyList()
                        val yearDirPath = "_thumbnails/${yearInfo.year}/"
                        val yearState = syncStateDao.getByPath(yearDirPath)

                        // Fast path: year-level server ETag matches → skip all months
                        // Never skip current year — new photos may be added frequently
                        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                        if (yearInfo.year != currentYear &&
                            yearState != null && yearState.etag != null && yearInfo.etag != null && yearState.etag == yearInfo.etag) {
                            Log.d("Sync", "Year ${yearInfo.year}: SKIP (etag match)")
                            return@async emptyList()
                        }
                        Log.d("Sync", "Year ${yearInfo.year}: checking months (server=${yearInfo.etag} stored=${yearState?.etag})")

                        val monthsResult = webDavClient.propfind("/dav/photos/_thumbnails/${yearInfo.year}/", depth = 1)
                        val monthResources = monthsResult.getOrNull()
                            ?.filter { it.isCollection }
                            ?: return@async emptyList()

                        // Compute content hash of month directories for this year
                        val monthNames = monthResources.mapNotNull { res ->
                            extractMonthFromPath(res.href, yearInfo.year)?.toString()
                        }
                        val yearContentHash = computeContentHash(monthNames)

                        // Fast path: year content hash matches → no months added/removed, skip
                        // But always check months for current year (new photos in existing months)
                        if (yearInfo.year != currentYear &&
                            yearState != null && yearState.contentHash != null && yearState.contentHash == yearContentHash) {
                            // Update ETag if server started providing one
                            if (yearInfo.etag != null && yearState.etag != yearInfo.etag) {
                                syncStateDao.upsert(yearState.copy(etag = yearInfo.etag, lastSyncedAt = System.currentTimeMillis()))
                            }
                            Log.d("Sync", "Year ${yearInfo.year}: SKIP (content hash match)")
                            return@async emptyList()
                        }

                        // Year changed — store updated state and return months for processing
                        syncStateDao.upsert(
                            SyncStateEntity(
                                directoryPath = yearDirPath,
                                etag = yearInfo.etag,
                                contentHash = yearContentHash,
                                lastSyncedAt = System.currentTimeMillis()
                            )
                        )

                        monthResources
                            .mapNotNull { res ->
                                val month = extractMonthFromPath(res.href, yearInfo.year) ?: return@mapNotNull null
                                MonthInfo(yearInfo.year, month, res.etag)
                            }
                            .sortedByDescending { it.month }
                    }
                }.awaitAll().flatten()
            }

            // Phase 3: Filter to months that actually need processing, then process in parallel
            val processSemaphore = Semaphore(4)
            coroutineScope {
                pendingMonths.map { info ->
                    async {
                        processSemaphore.withPermit {
                            if (!currentCoroutineContext().isActive) return@withPermit
                            val dirPath = "_thumbnails/${info.year}/${info.month.toString().padStart(2, '0')}/"
                            val state = syncStateDao.getByPath(dirPath)

                            // Fast path: server ETag matches → skip entirely (no HTTP, no DB)
                            if (state != null && state.etag != null && info.etag != null && state.etag == info.etag) {
                                Log.d("Sync", "Month ${info.year}/${info.month}: SKIP (etag server=${info.etag} stored=${state.etag})")
                                return@withPermit
                            }
                            Log.d("Sync", "Month ${info.year}/${info.month}: checking (etag server=${info.etag} stored=${state?.etag} hash=${state?.contentHash})")

                            // PROPFIND the thumbnail directory to get file list
                            val monthStr = info.month.toString().padStart(2, '0')
                            val thumbsResult = webDavClient.propfind("/dav/photos/_thumbnails/${info.year}/$monthStr/", depth = 1)
                            val thumbResources = thumbsResult.getOrNull()
                                ?.filter { !it.isCollection }
                                ?: return@withPermit

                            // Compute content hash from sorted thumbnail filenames
                            val contentHash = computeContentHash(thumbResources.map { it.name })

                            // Fast path: client content hash matches → skip (no originals PROPFIND, no DB)
                            if (state != null && state.contentHash != null && state.contentHash == contentHash) {
                                // Update ETag if server started providing one, but skip processing
                                if (info.etag != null && state.etag != info.etag) {
                                    syncStateDao.upsert(state.copy(etag = info.etag, lastSyncedAt = System.currentTimeMillis()))
                                }
                                return@withPermit
                            }

                            // Content changed — do full processing
                            processMonth(info, thumbResources)
                            syncStateDao.upsert(
                                SyncStateEntity(
                                    directoryPath = dirPath,
                                    etag = info.etag,
                                    contentHash = contentHash,
                                    lastSyncedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }.awaitAll()
            }

            // Phase 4: Download missing thumbnails
            downloadMissingThumbnails(onDownloadProgress)

            settingsRepository.setLastSyncTimestamp(System.currentTimeMillis())
            _syncStatus.value = SyncStatus.Complete
        } catch (e: CancellationException) {
            _syncStatus.value = SyncStatus.Idle
            throw e
        } catch (e: WebDavClient.UnauthorizedException) {
            _syncStatus.value = SyncStatus.Error("Authentication failed")
        } catch (e: IOException) {
            _syncStatus.value = SyncStatus.Offline
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Error(e.message ?: "Sync failed")
        }
    }

    private fun handleSyncError(t: Throwable) {
        _syncStatus.value = when (t) {
            is WebDavClient.UnauthorizedException -> SyncStatus.Error("Authentication failed")
            is IOException -> SyncStatus.Offline
            else -> SyncStatus.Error(t.message ?: "Sync failed")
        }
    }

    private fun computeContentHash(filenames: List<String>): String {
        val sorted = filenames.sorted().joinToString("\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(sorted.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun processMonth(info: MonthInfo, thumbResources: List<com.webgallery.data.webdav.DavResource>) {
        val year = info.year
        val month = info.month
        val monthStr = month.toString().padStart(2, '0')
        val originalsResult = webDavClient.propfind("/dav/photos/$year/$monthStr/", depth = 1)
        val originalResources = originalsResult.getOrElse { return }
            .filter { !it.isCollection }

        val originalsByStem = originalResources.associateBy { res ->
            FileUtils.filenameWithoutExtension(res.name).lowercase()
        }

        // Batch read: fetch all existing photos for this month in one query
        val existingByThumbPath = photoDao.getAllForMonth(year, month)
            .associateBy { it.remoteThumbnailPath }

        val toUpsert = mutableListOf<PhotoEntity>()
        val keepIds = mutableListOf<Long>()
        val now = System.currentTimeMillis()

        for (thumb in thumbResources) {
            val stem = FileUtils.filenameWithoutExtension(thumb.name)
            val original = originalsByStem[stem.lowercase()] ?: continue
            val mime = original.contentType ?: guessMime(original.name)
            val mediaType = MediaType.fromMimeType(mime)
            val ext = FileUtils.extensionOf(original.name)
            val remoteThumbPath = "_thumbnails/$year/$monthStr/${thumb.name}"
            val remoteOriginalPath = "$year/$monthStr/${original.name}"

            val existing = existingByThumbPath[remoteThumbPath]
            if (existing == null) {
                toUpsert += PhotoEntity(
                    remoteThumbnailPath = remoteThumbPath,
                    remoteOriginalPath = remoteOriginalPath,
                    year = year,
                    month = month,
                    filenameStem = stem,
                    originalExtension = ext,
                    mimeType = mime,
                    mediaType = mediaType.name,
                    fileSize = original.contentLength ?: 0L,
                    etag = original.etag,
                    lastModified = original.lastModified,
                    thumbnailDownloaded = false,
                    localThumbnailPath = null,
                    isFavorite = false,
                    isFlagged = false,
                    localFullPath = null,
                    localFavoritePath = null,
                    isDeleted = false,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                if (existing.etag != original.etag) {
                    toUpsert += existing.copy(
                        etag = original.etag,
                        fileSize = original.contentLength ?: existing.fileSize,
                        lastModified = original.lastModified,
                        isDeleted = false,
                        updatedAt = now
                    )
                } else if (existing.isDeleted) {
                    toUpsert += existing.copy(isDeleted = false, updatedAt = now)
                }
                keepIds += existing.id
            }
        }

        // Batch write: single transaction for all inserts/updates
        if (toUpsert.isNotEmpty()) {
            photoDao.upsertPhotos(toUpsert)
        }

        // Collect IDs for newly inserted photos (they didn't have IDs before upsert)
        if (keepIds.size < thumbResources.size) {
            val allAfter = photoDao.getAllForMonth(year, month)
            keepIds.clear()
            keepIds += allAfter.filter { !it.isDeleted || toUpsert.any { u -> u.remoteThumbnailPath == it.remoteThumbnailPath } }
                .map { it.id }
        }

        photoDao.markDeletedByMonth(year, month, keepIds)
    }

    private suspend fun downloadMissingThumbnails(onDownloadProgress: ((Int, Int) -> Unit)? = null) = coroutineScope {
        val pending = photoDao.getUnsyncedThumbnails()
        Log.d("Sync", "downloadMissingThumbnails: ${pending.size} pending")
        if (pending.isEmpty()) {
            _syncStatus.value = SyncStatus.Complete
            return@coroutineScope
        }
        val total = pending.size
        val counter = AtomicInteger(0)
        val semaphore = Semaphore(8)
        val diskFull = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastStatusUpdate = java.util.concurrent.atomic.AtomicLong(0)
        val pendingDbUpdates = java.util.concurrent.ConcurrentLinkedQueue<ThumbnailUpdate>()
        val pendingErrors = java.util.concurrent.ConcurrentLinkedQueue<PhotoErrorEntity>()

        _syncStatus.value = SyncStatus.Syncing(0, total)

        // Process in chunks to batch DB writes
        val chunkSize = 50
        for (chunk in pending.chunked(chunkSize)) {
            val jobs = chunk.map { entity ->
                async {
                    semaphore.withPermit {
                        if (!currentCoroutineContext().isActive) return@withPermit
                        if (diskFull.get()) return@withPermit
                        val filename = entity.remoteThumbnailPath.substringAfterLast('/')
                        val target = thumbnailStore.getThumbnailFile(entity.year, entity.month, filename)
                        val res = webDavClient.downloadFile("/dav/photos/${entity.remoteThumbnailPath}", target)
                        if (res.isSuccess) {
                            pendingDbUpdates.add(
                                ThumbnailUpdate(entity.id, true, target.absolutePath)
                            )
                        } else {
                            val err = res.exceptionOrNull()
                            pendingErrors.add(
                                PhotoErrorEntity(
                                    photoId = entity.id,
                                    errorType = "THUMBNAIL_DOWNLOAD",
                                    errorMessage = err?.message ?: "Unknown error",
                                    httpStatus = (err as? WebDavClient.HttpException)?.code,
                                    remotePath = entity.remoteThumbnailPath,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            if (err is IOException && (err.message?.contains("No space", ignoreCase = true) == true ||
                                    err.cause?.message?.contains("No space", ignoreCase = true) == true)) {
                                diskFull.set(true)
                            }
                        }
                        val current = counter.incrementAndGet()
                        // Throttle status updates to at most once per 250ms
                        val now = System.currentTimeMillis()
                        if (now - lastStatusUpdate.get() > 250) {
                            lastStatusUpdate.set(now)
                            _syncStatus.value = SyncStatus.Syncing(current, total)
                            onDownloadProgress?.invoke(current, total)
                        }
                    }
                }
            }
            jobs.awaitAll()

            // Flush batched DB writes after each chunk (single transaction)
            val updates = mutableListOf<ThumbnailUpdate>()
            while (true) { pendingDbUpdates.poll()?.let { updates += it } ?: break }
            if (updates.isNotEmpty()) {
                photoDao.batchUpdateThumbnailDownloaded(updates)
            }

            val errors = mutableListOf<PhotoErrorEntity>()
            while (true) { pendingErrors.poll()?.let { errors += it } ?: break }
            for (error in errors) {
                photoErrorDao.insert(error)
            }

            // Update status after each chunk flush
            _syncStatus.value = SyncStatus.Syncing(counter.get(), total)
        }

        if (diskFull.get()) {
            _syncStatus.value = SyncStatus.Error("Storage full — free up space and try again")
        }
    }

    suspend fun ensureFullImage(
        photo: PhotoEntity,
        cacheLimitBytes: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val existing = photo.localFullPath?.let { File(it) }
        if (existing != null && existing.exists()) {
            imageCacheManager.touch(existing)
            return@withContext existing
        }
        val filename = "${photo.filenameStem}.${photo.originalExtension}"
        val target = imageCacheManager.getCachedFile(photo.year, photo.month, filename)
        val result = webDavClient.downloadFile("/dav/photos/${photo.remoteOriginalPath}", target, onProgress)
        result.fold(
            onSuccess = {
                photoDao.updateLocalFullPath(photo.id, target.absolutePath)
                evictCacheIfNeeded(cacheLimitBytes)
                target
            },
            onFailure = { err ->
                photoErrorDao.insert(
                    PhotoErrorEntity(
                        photoId = photo.id,
                        errorType = "FULL_IMAGE_DOWNLOAD",
                        errorMessage = err.message ?: "Unknown error",
                        httpStatus = (err as? WebDavClient.HttpException)?.code,
                        remotePath = photo.remoteOriginalPath,
                        timestamp = System.currentTimeMillis()
                    )
                )
                null
            }
        )
    }

    suspend fun evictCacheIfNeeded(limitBytes: Long) = withContext(Dispatchers.IO) {
        val evicted = imageCacheManager.evictIfNeeded(limitBytes)
        if (evicted.isNotEmpty()) {
            photoDao.clearAllLocalFullPaths()
        }
    }

    suspend fun toggleFavorite(photo: PhotoEntity, onVideoProgress: ((Long, Long) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        val filename = "${photo.filenameStem}.${photo.originalExtension}"
        if (photo.isFavorite) {
            photo.localFavoritePath?.let {
                val f = File(it)
                if (f.exists()) f.delete()
            }
            photoDao.updateFavorite(photo.id, false, null)
            false
        } else {
            val favTarget = imageCacheManager.getFavoriteFile(photo.year, photo.month, filename)
            val cached = photo.localFullPath?.let { File(it) }
            if (cached != null && cached.exists()) {
                cached.copyTo(favTarget, overwrite = true)
            } else {
                val res = webDavClient.downloadFile("/dav/photos/${photo.remoteOriginalPath}", favTarget, onVideoProgress)
                if (res.isFailure) {
                    return@withContext false
                }
                if (photo.mediaType == MediaType.PHOTO.name) {
                    val cacheTarget = imageCacheManager.getCachedFile(photo.year, photo.month, filename)
                    favTarget.copyTo(cacheTarget, overwrite = true)
                    photoDao.updateLocalFullPath(photo.id, cacheTarget.absolutePath)
                }
            }
            photoDao.updateFavorite(photo.id, true, favTarget.absolutePath)
            true
        }
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        photoDao.deleteAllPhotos()
        syncStateDao.deleteAll()
        thumbnailStore.deleteAllThumbnails()
        imageCacheManager.clearCache()
        File(imageCacheManager.favoritesRoot().absolutePath).deleteRecursively()
        imageCacheManager.favoritesRoot().mkdirs()
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageCacheManager.clearCache()
        photoDao.clearAllLocalFullPaths()
    }

    suspend fun forceSyncStateReset() = withContext(Dispatchers.IO) {
        syncStateDao.deleteAll()
    }

    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) {
        thumbnailStore.deleteAllThumbnails()
        photoDao.clearAllThumbnailFlags()
        syncStateDao.deleteAll()
    }

    private fun extractYearFromPath(path: String): Int? {
        val cleaned = path.trim('/').removePrefix("_thumbnails/").removeSuffix("/")
        val name = cleaned.substringAfterLast('/')
        return name.toIntOrNull()?.takeIf { it in 1900..3000 }
    }

    private fun extractMonthFromPath(path: String, year: Int): Int? {
        val cleaned = path.trim('/').removePrefix("_thumbnails/").removeSuffix("/")
        val name = cleaned.substringAfterLast('/')
        return name.toIntOrNull()?.takeIf { it in 1..12 }
    }

    private fun guessMime(filename: String): String {
        val ext = FileUtils.extensionOf(filename).lowercase()
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
            else -> "application/octet-stream"
        }
    }

    private data class YearInfo(val year: Int, val etag: String?)
    private data class MonthInfo(val year: Int, val month: Int, val etag: String?)
}
