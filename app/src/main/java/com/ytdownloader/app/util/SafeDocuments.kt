package com.ytdownloader.app.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

object SafeDocuments {

    /** Display name of a single-document URI (or a tree root). */
    fun name(resolver: ContentResolver, uri: Uri): String? {
        val docUri = treeDocUri(uri) ?: uri
        return runCatching {
            resolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** Copy files into the folder behind a SAF tree URI, one new document each.
     *  Returns the created document URIs, or empty if any copy failed (rollback). */
    fun moveInto(
        resolver: ContentResolver,
        treeUri: Uri,
        files: List<File>,
        mimeOf: (File) -> String
    ): List<String> {
        if (files.isEmpty()) return emptyList()
        val parent = treeDocUri(treeUri) ?: return emptyList()
        val created = mutableListOf<Uri>()
        for (file in files) {
            try {
                val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(file), file.name)
                    ?: throw IOException("createDocument")
                resolver.openOutputStream(doc, "w")?.use { out ->
                    file.inputStream().use { ins -> ins.copyTo(out) }
                } ?: throw IOException("openOutputStream")
                created.add(doc)
            } catch (e: Exception) {
                for (uri in created) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
                return emptyList()
            }
        }
        return created.map { it.toString() }
    }

    /** Delete a document or the current child document of a tree URI. */
    fun delete(resolver: ContentResolver, uri: Uri): Boolean {
        val resetDoc = treeDocUri(uri) ?: uri
        return runCatching { DocumentsContract.deleteDocument(resolver, resetDoc) }.getOrDefault(false)
    }

    /** For a tree URI returns the URI of its root document, otherwise null. */
    private fun treeDocUri(uri: Uri): Uri? =
        if (DocumentsContract.isTreeUri(uri)) {
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            }.getOrNull()
        } else {
            null
        }
}
