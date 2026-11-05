package com.ytdownloader.app.util

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

object FileUtils {

    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "m4v", "mov", "m4a", "mp3", "opus", "ogg", "flac", "wav", "aac")
    private val THUMB_EXT = setOf("jpg", "jpeg", "png", "webp")
    private val SUB_EXT = setOf("srt", "vtt", "ttml", "ass")

    fun isMediaFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in MEDIA_EXT

    fun isThumbFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in THUMB_EXT

    fun isSubFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in SUB_EXT

    fun listNew(root: File, before: Set<String>, after: Long): List<File> {
        val files = root.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name !in before && it.lastModified() >= after }
    }

    fun deleteAll(paths: Collection<String>) {
        for (p in paths) {
            try {
                File(p).delete()
            } catch (_: Exception) {
            }
        }
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "mp4", "m4v", "mov" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "srt", "vtt" -> "text/plain"
            "ass", "ttml" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val mb = bytes / 1024.0 / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.ROOT, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.ROOT, "%.1f MB", mb)
            else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        }
    }
}