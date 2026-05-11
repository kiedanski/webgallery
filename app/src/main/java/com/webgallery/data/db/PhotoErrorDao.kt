// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoErrorDao {

    @Query("SELECT * FROM photo_errors WHERE photo_id = :photoId ORDER BY timestamp DESC")
    fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>>

    @Query("SELECT * FROM photo_errors WHERE photo_id IN (SELECT id FROM photos WHERE is_flagged = 1) ORDER BY timestamp DESC")
    fun getErrorsForFlaggedPhotos(): Flow<List<PhotoErrorEntity>>

    @Insert
    suspend fun insert(error: PhotoErrorEntity)

    @Query("DELETE FROM photo_errors WHERE photo_id = :photoId")
    suspend fun deleteForPhoto(photoId: Long)
}
