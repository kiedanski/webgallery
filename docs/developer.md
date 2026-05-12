---
layout: default
title: Developer Docs
---

# Developer Documentation

[Home](.) | [User Guide](guide) | **Developer Docs**

---

## Architecture

WebGallery is a two-module Gradle project:

```
webgallery/
  core/     Pure Kotlin (no Android dependencies)
            WebDAV client, XML parser, domain models, utilities
            Testable on JVM without an emulator

  app/      Android application
            Jetpack Compose UI, Room database, services
            Depends on core/
```

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Networking | OkHttp 4.12 |
| Image loading | Coil 3 |
| Video | Media3 / ExoPlayer |
| Database | Room 2.6 |
| Settings | DataStore Preferences |
| Credentials | EncryptedSharedPreferences (AES-256-GCM) |
| Background work | Foreground Services + WorkManager |
| Build | Gradle with Kotlin DSL + Version Catalog |
| Min SDK | API 26 (Android 8.0) |

### MVVM Pattern

The app follows MVVM with a manual DI container:

```
AppContainer (singleton, created in WebGalleryApp.onCreate)
  ├── WebDavClient
  ├── PhotoRepository
  ├── MutationProcessor
  ├── FolderScanner
  ├── DAOs (PhotoDao, SyncStateDao, MutationDao, etc.)
  ├── ThumbnailStore
  ├── ImageCacheManager
  └── SettingsRepository

AppViewModelFactory (creates all ViewModels from AppContainer)

Screens (Compose) → ViewModels → Repository → DAOs / WebDavClient
```

---

## Database Schema

Room database with 6 tables, currently at version 5.

### `photos`

Core table for photo metadata synced from the server.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | Auto-generated |
| `remote_thumbnail_path` | TEXT | Unique, e.g., `_thumbnails/2024/03/photo.webp` |
| `remote_original_path` | TEXT | e.g., `2024/03/photo.jpg` |
| `year`, `month` | INTEGER | Indexed together |
| `filename_stem` | TEXT | Without extension |
| `original_extension` | TEXT | |
| `mime_type`, `media_type` | TEXT | `PHOTO` or `VIDEO` |
| `file_size` | INTEGER | Bytes |
| `etag` | TEXT | Server ETag for change detection |
| `last_modified` | TEXT | HTTP date string |
| `thumbnail_downloaded` | INTEGER | 0/1 |
| `local_thumbnail_path` | TEXT | Absolute path on device |
| `is_favorite` | INTEGER | Indexed |
| `is_flagged` | INTEGER | Indexed |
| `tags` | TEXT | Comma-separated |
| `local_full_path` | TEXT | Cached full-resolution path |
| `local_favorite_path` | TEXT | Permanent favorite path |
| `is_deleted` | INTEGER | Soft delete, indexed |

### `sync_state`

Tracks per-directory sync state for incremental sync.

| Column | Type | Notes |
|---|---|---|
| `directory_path` | TEXT | Unique, e.g., `_thumbnails/2024/03/` |
| `etag` | TEXT | Server directory ETag |
| `content_hash` | TEXT | SHA-256 of sorted filenames |
| `last_synced_at` | INTEGER | Timestamp |

### `photo_errors`

Per-photo error log for download failures.

| Column | Type | Notes |
|---|---|---|
| `photo_id` | INTEGER FK | References photos(id), CASCADE delete |
| `error_type` | TEXT | `THUMBNAIL_DOWNLOAD` or `FULL_IMAGE_DOWNLOAD` |
| `error_message` | TEXT | |
| `http_status` | INTEGER | HTTP response code |
| `remote_path` | TEXT | |
| `timestamp` | INTEGER | |

### `pending_mutations`

Offline queue for photo edits and deletes.

| Column | Type | Notes |
|---|---|---|
| `photo_id` | INTEGER | |
| `mutation_type` | TEXT | `CHANGE_DATE`, `SET_TAGS`, `DELETE` |
| `payload` | TEXT | JSON, e.g., `{"date":"2024:01:15 12:00:00"}` |
| `remote_path` | TEXT | |
| `status` | TEXT | `PENDING`, `PROCESSING`, `FAILED` |
| `error_message` | TEXT | |
| `retry_count` | INTEGER | |

### `watched_folders`

User-configured folders for auto-upload.

| Column | Type | Notes |
|---|---|---|
| `path` | TEXT | Unique, absolute path |
| `display_name` | TEXT | User-visible name |
| `enabled` | INTEGER | 0/1 |
| `delete_after_upload` | INTEGER | 0/1 |
| `wifi_only` | INTEGER | 0/1 |

### `uploads`

Tracks upload status per file from watched folders.

| Column | Type | Notes |
|---|---|---|
| `folder_id` | INTEGER | References watched_folders |
| `local_path` | TEXT | Unique |
| `file_name` | TEXT | |
| `file_size` | INTEGER | |
| `mime_type` | TEXT | |
| `status` | TEXT | `PENDING`, `UPLOADING`, `UPLOADED`, `FAILED`, `DELETED` |

---

## WebDAV Operations

All server communication goes through `WebDavClient` in the `core` module.

| Method | Operation | Used for |
|---|---|---|
| `PROPFIND` | List directory contents | Sync discovery |
| `GET` | Download file | Thumbnails, full images |
| `PUT` | Upload file | Auto-upload to `_inbox/`, EXIF-modified files |
| `DELETE` | Delete file | Photo deletion |
| `PROPPATCH` | Set/remove properties | Tags (custom namespace `http://webgallery.app/ns/`) |

### Sync Protocol

```
1. PROPFIND /_thumbnails/           → year directories + ETags
2. For each year (parallel):
   - ETag match + not current year? → SKIP
   - PROPFIND /_thumbnails/{year}/  → month directories
   - Content hash match?            → SKIP
   - For each changed month:
     - PROPFIND /_thumbnails/{year}/{month}/ → thumbnail files
     - Content hash match?                   → SKIP
     - PROPFIND /{year}/{month}/             → original files
     - Diff against local DB, batch upsert
3. Download missing thumbnails (8 concurrent, batched DB writes)
4. Process mutation queue (if sync fresh < 10 min)
```

### Upload Protocol

```
1. PUT /dav/photos/_inbox/{filename}  → 201 (file fsync'd)
2. Server processes asynchronously:
   - Read EXIF date
   - Move to {year}/{month}/
   - Generate thumbnail
   - SHA-256 dedup
3. Delete local file (if configured)
```

---

## Building

### Prerequisites

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0

### Commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Run tests
./gradlew :core:test

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release build

Release builds are handled by CI. Push a `v*` tag to trigger:
1. Tests run
2. Debug APK built
3. Signed release APK built (using secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)
4. GitHub Release created with APK attached

---

## Contributing

1. Fork the repo
2. Create a feature branch
3. Make your changes
4. Run `./gradlew :core:test` to verify tests pass
5. Open a pull request

Please open an issue first to discuss significant changes.
