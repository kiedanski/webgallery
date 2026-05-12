# Mutation Queue & Metadata Editing Implementation Plan

## Overview

Add the ability to edit photo dates (EXIF), manage tags (PROPPATCH), and delete photos — all through an offline-capable mutation queue. Mutations are queued locally and only pushed to the server when the last sync is fresh (< 10 minutes). If stale, sync first, then push.

## Current State

- WebDavClient only supports PROPFIND and GET — no PUT, DELETE, or PROPPATCH
- No concept of local mutations or a write queue
- No EXIF reading/writing capability
- PhotoEntity has no tags field
- The app is read-only

## Desired End State

- User can change a photo's date (modifies EXIF DateTimeOriginal, PUTs file back)
- User can add/remove tags on photos (PROPPATCH custom properties)
- User can delete photos (WebDAV DELETE)
- All mutations go through a persistent queue in Room
- Queue only flushes when last sync was < 10 minutes ago
- If sync is stale, auto-sync first then flush
- Queue survives app restarts
- UI shows pending mutations count and queue status

### Server Behavior (confirmed):
- **PUT with modified EXIF**: Tilde auto-detects date change, moves file to correct `{year}/{month}/`, updates DB
- **PROPPATCH**: Stores custom properties in `file_properties` table, returned in PROPFIND
- **DELETE**: Moves to `.trash/`, cleans up thumbnail + photos row (fix being implemented server-side)

## What We're NOT Doing

- Exposing Tilde's `photo_tags` prefix system (trip:X, person:X) — using PROPPATCH custom properties instead
- Batch editing (one photo at a time)
- Conflict resolution beyond freshness check
- Undo/redo

## Implementation Approach

Six phases: WebDAV write operations → EXIF library → mutation queue table → queue processor → UI for editing → queue status UI

---

## Phase 1: WebDAV Write Operations

### Overview
Add PUT, DELETE, and PROPPATCH methods to WebDavClient.

### Changes Required:

#### 1. WebDavClient — add PUT
**File**: `core/src/main/java/com/webgallery/data/webdav/WebDavClient.kt`

```kotlin
suspend fun putFile(
    remotePath: String,
    sourceFile: File,
    contentType: String = "application/octet-stream"
): Result<Unit> = withContext(Dispatchers.IO) {
    val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
    runCatching {
        val body = sourceFile.asRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url(buildUrl(cfg.baseUrl, remotePath))
            .put(body)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful) throw HttpException(response.code, "PUT failed")
        }
    }
}
```

#### 2. WebDavClient — add DELETE
```kotlin
suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
    val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
    runCatching {
        val request = Request.Builder()
            .url(buildUrl(cfg.baseUrl, remotePath))
            .delete()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful) throw HttpException(response.code, "DELETE failed")
        }
    }
}
```

#### 3. WebDavClient — add PROPPATCH
```kotlin
suspend fun proppatch(
    remotePath: String,
    setProperties: Map<String, String> = emptyMap(),
    removeProperties: List<String> = emptyList(),
    namespace: String = "http://webgallery.app/ns/"
): Result<Unit> = withContext(Dispatchers.IO) {
    val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
    runCatching {
        val body = buildProppatchBody(setProperties, removeProperties, namespace)
        val request = buildRequest(cfg.baseUrl, remotePath, "PROPPATCH", 0, body)
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (response.code !in listOf(200, 207)) throw HttpException(response.code, "PROPPATCH failed")
        }
    }
}
```

### Success Criteria:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] Unit tests for PUT/DELETE/PROPPATCH with MockWebServer

---

## Phase 2: EXIF Library Integration

### Overview
Add ability to read and modify EXIF DateTimeOriginal in JPEG/HEIC files.

### Changes Required:

#### 1. Add dependency
**File**: `app/build.gradle.kts`

```kotlin
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

#### 2. ExifEditor utility
**File**: `app/src/main/java/com/webgallery/data/ExifEditor.kt`

```kotlin
object ExifEditor {
    /**
     * Reads DateTimeOriginal from the file.
     * Returns ISO date string or null.
     */
    fun readDate(file: File): String?

    /**
     * Writes DateTimeOriginal to the file (modifies in place).
     * dateStr format: "yyyy:MM:dd HH:mm:ss" (EXIF standard)
     */
    fun writeDate(file: File, dateStr: String): Boolean
}
```

Uses `androidx.exifinterface.media.ExifInterface` for JPEG. For other formats, returns null/false gracefully.

### Success Criteria:
- [ ] App builds
- [ ] Unit test: write date to a test JPEG, read it back, verify match

---

## Phase 3: Mutation Queue Table (DB Migration 3→4)

### Overview
Create a `pending_mutations` table to persist queued operations.

### Changes Required:

#### 1. MutationEntity
**File**: `app/src/main/java/com/webgallery/data/db/MutationEntity.kt`

```kotlin
@Entity(tableName = "pending_mutations")
data class MutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "mutation_type") val mutationType: String,  // "CHANGE_DATE", "SET_TAGS", "DELETE"
    @ColumnInfo(name = "payload") val payload: String,  // JSON: {"date":"2024-01-15T10:30:00"} or {"tags":"vacation,family"} or {}
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "status") val status: String = "PENDING",  // PENDING, PROCESSING, FAILED
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
```

#### 2. MutationDao
**File**: `app/src/main/java/com/webgallery/data/db/MutationDao.kt`

```kotlin
@Dao
interface MutationDao {
    @Query("SELECT * FROM pending_mutations WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPending(): List<MutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE status IN ('PENDING', 'PROCESSING')")
    fun getPendingCount(): Flow<Int>

    @Insert
    suspend fun insert(mutation: MutationEntity): Long

    @Query("UPDATE pending_mutations SET status = :status, error_message = :error, retry_count = retry_count + 1, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_mutations WHERE photo_id = :photoId")
    suspend fun deleteForPhoto(photoId: Long)
}
```

#### 3. DB Migration
**File**: `app/src/main/java/com/webgallery/data/db/AppDatabase.kt`

Add `MutationEntity` to entities, bump to version 4, add migration:
```sql
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
```

#### 4. Add tags column to PhotoEntity
**File**: `app/src/main/java/com/webgallery/data/db/PhotoEntity.kt`

Add: `@ColumnInfo(name = "tags") val tags: String? = null`

Migration adds: `ALTER TABLE photos ADD COLUMN tags TEXT`

### Success Criteria:
- [ ] App builds
- [ ] Migration test passes

---

## Phase 4: Queue Processor (MutationProcessor)

### Overview
Service that processes pending mutations. Checks sync freshness, syncs if needed, then executes mutations in order.

### Changes Required:

#### 1. MutationProcessor
**File**: `app/src/main/java/com/webgallery/data/MutationProcessor.kt`

```kotlin
class MutationProcessor(
    private val webDavClient: WebDavClient,
    private val photoDao: PhotoDao,
    private val mutationDao: MutationDao,
    private val settingsRepository: SettingsRepository,
    private val photoRepository: PhotoRepository,
    private val imageCacheManager: ImageCacheManager
) {
    companion object {
        const val FRESHNESS_THRESHOLD_MS = 10 * 60 * 1000L  // 10 minutes
    }

    suspend fun processQueue() {
        val pending = mutationDao.getPending()
        if (pending.isEmpty()) return

        // Check sync freshness
        val lastSync = settingsRepository.getLastSyncTimestamp()
        if (System.currentTimeMillis() - lastSync > FRESHNESS_THRESHOLD_MS) {
            // Sync first to prevent conflicts
            photoRepository.sync()
        }

        for (mutation in pending) {
            mutationDao.updateStatus(mutation.id, "PROCESSING")
            try {
                when (mutation.mutationType) {
                    "CHANGE_DATE" -> processDateChange(mutation)
                    "SET_TAGS" -> processTagChange(mutation)
                    "DELETE" -> processDelete(mutation)
                }
                mutationDao.delete(mutation.id)
            } catch (e: Exception) {
                mutationDao.updateStatus(mutation.id, "FAILED", e.message)
            }
        }
    }

    private suspend fun processDateChange(mutation: MutationEntity) {
        // 1. Download the full image to a temp file
        // 2. Modify EXIF DateTimeOriginal
        // 3. PUT back to same remote path
        // 4. Server handles reorganization
        // 5. Trigger a sync to pick up the new location
    }

    private suspend fun processTagChange(mutation: MutationEntity) {
        // PROPPATCH to set/update tags property
        val tags = // parse from payload JSON
        webDavClient.proppatch(
            "/dav/photos/${mutation.remotePath}",
            setProperties = mapOf("tags" to tags)
        ).getOrThrow()
        // Update local PhotoEntity tags
    }

    private suspend fun processDelete(mutation: MutationEntity) {
        // DELETE the remote file
        webDavClient.delete("/dav/photos/${mutation.remotePath}").getOrThrow()
        // Mark local entity as deleted
        photoDao.markAsDeleted(mutation.photoId)
    }
}
```

#### 2. Wire into SyncService
After sync completes, also process the mutation queue:

```kotlin
repo.sync { current, total -> updateNotification(current, total) }
// After sync, flush pending mutations
container.mutationProcessor.processQueue()
```

#### 3. PhotoRepository — add mutation enqueue methods

```kotlin
suspend fun enqueueDateChange(photo: PhotoEntity, newDate: String)
suspend fun enqueueTagChange(photo: PhotoEntity, tags: String)
suspend fun enqueueDelete(photo: PhotoEntity)
```

Each creates a `MutationEntity` and inserts it. For delete, also locally marks the photo as deleted immediately (optimistic UI).

### Success Criteria:
- [ ] App builds
- [ ] Enqueue a delete → verify mutation row created
- [ ] Process queue with mock server → verify DELETE sent

---

## Phase 5: UI for Editing

### Overview
Add edit actions to the photo viewer and bottom sheet.

### Changes Required:

#### 1. PhotoActionsSheet — add "Change date", "Edit tags", "Delete"
Three new ActionRows:
- "Change date" → opens a date picker dialog
- "Edit tags" → opens a text input dialog
- "Delete" → confirmation dialog, then enqueues delete

#### 2. Date picker dialog
Simple Material3 DatePickerDialog. On confirm, calls `viewModel.enqueueDateChange(photo, newDate)`.

#### 3. Tag editor dialog
Text field with current tags (comma-separated). On confirm, calls `viewModel.enqueueTagChange(photo, tags)`.

#### 4. Delete confirmation dialog
"Delete this photo? It will be moved to trash on the server."
On confirm, calls `viewModel.enqueueDelete(photo)`.

#### 5. Show tags in photo info
In `PhotoViewerScreen` bottom bar, show tags if present.

#### 6. ViewModels — add mutation methods
`GalleryViewModel`, `PhotoViewerViewModel`, `FavoritesViewModel` all get:
```kotlin
fun enqueueDateChange(photo: PhotoEntity, newDate: String)
fun enqueueTagChange(photo: PhotoEntity, tags: String)  
fun enqueueDelete(photo: PhotoEntity)
```

### Success Criteria:
- [ ] App builds
- [ ] Long press → "Delete" → confirmation → photo disappears from grid
- [ ] Long press → "Change date" → picker → mutation queued
- [ ] Long press → "Edit tags" → input → mutation queued

---

## Phase 6: Queue Status UI

### Overview
Show pending mutation count in the UI and allow viewing/retrying failed mutations.

### Changes Required:

#### 1. GalleryScreen top bar — pending count badge
If `pendingCount > 0`, show a small badge/chip like "3 pending" next to the sync indicator.

#### 2. Settings — "Pending changes" section
Show list of pending/failed mutations with:
- Photo name
- Mutation type
- Status (pending/failed)
- Error message if failed
- Retry / Discard actions for failed items

### Success Criteria:
- [ ] Pending mutations show count in gallery
- [ ] Failed mutations visible in settings with retry option

---

## Testing Strategy

### Unit Tests:
- WebDavClient PUT/DELETE/PROPPATCH with MockWebServer
- ExifEditor read/write date roundtrip
- MutationProcessor queue processing logic
- Freshness check threshold

### Manual Testing:
1. Delete a photo → verify it disappears locally and is in server .trash/
2. Change date → verify server moved file to new year/month
3. Add tags → verify PROPFIND returns them
4. Go offline → queue mutations → come online → verify queue flushes
5. Queue with stale sync → verify sync runs first

## Performance Considerations

- EXIF modification requires downloading the full image — for large files this may take time
- Date changes trigger a server-side reorganization + a follow-up sync to update local state
- Queue processing is sequential (one mutation at a time) to avoid conflicts
- Delete is optimistic (local hide immediately, server push async)

## References

- WebDavClient: `core/src/main/java/com/webgallery/data/webdav/WebDavClient.kt`
- PhotoEntity: `app/src/main/java/com/webgallery/data/db/PhotoEntity.kt`
- PhotoActionsSheet: `app/src/main/java/com/webgallery/ui/gallery/PhotoActionsSheet.kt`
- SyncService: `app/src/main/java/com/webgallery/sync/SyncService.kt`
- AppDatabase: `app/src/main/java/com/webgallery/data/db/AppDatabase.kt`
