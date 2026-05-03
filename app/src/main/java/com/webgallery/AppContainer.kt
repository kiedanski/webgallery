package com.webgallery

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.webgallery.data.CredentialStore
import com.webgallery.data.PhotoRepository
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

    val credentialStore: CredentialStore = CredentialStore(context)
    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val thumbnailStore: ThumbnailStore = ThumbnailStore(context)
    val imageCacheManager: ImageCacheManager = ImageCacheManager(context)

    val photoRepository: PhotoRepository = PhotoRepository(
        webDavClient = webDavClient,
        photoDao = photoDao,
        syncStateDao = syncStateDao,
        thumbnailStore = thumbnailStore,
        imageCacheManager = imageCacheManager,
        settingsRepository = settingsRepository
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(okHttpClient))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil_disk_cache"))
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .build()

    init {
        // Restore saved credentials at startup
        credentialStore.loadConfig()?.let { webDavClient.configure(it) }
    }
}
