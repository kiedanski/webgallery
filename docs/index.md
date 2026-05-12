---
layout: default
title: WebGallery
---

# WebGallery

A private, self-hosted photo gallery for Android — your photos, your server, no cloud.

[Download Latest](https://github.com/kiedanski/webgallery/releases/latest){: .btn }
[User Guide](guide){: .btn }
[View on GitHub](https://github.com/kiedanski/webgallery){: .btn }

---

## What is WebGallery?

WebGallery is a companion Android app for [Tilde](https://github.com/kiedanski/tilde), a personal cloud server. It gives you a fast, native gallery experience for browsing, organizing, and uploading your photo library — all over a standard WebDAV connection.

Unlike Google Photos or iCloud, your photos stay on hardware you control. WebGallery is the mobile interface to that library.

## Why WebGallery?

- **Private** — Your photos never leave your server. No analytics, no tracking, no third-party services.
- **Offline-first** — Thumbnails are synced for instant browsing. Full images are cached on demand. Edits queue locally and push when online.
- **Fast at scale** — Designed for libraries with 100,000+ photos. ETag-based incremental sync means re-syncs complete in under a second.
- **Auto-upload** — Watch folders on your phone (Camera, WhatsApp, etc.) and automatically back up new photos to your server.
- **Open source** — GPLv3 licensed, zero proprietary dependencies, F-Droid compatible.

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/kiedanski/webgallery/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" />
  <img src="https://raw.githubusercontent.com/kiedanski/webgallery/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" />
  <img src="https://raw.githubusercontent.com/kiedanski/webgallery/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" />
  <img src="https://raw.githubusercontent.com/kiedanski/webgallery/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200" />
</p>

## Quick Start

1. **Install** — Download the APK from [Releases](https://github.com/kiedanski/webgallery/releases/latest) or build from source
2. **Connect** — Enter your Tilde server URL and credentials
3. **Sync** — Pull down to sync your library. Thumbnails download in the background.
4. **Browse** — Swipe through your photos organized by year and month

See the [User Guide](guide) for full documentation.

---

## Requirements

| Requirement | Details |
|---|---|
| Android | 8.0+ (API 26) |
| Server | [Tilde](https://github.com/kiedanski/tilde) with WebDAV enabled |
| Network | Any — WiFi recommended for initial sync |
