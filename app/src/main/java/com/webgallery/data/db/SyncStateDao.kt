// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE directory_path = :directoryPath LIMIT 1")
    suspend fun getByPath(directoryPath: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(syncState: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}
