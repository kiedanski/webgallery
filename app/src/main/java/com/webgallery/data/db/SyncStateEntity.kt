package com.webgallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_state",
    indices = [
        Index(value = ["directory_path"], unique = true, name = "idx_sync_state_path")
    ]
)
data class SyncStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "directory_path") val directoryPath: String,
    @ColumnInfo(name = "etag") val etag: String? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long
)
