---
layout: default
title: Home
---

# WebGallery

A private, self-hosted photo gallery for Android. Browse, sync, and manage your photos and videos from your [Tilde](https://github.com/kiedanski/tilde) personal cloud server over WebDAV.

[Download Latest Release](https://github.com/kiedanski/webgallery/releases/latest){: .btn }
[View on GitHub](https://github.com/kiedanski/webgallery){: .btn }

---

## Getting Started

### 1. Install

Download the latest APK from [GitHub Releases](https://github.com/kiedanski/webgallery/releases/latest) and install it on your Android device (Android 8.0+).

### 2. Connect to your server

Open the app and enter:
- **Server URL**: Your Tilde server address (e.g., `https://photos.example.com`)
- **Username**: Your Tilde username
- **Password**: Your app password

The app will test the connection and start syncing your library.

### 3. Browse your photos

Once synced, your photos appear in a grid organized by year and month. Use the **year timeline** on the right edge (swipe left to reveal) to jump to any year.

---

## Features

### Sync

WebGallery uses an efficient multi-level sync system designed for libraries with 100,000+ photos:

1. **Year-level ETag check** — if a year directory hasn't changed, all its months are skipped entirely
2. **Month-level content hash** — even without server ETags, the app detects whether files changed by hashing the directory contents
3. **Current year always checked** — new photos are detected immediately
4. **Parallel processing** — month discovery and thumbnail downloads run concurrently
5. **Background service** — sync continues when the app is in the background, with a progress notification

Pull down to refresh, or let the app sync automatically on launch.

### Photo Viewer

Tap any photo to view it full-screen:
- **Pinch to zoom** and double-tap to zoom
- **Swipe left/right** to navigate photos in the same month
- Bottom bar shows dimensions, file size, and date

### Favorites

Tap the heart icon on any photo to save it as a favorite. Favorites are downloaded for permanent offline access and appear in the **Favorites** tab.

### Flagging

Long press a photo and select **Flag for inspection** to mark photos that appear broken or gray. View all flagged photos in **Settings > Diagnostics > Flagged images**, where you can see:
- The photo's metadata and remote path
- The full-resolution image (or broken placeholder)
- Download error logs with timestamps and HTTP status codes

### Editing

Long press any photo to access:

| Action | What happens |
|--------|-------------|
| **Change date** | Picks a new date, modifies EXIF DateTimeOriginal, uploads to server. Server auto-reorganizes to the correct year/month. |
| **Edit tags** | Sets comma-separated tags via WebDAV PROPPATCH with a custom namespace. |
| **Delete** | Moves to server trash. Photo hides immediately from the gallery. |

All edits go through an **offline mutation queue**:
- Edits are saved locally and pushed to the server on the next sync
- The queue only flushes when the last sync was less than 10 minutes ago (to prevent conflicts)
- If the sync is stale, the app syncs first, then pushes edits
- Pending edits show as icon overlays on thumbnails (red trash for delete, orange calendar for date change, green label for tags)
- View and manage the queue in **Settings > Diagnostics > Pending changes**

### Auto-Upload

Automatically upload photos from your phone to your Tilde server:

1. Go to **Settings > Upload > Watched folders**
2. Tap **+** to add a folder (browse or use quick-add suggestions for Camera, WhatsApp, etc.)
3. New photos are scanned periodically (every 30 minutes) and uploaded to the server's `_inbox/`
4. The server reads EXIF data, organizes photos into the correct year/month, generates thumbnails, and deduplicates

Per-folder settings:
- **WiFi only** — only upload on WiFi (default: on)
- **Delete after upload** — remove local file after successful upload (default: on)

Browse any watched folder to see its contents with upload status overlays (checkmark = uploaded, hourglass = pending, error = failed).

---

## Architecture

WebGallery is a two-module Gradle project:

```
webgallery/
  core/     Pure Kotlin module (no Android dependencies)
            WebDAV client, XML parser, domain models, utilities
            Can be tested on JVM without an emulator

  app/      Android application module
            Jetpack Compose UI, Room database, sync services
            Depends on core/
```

### Key components

| Component | Purpose |
|-----------|---------|
| `WebDavClient` | HTTP client for PROPFIND, GET, PUT, DELETE, PROPPATCH |
| `PhotoRepository` | Sync orchestration, caching, mutation enqueuing |
| `SyncService` | Foreground service for background thumbnail sync |
| `UploadService` | Foreground service for uploading to `_inbox/` |
| `MutationProcessor` | Processes queued edits/deletes against the server |
| `FolderScanner` | Scans watched folders for new media files |

### Database

Room database with 6 tables:

| Table | Purpose |
|-------|---------|
| `photos` | Photo metadata, sync state, favorites, flags, tags |
| `sync_state` | Per-directory ETag and content hash for incremental sync |
| `photo_errors` | Per-photo error log (download failures) |
| `pending_mutations` | Offline mutation queue (edits, deletes) |
| `watched_folders` | User-configured upload source folders |
| `uploads` | Upload tracking (status per file) |

---

## Server Requirements

WebGallery requires a [Tilde](https://github.com/kiedanski/tilde) server with:

- WebDAV endpoint at `/dav/photos/`
- Server-generated thumbnails under `_thumbnails/{year}/{month}/`
- Directory ETags in PROPFIND responses (for efficient sync)
- `_inbox/` directory for auto-upload processing

### WebDAV operations used

| Operation | Method | Purpose |
|-----------|--------|---------|
| List directories | `PROPFIND` | Discover years, months, files |
| Download files | `GET` | Thumbnails and full-resolution images |
| Upload files | `PUT` | Auto-upload to `_inbox/`, EXIF-modified files |
| Delete files | `DELETE` | Move to server trash |
| Set properties | `PROPPATCH` | Tags (custom namespace) |

---

## Building from Source

### Prerequisites

- JDK 17
- Android SDK with platform 35 and build tools 35.0.0

### Debug build

```bash
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Run tests

```bash
./gradlew :core:test
```

---

## License

WebGallery is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
