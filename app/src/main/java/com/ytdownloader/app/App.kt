package com.ytdownloader.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.ytdownloader.app.data.DownloadRepository
import com.ytdownloader.app.model.DownloadStatus
import com.ytdownloader.app.util.Prefs
import com.ytdownloader.app.ytdl.YtdlInitProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {

    // Momento en que arrancó este proceso: las filas creadas antes son huérfanas.
    private val processStartMs = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        YtdlInitProvider.warmUp(this)
        DownloadRepository.get(this)
        retakeDestinationPermission()
        pauseStuckDownloads()
    }

    /**
     * Si el proceso murió con descargas en curso, se dejan en PAUSED para que el usuario
     * pueda reanudarlas (yt-dlp continúa desde el archivo .part).
     */
    private fun pauseStuckDownloads() {
        val startedAt = processStartMs
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                DownloadRepository.get(this@App).dao().failStuck(
                    listOf(DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name),
                    DownloadStatus.PAUSED.name,
                    "",
                    startedAt
                )
            }
        }
    }

    private fun retakeDestinationPermission() {
        val uriText = Prefs.destinationUri(this)
        if (uriText.isBlank()) return
        try {
            val uri = Uri.parse(uriText)
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }
}