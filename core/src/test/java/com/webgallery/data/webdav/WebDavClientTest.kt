package com.webgallery.data.webdav

import com.webgallery.model.ServerConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: BasicAuthInterceptor
    private lateinit var client: WebDavClient
    private lateinit var tmp: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        auth = BasicAuthInterceptor()
        val ok = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .build()
        client = WebDavClient(ok, auth)
        tmp = Files.createTempDirectory("webdav-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    @Test
    fun testConnection_success_on207() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "user", "pass"))
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))

        val res = client.testConnection()
        assertTrue(res.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("/dav/photos/", recorded.path)
        assertEquals("0", recorded.getHeader("Depth"))
        assertNotNull(recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun testConnection_unauthorized_on401() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "user", "wrong"))
        server.enqueue(MockResponse().setResponseCode(401))

        val res = client.testConnection()
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is WebDavClient.UnauthorizedException)
    }

    @Test
    fun propfind_parsesResources() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/photos/2024/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/photos/2024/03/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getetag>"abc"</d:getetag></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val res = client.propfind("/dav/photos/2024/", depth = 1)
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(2, list.size)
        // hrefs are normalized to relative paths (server prefix stripped)
        assertEquals("2024/", list[0].href)
        assertEquals("2024/03/", list[1].href)
        assertEquals("abc", list[1].etag)
    }

    @Test
    fun propfind_failsOn401() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        server.enqueue(MockResponse().setResponseCode(401))
        val res = client.propfind("/dav/photos/", depth = 1)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is WebDavClient.UnauthorizedException)
    }

    @Test
    fun downloadFile_writesFileAndCleansTemp() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        val payload = "hello world".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        val target = File(tmp, "subdir/test.bin")
        val res = client.downloadFile("/dav/photos/test.bin", target)

        assertTrue(res.isSuccess)
        assertTrue(target.exists())
        assertEquals(payload.size.toLong(), target.length())
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test
    fun downloadFile_reportsProgress() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        val data = ByteArray(200_000) { (it % 256).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", data.size.toString())
                .setBody(okio.Buffer().write(data))
        )

        var maxRead = 0L
        var seenTotal = 0L
        val target = File(tmp, "big.bin")
        val res = client.downloadFile("/dav/photos/big.bin", target) { read, total ->
            maxRead = maxOf(maxRead, read)
            seenTotal = total
        }
        assertTrue(res.isSuccess)
        assertEquals(data.size.toLong(), target.length())
        assertEquals(data.size.toLong(), seenTotal)
        assertTrue(maxRead > 0)
    }

    @Test
    fun downloadFile_deletesTmpOnFailure() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        server.enqueue(MockResponse().setResponseCode(500))

        val target = File(tmp, "fail.bin")
        val res = client.downloadFile("/dav/photos/fail.bin", target)
        assertTrue(res.isFailure)
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test
    fun urlEncodesSpacesAndUnicode() = runBlocking {
        client.configure(ServerConfig(server.url("").toString().trimEnd('/'), "u", "p"))
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))

        client.propfind("/dav/photos/My Album/Pâté.jpg", depth = 0)
        val recorded = server.takeRequest()
        // Spaces become %20, é becomes %C3%A9
        assertTrue("path was ${recorded.path}", recorded.path!!.contains("My%20Album"))
        assertTrue("path was ${recorded.path}", recorded.path!!.contains("P%C3%A2t%C3%A9.jpg"))
    }
}
