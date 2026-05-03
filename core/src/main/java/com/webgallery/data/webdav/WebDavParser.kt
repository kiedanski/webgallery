// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

object WebDavParser {

    fun parseMultistatus(inputStream: InputStream): List<DavResource> {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        val resources = mutableListOf<DavResource>()
        var event = parser.eventType
        var current: ResourceBuilder? = null
        var inResourceType = false
        var seenCollection = false
        var inProp = false
        var currentTag: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name
                    when (local) {
                        "response" -> {
                            current = ResourceBuilder()
                            seenCollection = false
                        }
                        "href" -> {
                            currentTag = "href"
                        }
                        "prop" -> {
                            inProp = true
                        }
                        "resourcetype" -> {
                            inResourceType = true
                            seenCollection = false
                        }
                        "collection" -> {
                            if (inResourceType) seenCollection = true
                        }
                        "displayname" -> if (inProp) currentTag = "displayname"
                        "getcontenttype" -> if (inProp) currentTag = "getcontenttype"
                        "getcontentlength" -> if (inProp) currentTag = "getcontentlength"
                        "getetag" -> if (inProp) currentTag = "getetag"
                        "getlastmodified" -> if (inProp) currentTag = "getlastmodified"
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty() && current != null) {
                        when (currentTag) {
                            "href" -> current.href = text
                            "displayname" -> current.displayName = text
                            "getcontenttype" -> current.contentType = text
                            "getcontentlength" -> current.contentLength = text.toLongOrNull()
                            "getetag" -> current.etag = stripQuotes(text)
                            "getlastmodified" -> current.lastModified = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val local = parser.name
                    when (local) {
                        "response" -> {
                            current?.let {
                                if (it.href != null) {
                                    resources += DavResource(
                                        href = it.href!!,
                                        isCollection = it.isCollection,
                                        contentType = it.contentType,
                                        contentLength = it.contentLength,
                                        etag = it.etag,
                                        lastModified = it.lastModified,
                                        displayName = it.displayName
                                    )
                                }
                            }
                            current = null
                            currentTag = null
                        }
                        "resourcetype" -> {
                            current?.isCollection = seenCollection
                            inResourceType = false
                        }
                        "prop" -> inProp = false
                        "href", "displayname", "getcontenttype", "getcontentlength", "getetag", "getlastmodified" -> {
                            currentTag = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return resources
    }

    private fun stripQuotes(value: String): String {
        return value.trim().removeSurrounding("\"").removePrefix("W/\"").removeSuffix("\"")
    }

    private class ResourceBuilder {
        var href: String? = null
        var isCollection: Boolean = false
        var contentType: String? = null
        var contentLength: Long? = null
        var etag: String? = null
        var lastModified: String? = null
        var displayName: String? = null
    }
}
