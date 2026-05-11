// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoEntity::class, SyncStateEntity::class, PhotoErrorEntity::class, MutationEntity::class, WatchedFolderEntity::class, UploadEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun photoErrorDao(): PhotoErrorDao
    abstract fun mutationDao(): MutationDao
    abstract fun watchedFolderDao(): WatchedFolderDao
    abstract fun uploadDao(): UploadDao

    companion object {
        const val DATABASE_NAME = "webgallery.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN is_flagged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX idx_photos_is_flagged ON photos(is_flagged)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS photo_errors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        photo_id INTEGER NOT NULL,
                        error_type TEXT NOT NULL,
                        error_message TEXT NOT NULL,
                        http_status INTEGER,
                        remote_path TEXT,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(photo_id) REFERENCES photos(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_photo_errors_photo_id ON photo_errors(photo_id)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN content_hash TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN tags TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_mutations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        photo_id INTEGER NOT NULL,
                        mutation_type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        remote_path TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        error_message TEXT,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS watched_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        path TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        delete_after_upload INTEGER NOT NULL DEFAULT 1,
                        wifi_only INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX idx_watched_folders_path ON watched_folders(path)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS uploads (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        folder_id INTEGER NOT NULL,
                        local_path TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        error_message TEXT,
                        uploaded_at INTEGER,
                        deleted_at INTEGER,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_uploads_folder_id ON uploads(folder_id)")
                db.execSQL("CREATE UNIQUE INDEX idx_uploads_local_path ON uploads(local_path)")
                db.execSQL("CREATE INDEX idx_uploads_status ON uploads(status)")
            }
        }

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
