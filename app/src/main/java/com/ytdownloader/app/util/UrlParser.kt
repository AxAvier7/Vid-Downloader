package com.ytdownloader.app.util

import android.net.Uri

object UrlParser {

    data class Parsed(
        val videoId: String?,
        val playlistId: String?,
        val url: String,
        val isYoutube: Boolean
    )

    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val isYoutube = host.contains("youtube.com") || host.contains("youtu.be")
        return Parsed(
            videoId = extractVideoId(uri, host),
            playlistId = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() },
            url = url,
            isYoutube = isYoutube
        )
    }

    private fun extractVideoId(uri: Uri, host: String): String? {
        val path = uri.path ?: ""
        if (host.contains("youtu.be")) {
            return path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
        }
        uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
        for (prefix in listOf("/shorts/", "/embed/", "/live/", "/v/")) {
            if (path.startsWith(prefix)) {
                return path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}