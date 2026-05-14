// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {

    @Query("SELECT * FROM uploads WHERE status IN ('PENDING', 'UPLOADING') ORDER BY created_at ASC")
    suspend fun getPending(): List<UploadEntity>

    @Query("SELECT COUNT(*) FROM uploads WHERE status IN ('PENDING', 'UPLOADING')")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT * FROM uploads WHERE folder_id = :folderId ORDER BY created_at DESC")
    fun getByFolder(folderId: Long): Flow<List<UploadEntity>>

    @Query("SELECT local_path FROM uploads WHERE folder_id = :folderId")
    suspend fun getKnownPathsForFolder(folderId: Long): List<String>

    @Query("SELECT * FROM uploads ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<UploadEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(upload: UploadEntity): Long

    @Query("UPDATE uploads SET status = :status, error_message = :error, uploaded_at = :uploadedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, uploadedAt: Long? = null)

    @Query("UPDATE uploads SET status = 'DELETED', deleted_at = :now WHERE id = :id")
    suspend fun markDeleted(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM uploads WHERE status IN ('UPLOADED', 'UPLOADING')")
    suspend fun getUploadedNotDeleted(): List<UploadEntity>

    @Query("DELETE FROM uploads WHERE folder_id = :folderId")
    suspend fun deleteByFolder(folderId: Long)
}
