// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.webdav

import com.webgallery.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

class WebDavClient(
    private val okHttpClient: OkHttpClient,
    private val authInterceptor: BasicAuthInterceptor
) {

    @Volatile
    private var config: ServerConfig? = null

    fun configure(config: ServerConfig?) {
        this.config = config
        if (config != null) {
            authInterceptor.setCredentials(config.username, config.password)
        } else {
            authInterceptor.setCredentials(null, null)
        }
    }

    fun isConfigured(): Boolean = config != null

    fun currentConfig(): ServerConfig? = config

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            val request = buildRequest(cfg.baseUrl, "/dav/photos/", "PROPFIND", 0, MINIMAL_PROPFIND_BODY)
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    207 -> true
                    401 -> throw UnauthorizedException()
                    else -> throw IOException("Unexpected response: ${response.code}")
                }
            }
        }
    }

    suspend fun propfind(path: String, depth: Int = 1): Result<List<DavResource>> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            val request = buildRequest(cfg.baseUrl, path, "PROPFIND", depth, FULL_PROPFIND_BODY)
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw UnauthorizedException()
                if (response.code != 207) throw IOException("PROPFIND failed: ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                WebDavParser.parseMultistatus(body.byteStream())
                    .map { normalizeResource(it, cfg.baseUrl) }
            }
        }
    }

    suspend fun downloadFile(
        remotePath: String,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            val request = Request.Builder()
                .url(buildUrl(cfg.baseUrl, remotePath))
                .get()
                .build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 401) throw UnauthorizedException()
                    if (!response.isSuccessful) throw HttpException(response.code, "Download failed")
                    val body = response.body ?: throw IOException("Empty body")
                    val total = body.contentLength()
                    var read = 0L
                    body.source().use { source ->
                        tmpFile.sink().buffer().use { sink ->
                            val buffer = okio.Buffer()
                            while (true) {
                                val bytes = source.read(buffer, 64 * 1024)
                                if (bytes == -1L) break
                                sink.write(buffer, bytes)
                                read += bytes
                                if (onProgress != null && total > 0) {
                                    onProgress(read, total)
                                }
                            }
                        }
                    }
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                targetFile
            } catch (t: Throwable) {
                tmpFile.delete()
                throw t
            }
        }
    }

    suspend fun putFile(
        remotePath: String,
        sourceFile: File,
        contentType: String = "application/octet-stream"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            val body = sourceFile.asRequestBody(contentType.toMediaType())
            val request = Request.Builder()
                .url(buildUrl(cfg.baseUrl, remotePath))
                .put(body)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw UnauthorizedException()
                if (!response.isSuccessful) throw HttpException(response.code, "PUT failed")
            }
        }
    }

    suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            val request = Request.Builder()
                .url(buildUrl(cfg.baseUrl, remotePath))
                .delete()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw UnauthorizedException()
                if (!response.isSuccessful) throw HttpException(response.code, "DELETE failed")
            }
        }
    }

    suspend fun proppatch(
        remotePath: String,
        setProperties: Map<String, String> = emptyMap(),
        removeProperties: List<String> = emptyList(),
        namespace: String = WEBGALLERY_NS
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = config ?: return@withContext Result.failure(IllegalStateException("Not configured"))
        runCatching {
            val body = buildProppatchBody(setProperties, removeProperties, namespace)
            val mediaType = "application/xml; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(buildUrl(cfg.baseUrl, remotePath))
                .method("PROPPATCH", body.toRequestBody(mediaType))
                .header("Content-Type", "application/xml; charset=utf-8")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw UnauthorizedException()
                if (response.code !in listOf(200, 207)) throw HttpException(response.code, "PROPPATCH failed")
            }
        }
    }

    private fun buildProppatchBody(
        setProps: Map<String, String>,
        removeProps: List<String>,
        namespace: String
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<d:propertyupdate xmlns:d="DAV:" xmlns:app="$namespace">""")
        if (setProps.isNotEmpty()) {
            sb.append("<d:set><d:prop>")
            for ((key, value) in setProps) {
                sb.append("<app:$key>")
                sb.append(escapeXml(value))
                sb.append("</app:$key>")
            }
            sb.append("</d:prop></d:set>")
        }
        if (removeProps.isNotEmpty()) {
            sb.append("<d:remove><d:prop>")
            for (key in removeProps) {
                sb.append("<app:$key/>")
            }
            sb.append("</d:prop></d:remove>")
        }
        sb.append("</d:propertyupdate>")
        return sb.toString()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun buildUrl(baseUrl: String, path: String): String {
        val cleanedBase = baseUrl.trimEnd('/')
        val cleanedPath = if (path.startsWith("/")) path else "/$path"
        // Encode each segment but preserve slashes
        val encoded = cleanedPath.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) segment else encodeSegment(segment)
        }
        return "$cleanedBase$encoded"
    }

    private fun encodeSegment(segment: String): String {
        val safeChars = "._-~"
        val sb = StringBuilder()
        val bytes = segment.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if ((c in 0x30..0x39) || (c in 0x41..0x5A) || (c in 0x61..0x7A) || c.toChar() in safeChars) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c shr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun buildRequest(baseUrl: String, path: String, method: String, depth: Int, body: String): Request {
        val mediaType = "application/xml; charset=utf-8".toMediaType()
        return Request.Builder()
            .url(buildUrl(baseUrl, path))
            .method(method, body.toRequestBody(mediaType))
            .header("Depth", depth.toString())
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
    }

    private fun normalizeResource(resource: DavResource, baseUrl: String): DavResource {
        val href = resource.href
        val withoutHost = if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                val uri = java.net.URI(href)
                uri.rawPath ?: href
            } catch (e: Exception) {
                href
            }
        } else {
            href
        }
        val decoded = try {
            java.net.URLDecoder.decode(withoutHost, "UTF-8")
        } catch (e: Exception) {
            withoutHost
        }
        val relative = decoded
            .removePrefix("/")
            .removePrefix("dav/photos/")
            .removePrefix("dav/photos")
        return resource.copy(href = relative)
    }

    class UnauthorizedException : IOException("Unauthorized")
    class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

    companion object {
        const val PHOTOS_BASE_PATH = "/dav/photos/"
        const val WEBGALLERY_NS = "http://webgallery.app/ns/"

        private const val MINIMAL_PROPFIND_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""

        private const val FULL_PROPFIND_BODY = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontenttype/>
    <d:getcontentlength/>
    <d:getetag/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""
    }
}
