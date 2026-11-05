package com.ytdownloader.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ytdownloader.app.model.DownloadStatus

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val kind: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val filePath: String = "",
    val thumbPath: String = "",
    val status: String = DownloadStatus.QUEUED.name,
    val progress: Float = 0f,
    val error: String = "",
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val destPaths: String = "",
    // Opciones necesarias para poder reanudar la descarga tras cerrar la app.
    val maxHeight: Int? = null,
    val audioQuality: String = "",
    val withSubtitles: Boolean = true
)