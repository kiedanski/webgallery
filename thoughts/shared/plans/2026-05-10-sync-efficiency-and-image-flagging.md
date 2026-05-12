# Sync Efficiency & Image Flagging Implementation Plan

## Overview

Two independent features:
1. **Sync efficiency** — Make re-syncs fast for large libraries by leveraging server-side directory ETags and parallelizing client-side operations.
2. **Image flagging** — Allow users to flag gray/broken images via long press; the app automatically records download errors per photo; a dedicated screen shows flagged images with their info, full image, and error logs.

## Current State Analysis

### Sync
- `PhotoRepository.sync()` (`app/.../data/PhotoRepository.kt:58-130`) orchestrates a multi-phase sync
- Month directories are discovered sequentially (line 80-95)
- ETag-based skip (line 102) compares directory ETag from PROPFIND against stored `SyncStateEntity.etag`
- **Problem:** If the server doesn't return directory ETags, the condition always fails → every month gets re-processed every sync
- Thumbnail downloads are capped at 4 concurrent (`Semaphore(4)`)
- Individual file reconciliation already avoids re-downloading existing thumbnails

### Flagging
- No flagging mechanism exists
- `PhotoActionsSheet.kt` only has a "favorite" toggle
- `PhotoEntity` has no `isFlagged` column
- No error logging per photo — download failures are silently ignored
- Database is at version 1 (`AppDatabase.kt:14`)

## Desired End State

### Sync
- Server returns ETags on directory PROPFIND responses for `_thumbnails/{year}/` and `_thumbnails/{year}/{month}/`
- Re-syncs with no changes complete in <2 seconds (just year+month discovery PROPFINDs)
- Changed months are discovered and processed in parallel
- Thumbnail download concurrency increased to 8

### Flagging
- Long press on any image shows "Flag for inspection" action (in addition to favorite)
- App records errors per photo in a `photo_errors` table (HTTP codes, error messages, timestamps)
- Errors are recorded automatically during sync and full-image download
- A "Flagged Images" screen (accessible from Settings) shows:
  - Photo metadata (filename, size, date, remote path)
  - The full-resolution image (attempted load)
  - All recorded errors/logs for that photo
  - Option to unflag

### Verification
- Sync with unchanged library: only discovery PROPFINDs fire (verify via OkHttp logging)
- Sync with one new photo in one month: only that month gets processed
- Flagging an image adds it to the flagged list with correct metadata
- A failed thumbnail download creates an error record visible in the flagged view

## What We're NOT Doing

- Server-side log retrieval (only client-side error recording)
- Automatic flagging of broken images (user-initiated only)
- Push-based sync / WebSocket notifications
- Delta sync at the individual file level within a month (still re-list the changed month)
- Changing the thumbnail download strategy (still downloads full thumbnail files)

## Implementation Approach

Two independent tracks that share a DB migration:
- **Track A (Sync):** Parallelize discovery + processing; bump semaphore; document server ETag requirement
- **Track B (Flagging):** New DB table + column, error recording in repository, UI for flag action and flagged list

---

## Phase 1: Database Migration (version 1 → 2)

### Overview
Add `is_flagged` column to `photos` table, create `photo_errors` table, add index.

### Changes Required:

#### 1. Migration definition
**File**: `app/src/main/java/com/webgallery/data/db/AppDatabase.kt`
**Changes**: Bump version to 2, add migration, add `PhotoErrorDao`

```kotlin
@Database(
    entities = [PhotoEntity::class, SyncStateEntity::class, PhotoErrorEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun photoErrorDao(): PhotoErrorDao

    companion object {
        const val DATABASE_NAME = "webgallery.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
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
                """)
                db.execSQL("CREATE INDEX idx_photo_errors_photo_id ON photo_errors(photo_id)")
            }
        }

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
```

#### 2. PhotoEntity — add `isFlagged` column
**File**: `app/src/main/java/com/webgallery/data/db/PhotoEntity.kt`
**Changes**: Add `isFlagged` field

```kotlin
@ColumnInfo(name = "is_flagged", defaultValue = "0") val isFlagged: Boolean = false,
```

Add to the `@Entity` indices:
```kotlin
Index(value = ["is_flagged"], name = "idx_photos_is_flagged"),
```

#### 3. New entity: PhotoErrorEntity
**File**: `app/src/main/java/com/webgallery/data/db/PhotoErrorEntity.kt` (new file)

```kotlin
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
    @ColumnInfo(name = "error_type") val errorType: String,   // "THUMBNAIL_DOWNLOAD", "FULL_IMAGE_DOWNLOAD"
    @ColumnInfo(name = "error_message") val errorMessage: String,
    @ColumnInfo(name = "http_status") val httpStatus: Int? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)
```

#### 4. New DAO: PhotoErrorDao
**File**: `app/src/main/java/com/webgallery/data/db/PhotoErrorDao.kt` (new file)

```kotlin
@Dao
interface PhotoErrorDao {
    @Query("SELECT * FROM photo_errors WHERE photo_id = :photoId ORDER BY timestamp DESC")
    fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>>

    @Query("SELECT * FROM photo_errors WHERE photo_id IN (SELECT id FROM photos WHERE is_flagged = 1) ORDER BY timestamp DESC")
    fun getErrorsForFlaggedPhotos(): Flow<List<PhotoErrorEntity>>

    @Insert
    suspend fun insert(error: PhotoErrorEntity)

    @Query("DELETE FROM photo_errors WHERE photo_id = :photoId")
    suspend fun deleteForPhoto(photoId: Long)
}
```

#### 5. PhotoDao — add flagging queries
**File**: `app/src/main/java/com/webgallery/data/db/PhotoDao.kt`
**Changes**: Add queries for flagged photos

```kotlin
@Query("SELECT * FROM photos WHERE is_flagged = 1 AND is_deleted = 0 ORDER BY updated_at DESC")
fun getFlaggedPhotos(): Flow<List<PhotoEntity>>

@Query("UPDATE photos SET is_flagged = :isFlagged, updated_at = :now WHERE id = :id")
suspend fun updateFlagged(id: Long, isFlagged: Boolean, now: Long = System.currentTimeMillis())
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds without errors: `./gradlew assembleDebug`
- [ ] Migration test passes (create a Room test for MIGRATION_1_2)
- [ ] Existing unit tests still pass: `./gradlew test`

#### Manual Verification:
- [ ] App upgrades from version 1 DB without data loss
- [ ] New columns exist and are queryable

---

## Phase 2: Sync Efficiency — Client-Side Parallelization

### Overview
Parallelize month discovery and month processing; increase download concurrency.

### Changes Required:

#### 1. Parallel month discovery
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: Replace sequential year loop with parallel coroutines

```kotlin
// Phase 1: Discover years and months in parallel
val yearsResult = webDavClient.propfind("/dav/photos/_thumbnails/", depth = 1)
val yearResources = yearsResult.getOrElse {
    handleSyncError(it)
    return@withContext
}

val yearDirs = yearResources
    .filter { it.isCollection }
    .mapNotNull { extractYearFromPath(it.href) }
    .distinct()
    .sortedDescending()

// Discover months for all years in parallel
val pendingMonths = coroutineScope {
    yearDirs.map { year ->
        async {
            if (!currentCoroutineContext().isActive) return@async emptyList()
            val monthsResult = webDavClient.propfind("/dav/photos/_thumbnails/$year/", depth = 1)
            monthsResult.getOrNull()
                ?.filter { it.isCollection }
                ?.mapNotNull { res ->
                    val month = extractMonthFromPath(res.href, year) ?: return@mapNotNull null
                    MonthInfo(year, month, res.etag)
                }
                ?.sortedByDescending { it.month }
                ?: emptyList()
        }
    }.awaitAll().flatten()
}
```

#### 2. Parallel month processing
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: Process changed months concurrently with bounded parallelism

```kotlin
// Phase 2: Process changed months in parallel (max 4 concurrent)
val processSemaphore = Semaphore(4)
coroutineScope {
    val changedMonths = pendingMonths.filter { info ->
        val dirPath = "_thumbnails/${info.year}/${info.month.toString().padStart(2, '0')}/"
        val state = syncStateDao.getByPath(dirPath)
        !(state != null && state.etag != null && info.etag != null && state.etag == info.etag)
    }

    changedMonths.map { info ->
        async {
            processSemaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit
                processMonth(info)
                val dirPath = "_thumbnails/${info.year}/${info.month.toString().padStart(2, '0')}/"
                syncStateDao.upsert(
                    SyncStateEntity(
                        directoryPath = dirPath,
                        etag = info.etag,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }.awaitAll()
}
```

#### 3. Increase download concurrency
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: Bump semaphore in `downloadMissingThumbnails()`

```kotlin
val semaphore = Semaphore(8)  // was 4
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] Existing WebDAV tests pass: `./gradlew :core:test`
- [ ] App-level tests pass: `./gradlew :app:test`

#### Manual Verification:
- [ ] Sync a library, then re-sync immediately — second sync completes in <3s (no month processing)
- [ ] Add a photo to one month on server, re-sync — only that month is processed
- [ ] Cancel mid-sync doesn't crash or corrupt state

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 3: Error Recording in Repository

### Overview
Record download errors (thumbnail + full image) to `photo_errors` table automatically during sync and image loading.

### Changes Required:

#### 1. Wire PhotoErrorDao into PhotoRepository
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: Add `photoErrorDao` parameter, record errors on download failure

```kotlin
class PhotoRepository(
    private val webDavClient: WebDavClient,
    private val photoDao: PhotoDao,
    private val syncStateDao: SyncStateDao,
    private val photoErrorDao: PhotoErrorDao,  // NEW
    private val thumbnailStore: ThumbnailStore,
    private val imageCacheManager: ImageCacheManager,
    private val settingsRepository: SettingsRepository
)
```

#### 2. Record thumbnail download errors
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: In `downloadMissingThumbnails()`, on failure log the error

```kotlin
if (res.isSuccess) {
    photoDao.updateThumbnailDownloaded(entity.id, true, target.absolutePath)
} else {
    val err = res.exceptionOrNull()
    // Record the error
    photoErrorDao.insert(
        PhotoErrorEntity(
            photoId = entity.id,
            errorType = "THUMBNAIL_DOWNLOAD",
            errorMessage = err?.message ?: "Unknown error",
            httpStatus = (err as? WebDavClient.HttpException)?.code,
            remotePath = entity.remoteThumbnailPath,
            timestamp = System.currentTimeMillis()
        )
    )
    // existing disk-full check...
}
```

#### 3. Record full-image download errors
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`
**Changes**: In `ensureFullImage()`, on failure log the error

```kotlin
result.fold(
    onSuccess = { ... },
    onFailure = { err ->
        photoErrorDao.insert(
            PhotoErrorEntity(
                photoId = photo.id,
                errorType = "FULL_IMAGE_DOWNLOAD",
                errorMessage = err.message ?: "Unknown error",
                httpStatus = (err as? WebDavClient.HttpException)?.code,
                remotePath = photo.remoteOriginalPath,
                timestamp = System.currentTimeMillis()
            )
        )
        null
    }
)
```

#### 4. Add HttpException to WebDavClient (for HTTP status capture)
**File**: `core/src/main/java/com/webgallery/data/webdav/WebDavClient.kt`
**Changes**: Add exception class that carries HTTP status code

```kotlin
class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")
```

Update `downloadFile()` to throw `HttpException` instead of generic `IOException` for HTTP errors:
```kotlin
if (response.code == 401) throw UnauthorizedException()
if (!response.isSuccessful) throw HttpException(response.code, "Download failed")
```

#### 5. Wire into AppContainer
**File**: `app/src/main/java/com/webgallery/AppContainer.kt`
**Changes**: Add `photoErrorDao`, pass to `PhotoRepository`

```kotlin
val photoErrorDao = database.photoErrorDao()

val photoRepository: PhotoRepository = PhotoRepository(
    webDavClient = webDavClient,
    photoDao = photoDao,
    syncStateDao = syncStateDao,
    photoErrorDao = photoErrorDao,
    thumbnailStore = thumbnailStore,
    imageCacheManager = imageCacheManager,
    settingsRepository = settingsRepository
)
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] All tests pass: `./gradlew test`

#### Manual Verification:
- [ ] Intentionally break a thumbnail URL on server → error appears in `photo_errors` table
- [ ] Errors have correct type, message, and timestamp

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 4: Flagging UI — Long Press Action & Toggle

### Overview
Add "Flag for inspection" to the long-press bottom sheet and repository methods for toggling flags.

### Changes Required:

#### 1. PhotoRepository — add flag methods
**File**: `app/src/main/java/com/webgallery/data/PhotoRepository.kt`

```kotlin
fun getFlaggedPhotos(): Flow<List<PhotoEntity>> = photoDao.getFlaggedPhotos()

fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>> =
    photoErrorDao.getErrorsForPhoto(photoId)

suspend fun toggleFlagged(photo: PhotoEntity): Boolean = withContext(Dispatchers.IO) {
    val newState = !photo.isFlagged
    photoDao.updateFlagged(photo.id, newState)
    newState
}
```

#### 2. PhotoActionsSheet — add "Flag" action row
**File**: `app/src/main/java/com/webgallery/ui/gallery/PhotoActionsSheet.kt`
**Changes**: Add `onToggleFlag` callback and a second action row

```kotlin
@Composable
fun PhotoActionsSheet(
    photo: PhotoEntity,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleFlag: () -> Unit,    // NEW
) {
    // ... existing sheet ...
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        // ... title ...
        // ... favorite row (existing) ...
        ActionRow(
            icon = if (photo.isFlagged) Icons.Filled.Flag else Icons.Outlined.Flag,
            tint = if (photo.isFlagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            label = if (photo.isFlagged) "Remove flag" else "Flag for inspection",
            onClick = {
                onToggleFlag()
                onDismiss()
            }
        )
    }
}
```

#### 3. Update all call sites of PhotoActionsSheet
**Files**: `GalleryScreen.kt`, `FavoritesScreen.kt`
**Changes**: Pass `onToggleFlag` lambda

In `GalleryScreen.kt`:
```kotlin
PhotoActionsSheet(
    photo = photo,
    onDismiss = { sheetPhoto = null },
    onToggleFavorite = { viewModel.toggleFavorite(photo) },
    onToggleFlag = { viewModel.toggleFlagged(photo) }
)
```

#### 4. GalleryViewModel & FavoritesViewModel — add toggleFlagged
**Files**: `GalleryViewModel.kt`, `FavoritesViewModel.kt`

```kotlin
fun toggleFlagged(photo: PhotoEntity) {
    viewModelScope.launch {
        repository.toggleFlagged(photo)
    }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] All tests pass: `./gradlew test`

#### Manual Verification:
- [ ] Long press shows both "Mark as favorite" and "Flag for inspection"
- [ ] Flagging/unflagging works and persists across app restarts

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation before proceeding.

---

## Phase 5: Flagged Images Screen

### Overview
New screen accessible from Settings showing flagged images with metadata, full image, and error logs.

### Changes Required:

#### 1. FlaggedViewModel
**File**: `app/src/main/java/com/webgallery/ui/flagged/FlaggedViewModel.kt` (new file)

```kotlin
class FlaggedViewModel(
    private val repository: PhotoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val flaggedPhotos: StateFlow<List<PhotoEntity>> = repository.getFlaggedPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun getErrorsForPhoto(photoId: Long): Flow<List<PhotoErrorEntity>> =
        repository.getErrorsForPhoto(photoId)

    fun unflag(photo: PhotoEntity) {
        viewModelScope.launch { repository.toggleFlagged(photo) }
    }

    fun ensureFullImage(photo: PhotoEntity, onProgress: ((Long, Long) -> Unit)? = null) {
        viewModelScope.launch {
            val limit = settingsRepository.getCacheLimitBytes()
            repository.ensureFullImage(photo, limit, onProgress)
        }
    }
}
```

#### 2. FlaggedScreen
**File**: `app/src/main/java/com/webgallery/ui/flagged/FlaggedScreen.kt` (new file)

Shows a list of flagged photos. Each item displays:
- Thumbnail (or gray placeholder if broken)
- Filename, year/month, file size, remote path
- Error count badge
- Tapping opens a detail view with:
  - Full image load attempt
  - Complete metadata
  - Error log list (type, message, HTTP status, timestamp)
  - "Unflag" button

#### 3. Navigation — add route from Settings
**File**: `app/src/main/java/com/webgallery/ui/navigation/AppNavHost.kt`
**Changes**: Add `Routes.FLAGGED = "flagged"` and composable

```kotlin
object Routes {
    // ... existing ...
    const val FLAGGED = "flagged"
}
```

```kotlin
composable(Routes.FLAGGED) {
    FlaggedScreen(
        factory = factory,
        onClose = { navController.popBackStack() }
    )
}
```

#### 4. Settings — add "Flagged Images" link
**File**: `app/src/main/java/com/webgallery/ui/settings/SettingsScreen.kt`
**Changes**: Add a row that navigates to the Flagged screen (pass navController or callback)

#### 5. AppViewModelFactory — register FlaggedViewModel
**File**: `app/src/main/java/com/webgallery/ui/AppViewModelFactory.kt`

```kotlin
FlaggedViewModel::class.java -> FlaggedViewModel(
    container.photoRepository,
    container.settingsRepository
) as T
```

### Success Criteria:

#### Automated Verification:
- [ ] App builds: `./gradlew assembleDebug`
- [ ] All tests pass: `./gradlew test`

#### Manual Verification:
- [ ] Flagged images appear in Settings > Flagged Images
- [ ] Each flagged image shows metadata and error log
- [ ] Full image loads (or shows error if broken)
- [ ] Unflagging from the flagged screen removes it from the list
- [ ] Empty state shows "No flagged images" message

---

## Phase 6: Server-Side — Directory ETag Support (Documentation)

### Overview
Document the server requirement for the sync optimization to work.

### Server Requirement:

The Tilde server must return an `ETag` (or `getetag`) property in PROPFIND responses for **directory** resources under:
- `/dav/photos/_thumbnails/{year}/`  
- `/dav/photos/_thumbnails/{year}/{month}/`

The ETag must change whenever files are added, removed, or modified within that directory.

**Example PROPFIND response for a month directory:**
```xml
<d:response>
  <d:href>/dav/photos/_thumbnails/2026/05/</d:href>
  <d:propstat>
    <d:prop>
      <d:resourcetype><d:collection/></d:resourcetype>
      <d:getetag>"abc123def456"</d:getetag>
    </d:prop>
    <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
</d:response>
```

**Implementation suggestion:** Hash of (file count + newest mtime) or use filesystem inode change number.

### Client Fallback:

If the server does NOT return ETags (returns null), the client should fall back to the previous behavior (process the month). The parallel processing from Phase 2 still helps in this case.

No client code change needed — the existing null-check on line 102 already handles this gracefully.

---

## Testing Strategy

### Unit Tests:
- Room migration test (version 1→2)
- `PhotoErrorDao` insert/query
- `PhotoDao.getFlaggedPhotos()` and `updateFlagged()`

### Integration Tests:
- Sync with mocked WebDAV server returning directory ETags → verify months are skipped
- Sync with mocked failure → verify error is recorded in `photo_errors`

### Manual Testing Steps:
1. Upgrade app from previous version — verify migration succeeds
2. Sync a 100+ photo library — observe speed improvement
3. Re-sync unchanged library — should be near-instant
4. Long press image → flag → verify appears in Settings > Flagged
5. Cause a download failure → verify error log appears in flagged detail view
6. Unflag → verify removal from list

## Performance Considerations

- Parallel month discovery: bounded by network, typically 10-12 concurrent PROPFINDs
- Parallel month processing: capped at 4 to avoid overwhelming the server
- Thumbnail downloads: bumped to 8 concurrent (still conservative for most connections)
- Error recording: minimal overhead (one INSERT per failure)
- Flagged screen: lazy-loads full images on demand, not eagerly

## Migration Notes

- Room migration 1→2 is additive (new column with default, new table) — safe for existing users
- No data loss during migration
- Existing photos default to `isFlagged = false`
- Sync state from version 1 remains valid

## References

- Sync orchestration: `app/src/main/java/com/webgallery/data/PhotoRepository.kt:58-130`
- Current bottom sheet: `app/src/main/java/com/webgallery/ui/gallery/PhotoActionsSheet.kt`
- Database: `app/src/main/java/com/webgallery/data/db/AppDatabase.kt`
- Photo entity: `app/src/main/java/com/webgallery/data/db/PhotoEntity.kt`
- WebDAV client: `core/src/main/java/com/webgallery/data/webdav/WebDavClient.kt`
