// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watched_folders",
    indices = [Index(value = ["path"], unique = true, name = "idx_watched_folders_path")]
)
data class WatchedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "delete_after_upload", defaultValue = "1") val deleteAfterUpload: Boolean = true,
    @ColumnInfo(name = "wifi_only", defaultValue = "1") val wifiOnly: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
