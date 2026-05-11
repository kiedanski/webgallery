// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.webgallery.model.PhotoCounts
import com.webgallery.model.YearMonth
import com.webgallery.model.YearStats
import kotlinx.coroutines.flow.Flow

data class ThumbnailUpdate(val id: Long, val downloaded: Boolean, val localPath: String?, val now: Long = System.currentTimeMillis())

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos WHERE is_deleted = 0 AND year = :year AND month = :month ORDER BY filename_stem ASC")
    fun getPhotosByYearMonth(year: Int, month: Int): Flow<List<PhotoEntity>>

    @Query("SELECT DISTINCT year, month FROM photos WHERE is_deleted = 0 ORDER BY year DESC, month DESC")
    fun getAllYearMonths(): Flow<List<YearMonth>>

    @Query("SELECT * FROM photos WHERE is_favorite = 1 AND is_deleted = 0 ORDER BY year DESC, month DESC, filename_stem ASC")
    fun getFavorites(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE is_flagged = 1 AND is_deleted = 0 ORDER BY updated_at DESC")
    fun getFlaggedPhotos(): Flow<List<PhotoEntity>>

    @Query("UPDATE photos SET is_flagged = :isFlagged, updated_at = :now WHERE id = :id")
    suspend fun updateFlagged(id: Long, isFlagged: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE photos SET tags = :tags, updated_at = :now WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    fun getPhotoById(id: Long): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getPhotoByIdOnce(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE is_deleted = 0 AND year = :year AND month = :month AND media_type = 'PHOTO' ORDER BY filename_stem ASC")
    fun getPhotosByMonth(year: Int, month: Int): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE is_deleted = 0 AND year = :year AND month = :month ORDER BY filename_stem ASC")
    fun getPhotosAndVideosByMonth(year: Int, month: Int): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE thumbnail_downloaded = 0 AND is_deleted = 0")
    suspend fun getUnsyncedThumbnails(): List<PhotoEntity>

    @Query("""
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN media_type = 'PHOTO' THEN 1 ELSE 0 END), 0) AS photoCount,
               COALESCE(SUM(CASE WHEN media_type = 'VIDEO' THEN 1 ELSE 0 END), 0) AS videoCount
        FROM photos WHERE is_deleted = 0 AND year = :year AND month = :month
    """)
    fun getCountByYearMonth(year: Int, month: Int): Flow<PhotoCounts>

    @Query("""
        SELECT year,
               COUNT(*) AS totalCount,
               COALESCE(SUM(CASE WHEN thumbnail_downloaded = 1 THEN 1 ELSE 0 END), 0) AS thumbnailsDownloaded
        FROM photos WHERE is_deleted = 0
        GROUP BY year ORDER BY year DESC
    """)
    fun getYearStats(): Flow<List<YearStats>>

    @Query("SELECT * FROM photos WHERE remote_thumbnail_path = :path LIMIT 1")
    suspend fun findByThumbnailPath(path: String): PhotoEntity?

    @Upsert
    suspend fun upsertPhoto(photo: PhotoEntity)

    @Upsert
    suspend fun upsertPhotos(photos: List<PhotoEntity>)

    @Query("SELECT * FROM photos WHERE year = :year AND month = :month")
    suspend fun getAllForMonth(year: Int, month: Int): List<PhotoEntity>

    @Transaction
    suspend fun batchUpdateThumbnailDownloaded(updates: List<ThumbnailUpdate>) {
        for (u in updates) updateThumbnailDownloaded(u.id, u.downloaded, u.localPath, u.now)
    }

    @Query("UPDATE photos SET thumbnail_downloaded = :downloaded, local_thumbnail_path = :localPath, updated_at = :now WHERE id = :id")
    suspend fun updateThumbnailDownloaded(id: Long, downloaded: Boolean, localPath: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE photos SET is_favorite = :isFavorite, local_favorite_path = :localFavoritePath, updated_at = :now WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean, localFavoritePath: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE photos SET local_full_path = :localPath, updated_at = :now WHERE id = :id")
    suspend fun updateLocalFullPath(id: Long, localPath: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE photos SET is_deleted = 1, updated_at = :now WHERE year = :year AND month = :month AND id NOT IN (:keepIds)")
    suspend fun markDeletedByMonthInternal(year: Int, month: Int, keepIds: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE photos SET is_deleted = 1, updated_at = :now WHERE year = :year AND month = :month")
    suspend fun markAllDeletedInMonth(year: Int, month: Int, now: Long = System.currentTimeMillis())

    suspend fun markDeletedByMonth(year: Int, month: Int, keepIds: List<Long>) {
        if (keepIds.isEmpty()) markAllDeletedInMonth(year, month)
        else markDeletedByMonthInternal(year, month, keepIds)
    }

    @Query("UPDATE photos SET local_full_path = NULL WHERE local_full_path IS NOT NULL")
    suspend fun clearAllLocalFullPaths()

    @Query("UPDATE photos SET thumbnail_downloaded = 0, local_thumbnail_path = NULL")
    suspend fun clearAllThumbnailFlags()

    @Query("DELETE FROM photos")
    suspend fun deleteAllPhotos()
}
