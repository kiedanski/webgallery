# WebGallery

A minimal, F-Droid-compatible Android gallery app that displays photos and
videos served from a [Tilde](https://github.com/user/tilde) personal cloud
server via WebDAV.

## Features

- Browse 256px thumbnails offline after a one-time sync
- Full-resolution photo viewer with pinch-to-zoom, double-tap zoom, and
  horizontal swipe between photos in the same month
- Video streaming with Media3 (ExoPlayer) and authenticated download
- Mark photos and videos as favorites for permanent offline access
- Configurable LRU cache for cached full-size images
- Material 3 UI with light/dark/system theming and dynamic colors on Android 12+
- HTTP Basic Auth credentials stored in EncryptedSharedPreferences
- Zero proprietary dependencies — fully F-Droid compatible

## Requirements

- Android 8.0 (API 26) or newer
- A reachable Tilde server with `/dav/photos/` accessible via WebDAV
- Server-generated 256px thumbnails under `_thumbnails/{year}/{month}/`

## Building

```
./gradlew :app:assembleDebug
```

You need JDK 17 and an Android SDK with platform 35 / build tools 35 installed.

## License

GPLv3 — see source headers.
