// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.model

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Syncing(val current: Int, val total: Int) : SyncStatus()
    data object Complete : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data object Offline : SyncStatus()
}
