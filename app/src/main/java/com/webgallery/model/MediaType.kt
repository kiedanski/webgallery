package com.webgallery.model

enum class MediaType {
    PHOTO,
    VIDEO;

    companion object {
        fun fromMimeType(mime: String?): MediaType {
            val lower = mime?.lowercase().orEmpty()
            return when {
                lower.startsWith("video/") -> VIDEO
                else -> PHOTO
            }
        }
    }
}
