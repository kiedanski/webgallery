// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_errors",
    foreignKeys = [ForeignKey(
        entity = PhotoEntity::class,
        parentColumns = ["id"],
        childColumns = ["photo_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["photo_id"], name = "idx_photo_errors_photo_id")]
)
data class PhotoErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "error_type") val errorType: String,
    @ColumnInfo(name = "error_message") val errorMessage: String,
    @ColumnInfo(name = "http_status") val httpStatus: Int? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)
