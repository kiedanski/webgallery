// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.webdav

data class DavResource(
    val href: String,
    val isCollection: Boolean,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val displayName: String? = null
) {
    val name: String
        get() {
            val trimmed = href.trimEnd('/')
            val lastSlash = trimmed.lastIndexOf('/')
            return if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
        }
}
