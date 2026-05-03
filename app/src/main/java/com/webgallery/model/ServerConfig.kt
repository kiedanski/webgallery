package com.webgallery.model

data class ServerConfig(
    val url: String,
    val username: String,
    val password: String
) {
    val baseUrl: String get() = url.trimEnd('/')
}
