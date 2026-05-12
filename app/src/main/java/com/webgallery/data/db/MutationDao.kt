// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class PendingMutation(
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "mutation_type") val mutationType: String
)

@Dao
interface MutationDao {

    @Query("SELECT * FROM pending_mutations WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at ASC")
    suspend fun getPending(): List<MutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE status IN ('PENDING', 'PROCESSING')")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT * FROM pending_mutations ORDER BY created_at DESC")
    fun getAll(): Flow<List<MutationEntity>>

    @Query("SELECT photo_id, mutation_type FROM pending_mutations WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')")
    fun getPendingPhotoMutations(): Flow<List<PendingMutation>>

    @Insert
    suspend fun insert(mutation: MutationEntity): Long

    @Query("UPDATE pending_mutations SET status = :status, error_message = :error, retry_count = retry_count + 1, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_mutations WHERE photo_id = :photoId")
    suspend fun deleteForPhoto(photoId: Long)
}
