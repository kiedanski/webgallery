// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.util

import com.webgallery.model.MediaType
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object FileUtils {

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes == Long.MAX_VALUE) return "Unlimited"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size - 1)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun mimeToMediaType(mime: String?): MediaType = MediaType.fromMimeType(mime)

    fun filenameWithoutExtension(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0) filename.substring(0, dot) else filename
    }

    fun extensionOf(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot in 0 until filename.length - 1) filename.substring(dot + 1) else ""
    }

    fun walkSize(directory: java.io.File): Long {
        if (!directory.exists()) return 0L
        var total = 0L
        directory.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun monthName(month: Int): String {
        return when (month) {
            1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
            5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
            9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
            else -> "Month $month"
        }
    }

    fun formatDate(year: Int, month: Int, day: Int): String =
        "${monthName(month)} $day, $year"

    fun parseLastModified(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss z"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                return sdf.parse(value)?.time ?: 0L
            } catch (_: Exception) {}
        }
        return 0L
    }
}
