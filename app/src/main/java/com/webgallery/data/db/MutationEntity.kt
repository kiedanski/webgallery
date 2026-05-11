// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_mutations")
data class MutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "mutation_type") val mutationType: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        const val TYPE_CHANGE_DATE = "CHANGE_DATE"
        const val TYPE_SET_TAGS = "SET_TAGS"
        const val TYPE_DELETE = "DELETE"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_FAILED = "FAILED"
    }
}
