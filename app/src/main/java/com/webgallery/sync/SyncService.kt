// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.webgallery.R
import com.webgallery.WebGalleryApp
import com.webgallery.model.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d("SyncService", "Cancel requested")
            syncJob?.cancel()
            val repo = (application as WebGalleryApp).container.photoRepository
            repo.setSyncStatus(SyncStatus.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (syncJob?.isActive == true) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Syncing\u2026", 0, 0))

        syncJob = scope.launch {
            try {
                val container = (application as WebGalleryApp).container
                val repo = container.photoRepository
                repo.sync { current, total ->
                    updateNotification(current, total)
                }
                // After sync, process any pending mutations
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("Processing queued changes\u2026", 0, 0))
                container.mutationProcessor.processQueue()
                // After mutations, trigger upload if there are watched folders
                UploadService.start(applicationContext)
            } catch (e: Exception) {
                Log.e("SyncService", "Sync failed", e)
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Photo sync progress"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val cancelIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_STOP
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("WebGallery")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancelPending)

        if (total > 0) {
            builder.setProgress(total, current, false)
            builder.setContentText("Downloading thumbnails: $current / $total")
        }

        return builder.build()
    }

    private fun updateNotification(current: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Downloading thumbnails: $current / $total", current, total))
    }

    companion object {
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.webgallery.STOP_SYNC"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
