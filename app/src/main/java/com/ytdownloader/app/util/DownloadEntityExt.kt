package com.ytdownloader.app.util

import android.content.Context
import android.net.Uri
import com.ytdownloader.app.data.DownloadEntity
import java.io.File

fun DownloadEntity.files(): List<File> =
    filePath.split("\n").asSequence()
        .filter { it.isNotBlank() }
        .map { File(it) }
        .filter { it.exists() && it.isFile }
        .toList()

fun DownloadEntity.mediaFiles(): List<File> = files().filter { FileUtils.isMediaFile(it) }

fun DownloadEntity.destUris(): List<Uri> =
    destPaths.split("\n").filter { it.isNotBlank() }.map { Uri.parse(it) }

fun DownloadEntity.deleteDestination(context: Context) {
    val resolver = context.contentResolver
    for (uri in destUris()) {
        val deleted = runCatching { resolver.delete(uri, null, null) >= 1 }.getOrDefault(false)
            if (!deleted) {
                SafeDocuments.delete(context.contentResolver, uri)
            }
    }
}