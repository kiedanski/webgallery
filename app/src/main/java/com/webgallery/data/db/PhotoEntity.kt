// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.webgallery.model.MediaType
import com.webgallery.model.Photo

@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["year", "month"], name = "idx_photos_year_month"),
        Index(value = ["is_favorite"], name = "idx_photos_is_favorite"),
        Index(value = ["is_flagged"], name = "idx_photos_is_flagged"),
        Index(value = ["is_deleted"], name = "idx_photos_is_deleted"),
        Index(value = ["remote_thumbnail_path"], unique = true, name = "idx_photos_remote_thumbnail")
    ]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "remote_thumbnail_path") val remoteThumbnailPath: String,
    @ColumnInfo(name = "remote_original_path") val remoteOriginalPath: String,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "month") val month: Int,
    @ColumnInfo(name = "filename_stem") val filenameStem: String,
    @ColumnInfo(name = "original_extension") val originalExtension: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "file_size", defaultValue = "0") val fileSize: Long = 0,
    @ColumnInfo(name = "etag") val etag: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: String? = null,
    @ColumnInfo(name = "thumbnail_downloaded", defaultValue = "0") val thumbnailDownloaded: Boolean = false,
    @ColumnInfo(name = "local_thumbnail_path") val localThumbnailPath: String? = null,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_flagged", defaultValue = "0") val isFlagged: Boolean = false,
    @ColumnInfo(name = "local_full_path") val localFullPath: String? = null,
    @ColumnInfo(name = "local_favorite_path") val localFavoritePath: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    fun toDomain(): Photo = Photo(
        id = id,
        remoteThumbnailPath = remoteThumbnailPath,
        remoteOriginalPath = remoteOriginalPath,
        year = year,
        month = month,
        filenameStem = filenameStem,
        originalExtension = originalExtension,
        mimeType = mimeType,
        mediaType = if (mediaType == "VIDEO") MediaType.VIDEO else MediaType.PHOTO,
        fileSize = fileSize,
        etag = etag,
        lastModified = lastModified,
        thumbnailDownloaded = thumbnailDownloaded,
        localThumbnailPath = localThumbnailPath,
        isFavorite = isFavorite,
        isFlagged = isFlagged,
        tags = tags,
        localFullPath = localFullPath,
        localFavoritePath = localFavoritePath,
        isDeleted = isDeleted,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
