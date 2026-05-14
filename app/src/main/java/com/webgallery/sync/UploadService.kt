// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.webgallery.R
import com.webgallery.WebGalleryApp
import com.webgallery.data.db.UploadEntity
import com.webgallery.data.db.WatchedFolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class UploadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Cancel requested")
            uploadJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (uploadJob?.isActive == true) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Scanning for new files\u2026", 0, 0))

        uploadJob = scope.launch {
            try {
                val container = (application as WebGalleryApp).container
                val scanner = container.folderScanner
                val uploadDao = container.uploadDao
                val watchedFolderDao = container.watchedFolderDao
                val webDavClient = container.webDavClient

                // Scan for new files
                val newFiles = scanner.scanAll()
                Log.d(TAG, "Scan found ${newFiles.size} new files")

                // Get all pending uploads
                val pending = uploadDao.getPending()
                if (pending.isEmpty()) {
                    Log.d(TAG, "No pending uploads")
                    return@launch
                }

                val total = pending.size
                var current = 0

                // Group by folder for WiFi check
                val foldersById = watchedFolderDao.getEnabled().associateBy { it.id }

                for (upload in pending) {
                    current++
                    updateNotification(upload.fileName, current, total)

                    val folder = foldersById[upload.folderId]
                    if (folder?.wifiOnly == true && !isOnWifi()) {
                        Log.d(TAG, "Skipping ${upload.fileName}: WiFi required")
                        continue
                    }

                    val file = File(upload.localPath)
                    if (!file.exists()) {
                        uploadDao.updateStatus(upload.id, UploadEntity.STATUS_UPLOADED, "File already removed")
                        continue
                    }

                    uploadDao.updateStatus(upload.id, UploadEntity.STATUS_UPLOADING)

                    val result = webDavClient.putFile(
                        "/dav/photos/_inbox/${upload.fileName}",
                        file,
                        upload.mimeType
                    )

                    if (result.isSuccess) {
                        val now = System.currentTimeMillis()
                        uploadDao.updateStatus(upload.id, UploadEntity.STATUS_UPLOADED, uploadedAt = now)
                        Log.d(TAG, "Uploaded ${upload.fileName}")

                        if (folder?.deleteAfterUpload == true) {
                            // Try direct delete (works for app-owned files only)
                            if (file.delete()) {
                                uploadDao.markDeleted(upload.id)
                                Log.d(TAG, "Deleted local ${upload.fileName}")
                            } else {
                                // Can't delete other apps' files from a service —
                                // user needs to use the cleanup button (requires system dialog)
                                Log.d(TAG, "Uploaded ${upload.fileName} — use cleanup button to free local storage")
                            }
                        }
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Unknown error"
                        uploadDao.updateStatus(upload.id, UploadEntity.STATUS_FAILED, err)
                        Log.e(TAG, "Upload failed: ${upload.fileName}: $err")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload service error", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun deleteMediaFile(file: java.io.File): Boolean {
        // Try direct delete first (works for app-owned files)
        if (file.delete()) return true

        // Fall back to MediaStore for shared storage files
        val resolver = contentResolver
        val uri = MediaStore.Files.getContentUri("external")
        val deleted = resolver.delete(
            uri,
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.absolutePath)
        )
        return deleted > 0
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Upload",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Photo upload progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val cancelIntent = Intent(this, UploadService::class.java).apply { action = ACTION_STOP }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("WebGallery Upload")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancelPending)
            .apply {
                if (total > 0) setProgress(total, current, false)
            }
            .build()
    }

    private fun updateNotification(fileName: String, current: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Uploading $current/$total: $fileName", current, total))
    }

    companion object {
        private const val TAG = "UploadService"
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.webgallery.STOP_UPLOAD"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, UploadService::class.java))
        }
    }
}
