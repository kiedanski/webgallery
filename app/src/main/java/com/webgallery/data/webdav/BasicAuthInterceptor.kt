package com.webgallery.data.webdav

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

class BasicAuthInterceptor : Interceptor {

    private val credentials = AtomicReference<String?>(null)

    fun setCredentials(username: String?, password: String?) {
        credentials.set(
            if (username != null && password != null) Credentials.basic(username, password)
            else null
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val auth = credentials.get()
        val request = if (auth != null) {
            chain.request().newBuilder().header("Authorization", auth).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
