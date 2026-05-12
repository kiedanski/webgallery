# Auto-Upload from Watched Folders — Implementation Plan

## Overview

Watch folders on the phone (camera roll, WhatsApp, etc.), automatically upload new photos/videos to Tilde via `PUT /dav/photos/_inbox/`, then delete locals to free space. Users can add any number of watched folders and browse their contents.

## Server Behavior (confirmed with Tilde team)

- `PUT /dav/photos/_inbox/filename.jpg` → server reads EXIF, organizes to `{year}/{month}/`, generates thumbnail, dedup via SHA-256
- No special headers needed beyond Basic Auth
- Duplicate filenames: appends counter (`photo-1.jpg`, etc.)
- Content dedup: exact same file content = skipped entirely
- Videos: thumbnails generated via ffmpeg
- Size limit: 10 GB per file
- Chunked upload supported for large files / unreliable connections

## Desired End State

- Settings screen to manage watched folders (add/remove)
- Each watched folder shows: path, file count, total size, upload status
- Background WorkManager job periodically scans watched folders for new files
- New files are uploaded to `_inbox/` via the existing WebDavClient
- After successful upload, local file is deleted (configurable: delete immediately, delete after X days, or keep)
- Upload progress visible in a notification (similar to sync)
- Upload history persisted in DB (what was uploaded, when, from where)
- Works on WiFi only by default (configurable)

## What We're NOT Doing

- Real-time file system monitoring (FileObserver) — too battery-intensive; periodic scan is sufficient
- Chunked uploads (first version — add later for large videos)
- Selective upload (all new files in watched folders are uploaded)
- Two-way sync (this is upload-only; the existing sync handles download)

## Implementation Phases

---

## Phase 1: Database — Upload Tracking (DB v5)

### New entities:

#### WatchedFolderEntity
```kotlin
@Entity(tableName = "watched_folders")
data class WatchedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,           // e.g., "/storage/emulated/0/DCIM/Camera"
    val displayName: String,    // e.g., "Camera"
    val enabled: Boolean = true,
    val deleteAfterUpload: Boolean = true,
    val wifiOnly: Boolean = true,
    val createdAt: Long
)
```

#### UploadEntity
```kotlin
@Entity(tableName = "uploads")
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val localPath: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val status: String = "PENDING",  // PENDING, UPLOADING, UPLOADED, FAILED, DELETED
    val errorMessage: String? = null,
    val uploadedAt: Long? = null,
    val deletedAt: Long? = null,
    val createdAt: Long
)
```

### DAOs:
- `WatchedFolderDao`: CRUD for folders, get all enabled
- `UploadDao`: insert, update status, get pending, get by folder, get history

---

## Phase 2: Folder Scanner

### FolderScanner
Scans watched folders for new files not yet tracked in `uploads` table.

```kotlin
class FolderScanner(
    private val uploadDao: UploadDao,
    private val watchedFolderDao: WatchedFolderDao
) {
    suspend fun scanAll(): List<UploadEntity> {
        val folders = watchedFolderDao.getEnabled()
        val newFiles = mutableListOf<UploadEntity>()
        for (folder in folders) {
            val knownPaths = uploadDao.getKnownPathsForFolder(folder.id)
            val files = File(folder.path).listFiles { f ->
                f.isFile && isMediaFile(f) && f.absolutePath !in knownPaths
            } ?: continue
            for (file in files) {
                val entity = UploadEntity(
                    folderId = folder.id,
                    localPath = file.absolutePath,
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = guessMimeType(file),
                    createdAt = System.currentTimeMillis()
                )
                uploadDao.insert(entity)
                newFiles += entity
            }
        }
        return newFiles
    }
}
```

---

## Phase 3: Upload Service

### UploadService (Foreground Service)
Similar to SyncService — runs as foreground service with notification showing upload progress.

Flow:
1. Scan watched folders for new files
2. For each pending upload:
   - Check WiFi requirement
   - PUT to `/dav/photos/_inbox/{filename}`
   - On success: mark as UPLOADED
   - If `deleteAfterUpload`: delete local file, mark as DELETED
   - On failure: mark as FAILED with error
3. Show progress notification with cancel button

```kotlin
class UploadService : Service() {
    // Similar pattern to SyncService
    // Uses WebDavClient.putFile() for each upload
    // Notification shows "Uploading: 3/15 — photo.jpg"
}
```

---

## Phase 4: WorkManager for Periodic Scanning

### Periodic scan
Use WorkManager to schedule periodic scans (every 30 minutes) that check for new files and trigger UploadService if any are found.

```kotlin
class UploadScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WebGalleryApp).container
        val scanner = container.folderScanner
        val newFiles = scanner.scanAll()
        if (newFiles.isNotEmpty()) {
            UploadService.start(applicationContext)
        }
        return Result.success()
    }
}
```

Register as periodic work:
```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "upload_scan",
    ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<UploadScanWorker>(30, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
)
```

---

## Phase 5: UI — Watched Folders Management

### New screen: WatchedFoldersScreen (accessible from Settings)

Shows:
- List of watched folders with toggle, file count, upload stats
- "Add folder" button → Android folder picker (SAF or direct path)
- Per-folder options: enable/disable, delete after upload toggle, WiFi only
- Upload history per folder

### Pre-configured folder suggestions:
- Camera: `/storage/emulated/0/DCIM/Camera`
- WhatsApp Images: `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images`
- WhatsApp Video: `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video`
- Screenshots: `/storage/emulated/0/Pictures/Screenshots`

### Settings additions:
- "Watched folders" → navigates to WatchedFoldersScreen
- "Upload on WiFi only" (global default)
- "Delete after upload" (global default)

---

## Phase 6: Permissions

### Required permissions:
- `READ_MEDIA_IMAGES` (Android 13+) or `READ_EXTERNAL_STORAGE` (older)
- `READ_MEDIA_VIDEO` (Android 13+)
- `MANAGE_EXTERNAL_STORAGE` for WhatsApp folder access (Android 11+)

### Permission flow:
- When user adds a watched folder, request necessary permissions
- If denied, show explanation and disable the folder

---

## Testing Strategy

### Manual Testing:
1. Add Camera folder → take a photo → verify it appears in pending uploads
2. Trigger upload → verify file appears in `_inbox/` on server
3. Verify local file deleted after upload (if configured)
4. Kill app during upload → verify it resumes on next scan
5. Go offline → verify uploads queue and retry on reconnect
6. Upload duplicate file → verify server dedup works

## References

- SyncService pattern: `app/src/main/java/com/webgallery/sync/SyncService.kt`
- WebDavClient.putFile: `core/src/main/java/com/webgallery/data/webdav/WebDavClient.kt`
- Tilde inbox docs: chat.txt conversation 2026-05-11
