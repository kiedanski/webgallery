package com.webgallery.util

import com.webgallery.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {

    @Test
    fun formatFileSize_zero() {
        assertEquals("0 B", FileUtils.formatFileSize(0))
        assertEquals("0 B", FileUtils.formatFileSize(-1))
    }

    @Test
    fun formatFileSize_bytes() {
        assertTrue(FileUtils.formatFileSize(512).startsWith("512"))
    }

    @Test
    fun formatFileSize_kilobytes() {
        // 1500 bytes ≈ 1.5 KB
        val s = FileUtils.formatFileSize(1500)
        assertTrue("got $s", s.endsWith("KB"))
    }

    @Test
    fun formatFileSize_megabytes() {
        val s = FileUtils.formatFileSize(3_500_000L)
        assertTrue("got $s", s.endsWith("MB"))
    }

    @Test
    fun formatFileSize_gigabytes() {
        val s = FileUtils.formatFileSize(2L * 1024 * 1024 * 1024)
        assertTrue("got $s", s.endsWith("GB"))
    }

    @Test
    fun formatFileSize_unlimited() {
        assertEquals("Unlimited", FileUtils.formatFileSize(Long.MAX_VALUE))
    }

    @Test
    fun mimeToMediaType_image() {
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType("image/jpeg"))
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType("image/png"))
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType("IMAGE/HEIC"))
    }

    @Test
    fun mimeToMediaType_video() {
        assertEquals(MediaType.VIDEO, FileUtils.mimeToMediaType("video/mp4"))
        assertEquals(MediaType.VIDEO, FileUtils.mimeToMediaType("video/quicktime"))
    }

    @Test
    fun mimeToMediaType_unknownDefaultsToPhoto() {
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType(null))
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType(""))
        assertEquals(MediaType.PHOTO, FileUtils.mimeToMediaType("application/octet-stream"))
    }

    @Test
    fun filenameWithoutExtension() {
        assertEquals("IMG_1234", FileUtils.filenameWithoutExtension("IMG_1234.jpg"))
        assertEquals("IMG_1234", FileUtils.filenameWithoutExtension("IMG_1234.JPEG"))
        assertEquals("photo", FileUtils.filenameWithoutExtension("photo"))
        assertEquals("a.b", FileUtils.filenameWithoutExtension("a.b.c"))
    }

    @Test
    fun extensionOf() {
        assertEquals("jpg", FileUtils.extensionOf("IMG_1234.jpg"))
        assertEquals("MP4", FileUtils.extensionOf("clip.MP4"))
        assertEquals("", FileUtils.extensionOf("noextension"))
        assertEquals("c", FileUtils.extensionOf("a.b.c"))
    }

    @Test
    fun monthName() {
        assertEquals("January", FileUtils.monthName(1))
        assertEquals("December", FileUtils.monthName(12))
        assertEquals("March", FileUtils.monthName(3))
    }

    @Test
    fun formatDate() {
        assertEquals("March 15, 2024", FileUtils.formatDate(2024, 3, 15))
    }

    @Test
    fun parseLastModified_validRfc7231() {
        val ms = FileUtils.parseLastModified("Mon, 01 Mar 2024 12:00:00 GMT")
        assertTrue(ms > 0L)
    }

    @Test
    fun parseLastModified_invalid() {
        assertEquals(0L, FileUtils.parseLastModified(null))
        assertEquals(0L, FileUtils.parseLastModified(""))
        assertEquals(0L, FileUtils.parseLastModified("not a date"))
    }
}
