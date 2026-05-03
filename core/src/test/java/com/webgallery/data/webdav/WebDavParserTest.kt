// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavParserTest {

    @Test
    fun parsesDirectoryAndFile() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/photos/_thumbnails/2024/03/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/photos/_thumbnails/2024/03/IMG_1234.webp</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>IMG_1234.webp</d:displayname>
                    <d:getcontenttype>image/webp</d:getcontenttype>
                    <d:getcontentlength>12345</d:getcontentlength>
                    <d:getetag>"a1b2c3d4"</d:getetag>
                    <d:getlastmodified>Mon, 01 Mar 2024 12:00:00 GMT</d:getlastmodified>
                    <d:resourcetype/>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val resources = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertEquals(2, resources.size)

        val dir = resources[0]
        assertTrue(dir.isCollection)
        assertEquals("/dav/photos/_thumbnails/2024/03/", dir.href)

        val file = resources[1]
        assertFalse(file.isCollection)
        assertEquals("IMG_1234.webp", file.displayName)
        assertEquals("image/webp", file.contentType)
        assertEquals(12345L, file.contentLength)
        assertEquals("a1b2c3d4", file.etag) // quotes stripped
        assertEquals("Mon, 01 Mar 2024 12:00:00 GMT", file.lastModified)
    }

    @Test
    fun stripsQuotedAndWeakEtags() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/file1</d:href>
                <d:propstat><d:prop><d:getetag>"abc"</d:getetag><d:resourcetype/></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/file2</d:href>
                <d:propstat><d:prop><d:getetag>W/"weak123"</d:getetag><d:resourcetype/></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/file3</d:href>
                <d:propstat><d:prop><d:getetag>noquotes</d:getetag><d:resourcetype/></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val r = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertEquals("abc", r[0].etag)
        assertEquals("weak123", r[1].etag)
        assertEquals("noquotes", r[2].etag)
    }

    @Test
    fun handlesMissingProperties() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/photos/2024/03/IMG.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val r = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertEquals(1, r.size)
        assertNull(r[0].contentType)
        assertNull(r[0].contentLength)
        assertNull(r[0].etag)
        assertFalse(r[0].isCollection)
    }

    @Test
    fun handlesEmptyMultistatus() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>""".trimIndent()
        val r = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertTrue(r.isEmpty())
    }

    @Test
    fun computesNameFromHref() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/photos/2024/03/IMG_1234.jpg</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/photos/2024/03/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val r = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertEquals("IMG_1234.jpg", r[0].name)
        assertEquals("03", r[1].name)
    }

    @Test
    fun parsesContentLengthAsLong() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/big.bin</d:href>
                <d:propstat><d:prop>
                  <d:getcontentlength>4294967296</d:getcontentlength>
                  <d:resourcetype/>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/bad.bin</d:href>
                <d:propstat><d:prop>
                  <d:getcontentlength>not-a-number</d:getcontentlength>
                  <d:resourcetype/>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val r = WebDavParser.parseMultistatus(xml.byteInputStream())
        assertEquals(4_294_967_296L, r[0].contentLength)
        assertNull(r[1].contentLength)
    }
}
