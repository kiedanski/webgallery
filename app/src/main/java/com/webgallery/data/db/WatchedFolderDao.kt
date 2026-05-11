// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedFolderDao {

    @Query("SELECT * FROM watched_folders ORDER BY display_name ASC")
    fun getAll(): Flow<List<WatchedFolderEntity>>

    @Query("SELECT * FROM watched_folders WHERE enabled = 1")
    suspend fun getEnabled(): List<WatchedFolderEntity>

    @Query("SELECT * FROM watched_folders WHERE id = :id")
    suspend fun getById(id: Long): WatchedFolderEntity?

    @Insert
    suspend fun insert(folder: WatchedFolderEntity): Long

    @Query("UPDATE watched_folders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE watched_folders SET delete_after_upload = :delete WHERE id = :id")
    suspend fun setDeleteAfterUpload(id: Long, delete: Boolean)

    @Query("UPDATE watched_folders SET wifi_only = :wifiOnly WHERE id = :id")
    suspend fun setWifiOnly(id: Long, wifiOnly: Boolean)

    @Query("DELETE FROM watched_folders WHERE id = :id")
    suspend fun delete(id: Long)
}
