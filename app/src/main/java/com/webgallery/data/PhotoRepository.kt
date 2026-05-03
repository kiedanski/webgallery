package com.webgallery.data

import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.cache.ThumbnailStore
import com.webgallery.data.db.PhotoDao
import com.webgallery.data.db.PhotoEntity
import com.webgallery.data.db.SyncStateDao
import com.webgallery.data.db.SyncStateEntity
import com.webgallery.data.webdav.WebDavClient
import com.webgallery.model.MediaType
import com.webgallery.model.PhotoCounts
import com.webgallery.model.SyncStatus
import com.webgallery.model.YearMonth
import com.webgallery.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val thumbnailStore: ThumbnailStore,
    private val imageCacheManager: ImageCacheManager,
    private val settingsRepository: SettingsRepository
) {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    fun getAllYearMonths(): Flow<List<YearMonth>> = photoDao.getAllYearMonths()
    fun getPhotosByYearMonth(year: Int, month: Int): Flow<List<PhotoEntity>> =
        photoDao.getPhotosByYearMonth(year, month)
    fun getPhotosAndVideosByMonth(year: Int, month: Int): Flow<List<PhotoEntity>> =
        photoDao.getPhotosAndVideosByMonth(year, month)
    fun getCountsByYearMonth(year: Int, month: Int): Flow<PhotoCounts> =
        photoDao.getCountByYearMonth(year, month)
    fun getFavorites(): Flow<List<PhotoEntity>> = photoDao.getFavorites()
    fun getPhotoById(id: Long): Flow<PhotoEntity?> = photoDao.getPhotoById(id)
    suspend fun getPhotoOnce(id: Long): PhotoEntity? = photoDao.getPhotoByIdOnce(id)

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!webDavClient.isConfigured()) {
            _syncStatus.value = SyncStatus.Error("Not configured")
            return@withContext
        }
        _syncStatus.value = SyncStatus.Syncing(0, 0)
        try {
            // Phase 1: Discover years
            val yearsResult = webDavClient.propfind("/dav/photos/_thumbnails/", depth = 1)
            val yearResources = yearsResult.getOrElse {
                handleSyncError(it)
                return@withContext
            }

            val yearDirs = yearResources
                .filter { it.isCollection }
                .mapNotNull { extractYearFromPath(it.href) }
                .distinct()
                .sortedDescending()

            val pendingMonths = mutableListOf<MonthInfo>()

            for (year in yearDirs) {
                if (!currentCoroutineContext().isActive) return@withContext
                val monthsResult = webDavClient.propfind("/dav/photos/_thumbnails/$year/", depth = 1)
                val monthResources = monthsResult.getOrElse {
                    handleSyncError(it)
                    return@withContext
                }
                val monthDirs = monthResources
                    .filter { it.isCollection }
                    .mapNotNull { res ->
                        val month = extractMonthFromPath(res.href, year) ?: return@mapNotNull null
                        MonthInfo(year, month, res.etag)
                    }
                    .sortedByDescending { it.month }
                pendingMonths += monthDirs
            }

            // Phase 2 & 3 & 4: For each month, decide whether to sync
            for (info in pendingMonths) {
                if (!currentCoroutineContext().isActive) return@withContext
                val dirPath = "_thumbnails/${info.year}/${info.month.toString().padStart(2, '0')}/"
                val state = syncStateDao.getByPath(dirPath)
                if (state != null && state.etag != null && info.etag != null && state.etag == info.etag) {
                    continue
                }
                processMonth(info)
                syncStateDao.upsert(
                    SyncStateEntity(
                        directoryPath = dirPath,
                        etag = info.etag,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }

            // Phase 5: Download missing thumbnails
            downloadMissingThumbnails()

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

    private suspend fun processMonth(info: MonthInfo) {
        val year = info.year
        val month = info.month
        val monthStr = month.toString().padStart(2, '0')
        val thumbsResult = webDavClient.propfind("/dav/photos/_thumbnails/$year/$monthStr/", depth = 1)
        val thumbResources = thumbsResult.getOrElse { return }
            .filter { !it.isCollection }
        val originalsResult = webDavClient.propfind("/dav/photos/$year/$monthStr/", depth = 1)
        val originalResources = originalsResult.getOrElse { return }
            .filter { !it.isCollection }

        val originalsByStem = originalResources.associateBy { res ->
            FileUtils.filenameWithoutExtension(res.name).lowercase()
        }

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

            val existing = photoDao.findByThumbnailPath(remoteThumbPath)
            if (existing == null) {
                val entity = PhotoEntity(
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
                    localFullPath = null,
                    localFavoritePath = null,
                    isDeleted = false,
                    createdAt = now,
                    updatedAt = now
                )
                photoDao.upsertPhoto(entity)
                val saved = photoDao.findByThumbnailPath(remoteThumbPath)
                saved?.id?.let { keepIds += it }
            } else {
                if (existing.etag != original.etag) {
                    photoDao.upsertPhoto(
                        existing.copy(
                            etag = original.etag,
                            fileSize = original.contentLength ?: existing.fileSize,
                            lastModified = original.lastModified,
                            isDeleted = false,
                            updatedAt = now
                        )
                    )
                } else if (existing.isDeleted) {
                    photoDao.upsertPhoto(existing.copy(isDeleted = false, updatedAt = now))
                }
                keepIds += existing.id
            }
        }

        photoDao.markDeletedByMonth(year, month, keepIds)
    }

    private suspend fun downloadMissingThumbnails() = coroutineScope {
        val pending = photoDao.getUnsyncedThumbnails()
        if (pending.isEmpty()) {
            _syncStatus.value = SyncStatus.Complete
            return@coroutineScope
        }
        val total = pending.size
        val counter = AtomicInteger(0)
        val semaphore = Semaphore(4)

        _syncStatus.value = SyncStatus.Syncing(0, total)

        val jobs = pending.map { entity ->
            async {
                semaphore.withPermit {
                    if (!currentCoroutineContext().isActive) return@withPermit
                    val filename = entity.remoteThumbnailPath.substringAfterLast('/')
                    val target = thumbnailStore.getThumbnailFile(entity.year, entity.month, filename)
                    val res = webDavClient.downloadFile("/dav/photos/${entity.remoteThumbnailPath}", target)
                    if (res.isSuccess) {
                        photoDao.updateThumbnailDownloaded(entity.id, true, target.absolutePath)
                    }
                    val current = counter.incrementAndGet()
                    _syncStatus.value = SyncStatus.Syncing(current, total)
                }
            }
        }
        jobs.awaitAll()
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
            onFailure = { null }
        )
    }

    suspend fun evictCacheIfNeeded(limitBytes: Long) = withContext(Dispatchers.IO) {
        val evicted = imageCacheManager.evictIfNeeded(limitBytes)
        // The simplest path: any photo whose local_full_path no longer exists gets cleared.
        // We do a broad sweep via the DAO instead of per-row updates.
        if (evicted.isNotEmpty()) {
            // Clear stale references
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
        // favorites dir
        File(imageCacheManager.favoritesRoot().absolutePath).deleteRecursively()
        imageCacheManager.favoritesRoot().mkdirs()
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        imageCacheManager.clearCache()
        photoDao.clearAllLocalFullPaths()
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

    private data class MonthInfo(val year: Int, val month: Int, val etag: String?)
}
