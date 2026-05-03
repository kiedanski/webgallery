package com.webgallery.data.cache

import android.content.Context
import com.webgallery.util.FileUtils
import java.io.File

class ThumbnailStore(context: Context) {

    private val baseDir: File = File(context.cacheDir, "thumbnails")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    fun getThumbnailFile(year: Int, month: Int, filename: String): File {
        val dir = File(baseDir, "$year/${month.toString().padStart(2, '0')}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, filename)
    }

    fun thumbnailExists(year: Int, month: Int, filename: String): Boolean =
        getThumbnailFile(year, month, filename).exists()

    fun deleteThumbnail(localPath: String) {
        val file = File(localPath)
        if (file.exists()) file.delete()
    }

    fun deleteAllThumbnails() {
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
            baseDir.mkdirs()
        }
    }

    fun calculateTotalSize(): Long = FileUtils.walkSize(baseDir)

    fun rootDir(): File = baseDir
}
