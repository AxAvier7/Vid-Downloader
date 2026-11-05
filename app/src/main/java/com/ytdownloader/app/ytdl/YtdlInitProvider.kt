package com.ytdownloader.app.ytdl

import android.content.Context
import com.ytdownloader.app.util.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object YtdlInitProvider {

    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val RETRY_INTERVAL_MS = 15L * 60 * 1000

    private val mutex = Mutex()
    private val updateMutex = Mutex()

    @Volatile
    private var ready = false

    @Volatile
    var lastError: String? = null
        private set

    fun warmUp(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { init(app) }
    }

    suspend fun init(context: Context): Boolean {
        if (ready) return true
        mutex.withLock {
            if (ready) return true
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: "Error de inicialización"
            }
        }
        return ready
    }

    /**
     * Actualiza el binario de yt-dlp como mucho una vez al día (o 15 min después de un fallo).
     * YouTube cambia a menudo: un yt-dlp viejo acaba dando 403 al descargar datos del vídeo.
     */
    suspend fun ensureLatest(context: Context) {
        val app = context.applicationContext
        if (System.currentTimeMillis() < Prefs.ytdlNextUpdateAt(app)) return
        updateMutex.withLock {
            if (System.currentTimeMillis() < Prefs.ytdlNextUpdateAt(app)) return@withLock
            val updated = runCatching {
                if (!init(app)) return@runCatching false
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                true
            }.getOrDefault(false)
            val interval = if (updated) UPDATE_INTERVAL_MS else RETRY_INTERVAL_MS
            Prefs.setYtdlNextUpdateAt(app, System.currentTimeMillis() + interval)
        }
    }

    /** Fuerza la actualización en el momento (recuperación ante un 403). */
    suspend fun forceUpdate(context: Context): Boolean {
        val app = context.applicationContext
        return updateMutex.withLock {
            runCatching {
                if (!init(app)) return@withLock false
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                Prefs.setYtdlNextUpdateAt(app, System.currentTimeMillis() + UPDATE_INTERVAL_MS)
                true
            }.getOrDefault(false)
        }
    }
}