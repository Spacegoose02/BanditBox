package com.spacegoose.banditbox.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    const val DEFAULT_PORT = 8686

    /** "192.168.1.5", "nas:8686" or "http://nas:8686/" become "http://host:port"; null if it isn't an address. */
    fun normalize(address: String): String? {
        val a = address.trim().trimEnd('/')
        if (a.isEmpty() || a.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in a) a else "http://$a"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        val authority = withScheme.substringAfter("://").substringBefore('/')
        val hasPort = Regex(":\\d+$").containsMatchIn(authority)
        val port = when {
            hasPort -> url.port
            url.scheme == "https" -> 443
            else -> DEFAULT_PORT
        }
        return url.newBuilder().port(port).encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    }
}
