// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.model

data class ServerConfig(
    val url: String,
    val username: String,
    val password: String
) {
    val baseUrl: String get() = url.trimEnd('/')
}
