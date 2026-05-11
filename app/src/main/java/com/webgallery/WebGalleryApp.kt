// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery

import android.app.Application
import coil3.SingletonImageLoader
import com.webgallery.sync.UploadScanWorker

class WebGalleryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        SingletonImageLoader.setSafe { container.imageLoader }
        UploadScanWorker.schedule(this)
    }

    companion object {
        lateinit var instance: WebGalleryApp
            private set
    }
}
