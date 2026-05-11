// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExifEditor {

    private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * Reads DateTimeOriginal from the file.
     * Returns the EXIF date string or null if not present/readable.
     */
    fun readDate(file: File): String? {
        return try {
            val exif = ExifInterface(file)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes DateTimeOriginal (and DateTime, DateTimeDigitized) to the file.
     * @param dateStr format: "yyyy:MM:dd HH:mm:ss"
     * @return true if successful
     */
    fun writeDate(file: File, dateStr: String): Boolean {
        return try {
            val exif = ExifInterface(file)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts a Date to EXIF date format string.
     */
    fun formatDateForExif(date: Date): String = EXIF_DATE_FORMAT.format(date)

    /**
     * Parses an EXIF date string to a Date.
     */
    fun parseDateFromExif(dateStr: String): Date? {
        return try {
            EXIF_DATE_FORMAT.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}
