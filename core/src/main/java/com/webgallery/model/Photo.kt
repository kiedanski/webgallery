// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.model

data class Photo(
    val id: Long,
    val remoteThumbnailPath: String,
    val remoteOriginalPath: String,
    val year: Int,
    val month: Int,
    val filenameStem: String,
    val originalExtension: String,
    val mimeType: String,
    val mediaType: MediaType,
    val fileSize: Long,
    val etag: String?,
    val lastModified: String?,
    val thumbnailDownloaded: Boolean,
    val localThumbnailPath: String?,
    val isFavorite: Boolean,
    val localFullPath: String?,
    val localFavoritePath: String?,
    val isDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    val displayName: String get() = "$filenameStem.$originalExtension"
}

data class YearMonth(val year: Int, val month: Int) {
    val key: String get() = "$year-${month.toString().padStart(2, '0')}"
}

data class PhotoCounts(
    val total: Int = 0,
    val photoCount: Int = 0,
    val videoCount: Int = 0
)
