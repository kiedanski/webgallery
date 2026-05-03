// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.cache

import android.content.Context
import com.webgallery.util.FileUtils
import java.io.File

class ImageCacheManager(context: Context) {

    private val cacheDir: File = File(context.cacheDir, "fullsize")
    private val favoritesDir: File = File(context.filesDir, "favorites")

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        if (!favoritesDir.exists()) favoritesDir.mkdirs()
    }

    fun getCachedFile(year: Int, month: Int, filename: String): File {
        val dir = File(cacheDir, "$year/${month.toString().padStart(2, '0')}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, filename)
    }

    fun getFavoriteFile(year: Int, month: Int, filename: String): File {
        val dir = File(favoritesDir, "$year/${month.toString().padStart(2, '0')}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, filename)
    }

    fun cacheRoot(): File = cacheDir
    fun favoritesRoot(): File = favoritesDir

    fun touch(file: File) {
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    fun calculateCacheSize(): Long = FileUtils.walkSize(cacheDir)

    fun calculateFavoritesSize(): Long = FileUtils.walkSize(favoritesDir)

    fun clearCache() {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }

    /** Returns paths of files that were evicted, so DB rows can be updated. */
    fun evictIfNeeded(limitBytes: Long): List<String> {
        if (limitBytes == Long.MAX_VALUE) return emptyList()
        var total = calculateCacheSize()
        if (total <= limitBytes) return emptyList()

        val files = cacheDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()

        val evicted = mutableListOf<String>()
        for (file in files) {
            if (total <= limitBytes) break
            val len = file.length()
            val path = file.absolutePath
            if (file.delete()) {
                total -= len
                evicted += path
            }
        }
        return evicted
    }
}
