// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.model

enum class MediaType {
    PHOTO,
    VIDEO;

    companion object {
        fun fromMimeType(mime: String?): MediaType {
            val lower = mime?.lowercase().orEmpty()
            return when {
                lower.startsWith("video/") -> VIDEO
                else -> PHOTO
            }
        }
    }
}
