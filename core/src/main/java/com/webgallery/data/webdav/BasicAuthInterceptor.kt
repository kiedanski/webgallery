// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.webdav

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

class BasicAuthInterceptor : Interceptor {

    private val credentials = AtomicReference<String?>(null)
    private val serverHost = AtomicReference<String?>(null)

    fun setCredentials(username: String?, password: String?) {
        credentials.set(
            if (username != null && password != null) Credentials.basic(username, password)
            else null
        )
    }

    fun setServerHost(host: String?) {
        serverHost.set(host)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val auth = credentials.get()
        val host = serverHost.get()
        val requestHost = chain.request().url.host
        // Only attach credentials to requests going to the configured server
        val request = if (auth != null && host != null && requestHost == host) {
            chain.request().newBuilder().header("Authorization", auth).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
