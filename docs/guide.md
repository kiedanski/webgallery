---
layout: default
title: User Guide
---

# User Guide

[Home](.) | **User Guide** | [Developer Docs](developer)

---

## Setup

### Connecting to your server

1. Open WebGallery and you'll see the setup screen
2. Enter your **Server URL** — the base URL of your Tilde instance (e.g., `https://photos.example.com`)
3. Enter your **Username** and **Password**
4. Tap **Connect** — the app tests the connection and starts syncing

Your credentials are encrypted with AES-256-GCM and stored securely on the device.

### Initial sync

The first sync downloads thumbnail metadata for your entire library. For a library with ~35,000 photos, this typically takes 2-3 minutes. Thumbnail images then download in the background — you can use the app while this happens.

A notification shows download progress. You can switch to other apps; the sync continues via a foreground service.

---

## Browsing

### Gallery view

Photos are organized in a grid by year and month, newest first. Each month section shows the month name and photo/video counts.

The grid adapts to your screen:
- Portrait: 3 columns
- Landscape: 4 columns
- Tablets (600dp+): 5 columns

### Year timeline

Swipe left from the right edge to reveal the **year scrubber** — a vertical list of all years in your library. Tap any year to jump directly to it. Each year shows how many thumbnails are synced out of the total (e.g., "2,450/2,500").

Swipe the scrubber right to dismiss it.

### Photo viewer

Tap any photo to open the full-screen viewer:
- **Pinch to zoom** or double-tap for 2x zoom
- **Swipe left/right** to navigate between photos in the same month
- **Tap** to show/hide the top and bottom bars
- The bottom bar shows: image dimensions, file size, and date
- The top bar shows the filename and a heart icon for favorites

### Videos

Tap a video (marked with a play icon) to open the video player. Videos stream directly from your server — no need to download the full file first. Standard controls (play/pause, seek, fullscreen) are provided by Media3.

---

## Organizing

### Favorites

Tap the **heart icon** in the viewer or long-press a photo and select **Mark as favorite**. Favorites are:
- Downloaded in full resolution for permanent offline access
- Accessible in the **Favorites** tab (bottom navigation)
- Not affected by cache clearing

### Flagging

Long-press a photo and select **Flag for inspection** to mark photos that look wrong (gray thumbnails, missing images, etc.). View all flagged photos in **Settings > Diagnostics > Flagged images**, where each entry shows:
- Photo metadata (path, size, date)
- The full-resolution image (or a broken placeholder)
- Any download error logs with timestamps and HTTP status codes

This helps you diagnose sync issues with specific photos.

---

## Editing

All edits go through an **offline mutation queue** — they work even without connectivity and are pushed to the server on the next sync.

### Changing dates

Long-press a photo > **Change date** > pick a new date. This:
1. Queues a date change mutation
2. On sync: downloads the full image, modifies EXIF `DateTimeOriginal`, uploads back to the server
3. The server reads the new EXIF date and auto-reorganizes the photo to the correct `{year}/{month}/` directory

### Editing tags

Long-press a photo > **Edit tags** > enter comma-separated tags (e.g., "vacation, family, beach"). Tags are stored on the server via WebDAV PROPPATCH and displayed in the photo actions sheet.

### Deleting

Long-press a photo > **Delete** > confirm. The photo:
- Hides immediately from the gallery (optimistic delete)
- Is moved to the server's trash on the next sync push
- Can be recovered from the server's trash if needed

### Queue management

Pending edits show as **icon overlays** on thumbnails:
- Red trash icon = pending delete
- Orange calendar icon = pending date change
- Green label icon = pending tag edit

The gallery top bar shows "X pending" when mutations are queued.

View and manage the queue in **Settings > Diagnostics > Pending changes**:
- See all pending, processing, and failed mutations
- **Discard** — remove a mutation from the queue
- **Retry** — re-attempt a failed mutation

### Sync freshness

The queue only flushes when the last sync was less than 10 minutes ago. If the sync is stale, the app automatically syncs first to prevent conflicts, then pushes edits.

---

## Auto-Upload

### Setting up watched folders

Go to **Settings > Upload > Watched folders** and tap **+** to add a folder:
- **Browse for folder** — opens Android's system folder picker
- **Quick add** — one-tap buttons for common folders (Camera, Screenshots, WhatsApp Images, WhatsApp Video) if they exist on your device

### How it works

1. A background scanner checks watched folders every 30 minutes for new media files
2. New files are uploaded to the server's `_inbox/` directory via WebDAV PUT
3. The server automatically reads EXIF data, organizes photos into `{year}/{month}/`, and generates thumbnails
4. If "Delete after upload" is enabled, the local file is deleted after successful upload

### Per-folder settings

Each watched folder has its own settings:
- **Enabled/Disabled** — toggle the switch to pause uploads from this folder
- **Delete after upload** — remove local files after they're safely on the server

### Browsing folder contents

Tap any watched folder to browse its contents. The grid shows all media files with upload status overlays:
- Green checkmark = uploaded to server
- Hourglass = pending upload
- Cloud arrow = currently uploading
- Red error = upload failed
- No icon = not yet scanned

### Manual controls

In the Watched Folders screen:
- **Cloud icon** (top bar) — trigger an immediate scan and upload
- **Broom icon** (top bar) — delete local copies of all files that have been successfully uploaded

---

## Settings

### Storage

- **Image cache limit** — maximum disk space for cached full-resolution images (default: 500 MB). Oldest cached images are evicted when the limit is reached. Favorites are not affected.
- **Thumbnail cache** — shows total size of downloaded thumbnails. "Clear" forces a re-download on next sync.
- **Image cache** — shows current cache usage. "Clear" removes cached full-resolution images (not favorites).

### Appearance

- **Theme** — System default, Light, or Dark

### Diagnostics

- **Flagged images** — view photos marked for inspection with error logs
- **Pending changes** — view and manage the mutation queue
- **Force re-sync** — clears all sync state, forcing a full re-check of all directories on next sync. Does not re-download existing thumbnails.

### Server

- **Disconnect** — removes all local data (thumbnails, cache, favorites, settings) and returns to the setup screen

---

## Sync Details

### How sync works

WebGallery uses a multi-level incremental sync:

1. **PROPFIND root** — one HTTP request to discover all year directories
2. **Year-level ETag check** — skip years where the server ETag hasn't changed (except current year, which is always checked)
3. **Month-level content hash** — even without server ETags, a SHA-256 hash of thumbnail filenames detects changes
4. **Only process changed months** — fetch the file list, diff against local database, insert/update/delete
5. **Download missing thumbnails** — batch download with 8 concurrent connections

For a 35,000-photo library with no changes, a re-sync completes in under 1 second.

### Background sync

Sync runs as an Android foreground service with a notification showing progress. It continues when the app is backgrounded. Tap "Cancel" on the notification to stop.

---

## Troubleshooting

### Photos appear gray

Some thumbnails may fail to download due to network issues. Flag the photo for inspection (**long-press > Flag for inspection**) and check the error logs in **Settings > Flagged images**.

To re-download all missing thumbnails, pull down to refresh in the gallery.

### Sync seems stuck

If sync is not picking up new photos:
1. Go to **Settings > Diagnostics > Force re-sync**
2. Pull down to refresh in the gallery
3. The app will re-check all directories and download any missing thumbnails

### Uploads not working

1. Check that watched folders are enabled (toggle is on)
2. Verify media permissions are granted (the app prompts on first use)
3. Check WiFi — uploads default to WiFi-only
4. Tap the cloud icon in Watched Folders to trigger an immediate upload
