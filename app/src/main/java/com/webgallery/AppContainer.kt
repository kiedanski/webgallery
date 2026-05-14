// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.webgallery.data.CredentialStore
import com.webgallery.data.FolderScanner
import com.webgallery.data.MutationProcessor
import com.webgallery.data.PhotoRepository
import com.webgallery.sync.SyncService
import com.webgallery.data.SettingsRepository
import com.webgallery.data.cache.ImageCacheManager
import com.webgallery.data.cache.ThumbnailStore
import com.webgallery.data.db.AppDatabase
import com.webgallery.data.webdav.BasicAuthInterceptor
import com.webgallery.data.webdav.WebDavClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    private val authInterceptor: BasicAuthInterceptor = BasicAuthInterceptor()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .retryOnConnectionFailure(true)
        .build()

    val webDavClient: WebDavClient = WebDavClient(okHttpClient, authInterceptor)

    val database: AppDatabase = AppDatabase.build(context)
    val photoDao = database.photoDao()
    val syncStateDao = database.syncStateDao()
    val photoErrorDao = database.photoErrorDao()
    val mutationDao = database.mutationDao()
    val watchedFolderDao = database.watchedFolderDao()
    val uploadDao = database.uploadDao()

    val credentialStore: CredentialStore = CredentialStore(context)
    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val thumbnailStore: ThumbnailStore = ThumbnailStore(context)
    val imageCacheManager: ImageCacheManager = ImageCacheManager(context)

    val photoRepository: PhotoRepository = PhotoRepository(
        webDavClient = webDavClient,
        photoDao = photoDao,
        syncStateDao = syncStateDao,
        photoErrorDao = photoErrorDao,
        mutationDao = mutationDao,
        thumbnailStore = thumbnailStore,
        imageCacheManager = imageCacheManager,
        settingsRepository = settingsRepository
    )

    val folderScanner: FolderScanner = FolderScanner(uploadDao, watchedFolderDao)

    val mutationProcessor: MutationProcessor = MutationProcessor(
        webDavClient = webDavClient,
        photoDao = photoDao,
        mutationDao = mutationDao,
        settingsRepository = settingsRepository,
        photoRepository = photoRepository,
        imageCacheManager = imageCacheManager
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil_disk_cache").toOkioPath())
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .build()

    init {
        // Restore saved credentials at startup
        credentialStore.loadConfig()?.let { webDavClient.configure(it) }

        // Auto-sync when a mutation is enqueued (if online)
        photoRepository.onMutationEnqueued = {
            try {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    SyncService.start(context)
                }
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+ when backgrounded
            }
        }
    }
}
