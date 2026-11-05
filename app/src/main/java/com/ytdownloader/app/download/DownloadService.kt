package com.ytdownloader.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.ytdownloader.app.R
import com.ytdownloader.app.data.DownloadDao
import com.ytdownloader.app.data.DownloadEntity
import com.ytdownloader.app.data.DownloadRepository
import com.ytdownloader.app.model.DownloadFormat
import com.ytdownloader.app.model.DownloadOptions
import com.ytdownloader.app.model.DownloadScope
import com.ytdownloader.app.model.DownloadStatus
import com.ytdownloader.app.ui.MainActivity
import com.ytdownloader.app.util.FileUtils
import com.ytdownloader.app.util.Prefs
import com.ytdownloader.app.util.SafeDocuments
import com.ytdownloader.app.ytdl.YtdlInitProvider
import com.ytdownloader.app.ytdl.YtdlManager
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "com.ytdownloader.app.action.START_DOWNLOAD"
        private const val ACTION_PAUSE = "com.ytdownloader.app.action.PAUSE_DOWNLOAD"
        private const val ACTION_RESUME = "com.ytdownloader.app.action.RESUME_DOWNLOAD"
        private const val ACTION_CANCEL = "com.ytdownloader.app.action.CANCEL_DOWNLOAD"
        private const val EXTRA_ID = "download_id"
        private const val EXTRA_OPTIONS = "download_options"
        private const val CHANNEL_ID = "downloads"
        private const val REQUEST_CODE = 1001
        private const val ID_RANGE = 1000
        private const val PAUSE_REQUEST_OFFSET = 100_000
        private const val RESUME_REQUEST_OFFSET = 200_000
        private const val CANCEL_REQUEST_OFFSET = 300_000

        // Clientes alternativos de YouTube para reintentar cuando yt-dlp recibe un 403.
        private val FORBIDDEN_FALLBACK_CLIENTS = listOf("tv", "web_safari")

        // Mensajes de error que en realidad significan "se cayó la conexión".
        private val NETWORK_ERROR_HINTS = listOf(
            "connection",
            "network",
            "timed out",
            "timeout",
            "getaddrinfo",
            "name or service not known",
            "no route to host",
            "temporary failure",
            "unable to connect",
            "connection reset",
            "broken pipe",
            "eof",
            "ssl"
        )

        // Un id de notificación por descarga: dos descargas simultáneas ya no se pisan.
        private fun progressNotificationId(downloadId: Long): Int =
            2000 + (downloadId % ID_RANGE).toInt()

        private fun resultNotificationId(downloadId: Long): Int =
            3000 + (downloadId % ID_RANGE).toInt()

        fun start(context: Context, downloadId: Long, options: DownloadOptions) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, downloadId)
                putExtra(EXTRA_OPTIONS, options)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, downloadId: Long) = send(context, ACTION_PAUSE, downloadId)

        fun resume(context: Context, downloadId: Long) = send(context, ACTION_RESUME, downloadId)

        fun cancel(context: Context, downloadId: Long) = send(context, ACTION_CANCEL, downloadId)

        private fun send(context: Context, action: String, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, downloadId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeProcesses = ConcurrentHashMap<Long, String>()
    private val lastProgressAt = ConcurrentHashMap<Long, Long>()
    private val lastDbAt = ConcurrentHashMap<Long, Long>()
    private val progressValues = ConcurrentHashMap<Long, Float>()

    // Descargas activas (id -> opciones) y cola de espera. Protegidas por [lock].
    private val running = HashMap<Long, DownloadOptions>()
    private val queue = mutableListOf<Pair<Long, DownloadOptions>>()

    private val pauseRequested = ConcurrentHashMap.newKeySet<Long>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<Long>()
    private val networkPaused = ConcurrentHashMap.newKeySet<Long>()

    private val lock = Any()
    private val pendingOps = AtomicInteger(0)
    private var foregroundDownloadId = -1L

    private val connectivity: ConnectivityManager? by lazy {
        runCatching { getSystemService(ConnectivityManager::class.java) }.getOrNull()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (isOnline()) return
            // Pequeña espera: al cambiar de Wi-Fi a datos la red tarda un instante en estar lista.
            scope.launch {
                delay(3000)
                if (!isOnline()) pauseAllForNetworkLoss()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DownloadRepository.get(this)
        YtdlInitProvider.warmUp(this)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (downloadId > 0) {
            // Hay que entrar en foreground antes de 5 s cuando se usa startForegroundService.
            ensureForeground(downloadId)
        }
        when (intent?.action) {
            ACTION_START -> {
                if (downloadId > 0) handleStart(intent)
                refreshForeground()
            }
            ACTION_PAUSE -> {
                if (downloadId > 0) handlePause(downloadId)
                refreshForeground()
            }
            ACTION_RESUME -> {
                if (downloadId > 0) handleResume(downloadId) else refreshForeground()
            }
            ACTION_CANCEL -> {
                if (downloadId > 0) handleCancel(downloadId)
                refreshForeground()
            }
            else -> refreshForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------------------
    // Acciones
    // ---------------------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val downloadId = intent.getLongExtra(EXTRA_ID, -1L)
        val options = IntentCompat.getParcelableExtra(intent, EXTRA_OPTIONS, DownloadOptions::class.java)
        if (downloadId <= 0 || options == null) return
        cancelRequested.remove(downloadId)
        pauseRequested.remove(downloadId)
        networkPaused.remove(downloadId)
        enqueue(downloadId, options)
    }

    private fun handlePause(downloadId: Long) {
        networkPaused.remove(downloadId)

        val removedFromQueue = synchronized(lock) {
            val index = queue.indexOfFirst { it.first == downloadId }
            if (index >= 0) {
                queue.removeAt(index)
                true
            } else {
                false
            }
        }

        if (removedFromQueue) {
            // Todavía no había empezado: se marca como pausada directamente.
            launchBackground {
                val dao = DownloadRepository.get(this).dao()
                val updated = dao.setStatusIf(
                    downloadId,
                    DownloadStatus.PAUSED.name,
                    listOf(DownloadStatus.QUEUED.name)
                )
                if (updated > 0) notifyPaused(downloadId, false)
            }
            return
        }

        if (!isRunning(downloadId)) {
            launchBackground {
                val dao = DownloadRepository.get(this).dao()
                val updated = dao.setStatusIf(
                    downloadId,
                    DownloadStatus.PAUSED.name,
                    listOf(DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name)
                )
                if (updated > 0) notifyPaused(downloadId, false)
            }
            return
        }

        // yt-dlp no sabe pausarse: se corta el proceso y los .part se reanudan después.
        pauseRequested.add(downloadId)
        activeProcesses[downloadId]?.let { YtdlManager.cancel(it) }
    }

    private fun handleResume(downloadId: Long) {
        launchBackground {
            val dao = DownloadRepository.get(this).dao()
            val entity = dao.get(downloadId) ?: return@launchBackground
            if (entity.status != DownloadStatus.PAUSED.name &&
                entity.status != DownloadStatus.QUEUED.name
            ) {
                return@launchBackground
            }
            if (isRunning(downloadId) || synchronized(lock) { queue.any { it.first == downloadId } }) {
                return@launchBackground
            }

            pauseRequested.remove(downloadId)
            cancelRequested.remove(downloadId)
            networkPaused.remove(downloadId)
            NotificationManagerCompat.from(this).cancel(resultNotificationId(downloadId))
            dao.updateProgress(downloadId, DownloadStatus.QUEUED.name, entity.progress)
            enqueue(downloadId, optionsFrom(entity))
        }
    }

    private fun handleCancel(downloadId: Long) {
        pauseRequested.remove(downloadId)
        networkPaused.remove(downloadId)
        cancelRequested.add(downloadId)

        val removedFromQueue = synchronized(lock) {
            val index = queue.indexOfFirst { it.first == downloadId }
            if (index >= 0) {
                queue.removeAt(index)
                true
            } else {
                false
            }
        }

        val processId = activeProcesses[downloadId]
        if (processId != null) {
            // El proceso terminará con CanceledException y el propio runDownload cierra la fila.
            YtdlManager.cancel(processId)
            return
        }

        if (removedFromQueue || !isRunning(downloadId)) {
            launchBackground {
                val dao = DownloadRepository.get(this).dao()
                dao.setError(downloadId, DownloadStatus.CANCELED.name, "")
                NotificationManagerCompat.from(this).cancel(progressNotificationId(downloadId))
                notifyEnd(downloadId, failed = false, canceled = true)
                cancelRequested.remove(downloadId)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cola y concurrencia
    // ---------------------------------------------------------------------------------------

    private fun enqueue(downloadId: Long, options: DownloadOptions) {
        synchronized(lock) {
            if (running.containsKey(downloadId)) return
            if (queue.any { it.first == downloadId }) return
            queue.add(downloadId to options)
        }
        pump()
    }

    /** Arranca descargas de la cola mientras haya huecos libres. */
    private fun pump() {
        val starts = mutableListOf<Pair<Long, DownloadOptions>>()
        synchronized(lock) {
            val limit = Prefs.maxConcurrent(this)
            while (running.size < limit && queue.isNotEmpty()) {
                val next = queue.removeAt(0)
                if (running.containsKey(next.first)) continue
                running[next.first] = next.second
                activeProcesses[next.first] = "download-${next.first}"
                starts.add(next)
            }
        }
        starts.forEach { (id, options) ->
            ensureForeground(id)
            scope.launch { runDownload(id, options) }
        }
    }

    private fun isRunning(downloadId: Long): Boolean = synchronized(lock) {
        running.containsKey(downloadId)
    }

    private fun optionsFrom(entity: DownloadEntity): DownloadOptions {
        val parts = entity.kind.split("|")
        val format = if (parts.getOrNull(0) == DownloadFormat.AUDIO.name) {
            DownloadFormat.AUDIO
        } else {
            DownloadFormat.VIDEO
        }
        val scope = if (parts.getOrNull(1) == DownloadScope.PLAYLIST.name) {
            DownloadScope.PLAYLIST
        } else {
            DownloadScope.SINGLE
        }
        return DownloadOptions(
            url = entity.url,
            format = format,
            maxHeight = entity.maxHeight,
            audioQuality = entity.audioQuality,
            withSubtitles = entity.withSubtitles,
            scope = scope
        )
    }

    // ---------------------------------------------------------------------------------------
    // Descarga
    // ---------------------------------------------------------------------------------------

    private suspend fun runDownload(downloadId: Long, options: DownloadOptions) {
        val dao = DownloadRepository.get(this).dao()
        val processId = activeProcesses[downloadId] ?: "download-$downloadId"

        try {
            val entity = dao.get(downloadId) ?: return
            if (entity.status == DownloadStatus.CANCELED.name) return
            if (entity.status == DownloadStatus.PAUSED.name) return
            throwIfInterrupted(downloadId)
            dao.updateProgress(downloadId, DownloadStatus.DOWNLOADING.name, entity.progress)

            if (!YtdlInitProvider.init(this)) {
                dao.setError(
                    downloadId,
                    DownloadStatus.ERROR.name,
                    YtdlInitProvider.lastError ?: "Error de inicialización"
                )
                notifyEnd(downloadId, failed = true, canceled = false)
                return
            }

            val outDir = YtdlManager.downloadRoot(this)
            val before = outDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val startMs = System.currentTimeMillis()

            // Un yt-dlp viejo es la causa habitual de "unable to download video data: HTTP 403".
            YtdlInitProvider.ensureLatest(this)
            throwIfInterrupted(downloadId)
            runYtdl(downloadId, options, outDir, processId)

            val newFiles = FileUtils.listNew(outDir, before, startMs)
            val media = newFiles.filter { FileUtils.isMediaFile(it) }
            val thumbs = newFiles.filter { FileUtils.isThumbFile(it) }
            val subs = newFiles.filter { FileUtils.isSubFile(it) }
            if (media.isEmpty()) {
                dao.setError(downloadId, DownloadStatus.ERROR.name, "No se generó ningún archivo")
                notifyEnd(downloadId, failed = true, canceled = false)
                return
            }
            val size = media.sumOf { it.length() }

            val destinationUri = Prefs.destinationUri(this)
            var destPaths = ""
            var moved = false
            if (destinationUri.isNotBlank()) {
                val copied = moveToDestination(destinationUri, media + subs)
                if (copied.isNotEmpty()) {
                    // Las miniaturas se quedan en local (se usan en la lista y se borran con la fila).
                    FileUtils.deleteAll((media + subs).map { it.absolutePath })
                    destPaths = copied.joinToString("\n")
                    moved = true
                }
            }

            val filePath = if (moved) "" else (media + subs).joinToString("\n") { it.absolutePath }
            val thumb = thumbs.firstOrNull()
            // Solo se guarda una miniatura por fila: el resto se borra para no dejarlas huérfanas.
            FileUtils.deleteAll(thumbs.drop(1).map { it.absolutePath })
            dao.update(
                entity.copy(
                    status = DownloadStatus.DONE.name,
                    progress = 100f,
                    filePath = filePath,
                    thumbPath = thumb?.absolutePath ?: entity.thumbPath,
                    sizeBytes = size,
                    error = "",
                    destPaths = if (moved) destPaths else entity.destPaths
                )
            )
            if (moved && destPaths.isNotBlank()) {
                val uri = Uri.parse(destPaths.split("\n").first())
                notifyDone(entity, uri, contentResolver.getType(uri) ?: "application/octet-stream")
            } else {
                notifyDone(
                    entity,
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", media.first()),
                    FileUtils.mimeOf(media.first())
                )
            }
        } catch (e: YoutubeDL.CanceledException) {
            handleInterruption(downloadId, dao)
        } catch (e: InterruptedException) {
            handleInterruption(downloadId, dao)
        } catch (e: Exception) {
            handleFailure(downloadId, dao, e)
        } finally {
            finishDownload(downloadId)
        }
    }

    /**
     * Ejecuta yt-dlp con reintentos:
     *  1) con la configuración elegida,
     *  2) sin subtítulos (si el usuario los pidió),
     *  3) actualizando yt-dlp y probando otro cliente de YouTube cuando el error es un 403.
     */
    private suspend fun runYtdl(
        downloadId: Long,
        options: DownloadOptions,
        outDir: File,
        processId: String
    ) {
        try {
            execute(downloadId, options, outDir, processId)
            return
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            var last = e
            throwIfInterrupted(downloadId)

            // Sin conexión no merece la pena reintentar: se pausa de inmediato.
            if (!isOnline()) throw last

            // Si pidió subtítulos y falló, se descarga el vídeo sin ellos.
            if (options.withSubtitles) {
                try {
                    execute(downloadId, options.copy(withSubtitles = false), outDir, processId)
                    return
                } catch (c: YoutubeDL.CanceledException) {
                    throw c
                } catch (c: InterruptedException) {
                    throw c
                } catch (c: Exception) {
                    last = c
                }
            }

            if (isForbidden(last)) {
                YtdlInitProvider.forceUpdate(this)
                for (client in FORBIDDEN_FALLBACK_CLIENTS) {
                    throwIfInterrupted(downloadId)
                    try {
                        execute(downloadId, options.copy(withSubtitles = false), outDir, processId, client)
                        return
                    } catch (c: YoutubeDL.CanceledException) {
                        throw c
                    } catch (c: InterruptedException) {
                        throw c
                    } catch (c: Exception) {
                        last = c
                    }
                }
            }

            throw last
        }
    }

    private fun execute(
        downloadId: Long,
        options: DownloadOptions,
        outDir: File,
        processId: String,
        youtubeClient: String? = null
    ) {
        throwIfInterrupted(downloadId)
        val request = YtdlManager.buildRequest(options, outDir, youtubeClient)
        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
            handleProgress(downloadId, progress)
        }
    }

    /** Se lanza al pausar/cancelar antes de que el proceso de yt-dlp llegue a arrancar. */
    private fun throwIfInterrupted(downloadId: Long) {
        if (cancelRequested.contains(downloadId) || pauseRequested.contains(downloadId)) {
            throw YoutubeDL.CanceledException()
        }
    }

    private fun isForbidden(e: Exception): Boolean {
        val message = e.message ?: return false
        return message.contains("403", true) ||
            message.contains("unable to download video data", true) ||
            message.contains("HTTP Error 40", true)
    }

    private suspend fun handleInterruption(downloadId: Long, dao: DownloadDao) {
        if (pauseRequested.contains(downloadId)) {
            val dueToNetwork = networkPaused.contains(downloadId)
            dao.setError(
                downloadId,
                DownloadStatus.PAUSED.name,
                if (dueToNetwork) getString(R.string.status_no_connection) else ""
            )
            notifyPaused(downloadId, dueToNetwork)
        } else {
            dao.setError(downloadId, DownloadStatus.CANCELED.name, "")
            notifyEnd(downloadId, failed = false, canceled = true)
        }
    }

    private suspend fun handleFailure(downloadId: Long, dao: DownloadDao, e: Exception) {
        when {
            pauseRequested.contains(downloadId) -> {
                val dueToNetwork = networkPaused.contains(downloadId)
                dao.setError(
                    downloadId,
                    DownloadStatus.PAUSED.name,
                    if (dueToNetwork) getString(R.string.status_no_connection) else ""
                )
                notifyPaused(downloadId, dueToNetwork)
            }
            cancelRequested.contains(downloadId) -> {
                dao.setError(downloadId, DownloadStatus.CANCELED.name, "")
                notifyEnd(downloadId, failed = false, canceled = true)
            }
            // Sin conexión no es un error: se pausa y se puede reanudar al volver la red.
            isNetworkError(e) || !isOnline() -> {
                dao.setError(downloadId, DownloadStatus.PAUSED.name, getString(R.string.status_no_connection))
                notifyPaused(downloadId, true)
            }
            else -> {
                dao.setError(downloadId, DownloadStatus.ERROR.name, e.message ?: "Error")
                notifyEnd(downloadId, failed = true, canceled = false)
            }
        }
    }

    private fun isNetworkError(e: Exception): Boolean {
        if (e is UnknownHostException || e is SocketException || e is SocketTimeoutException) return true
        val message = (e.message ?: "").lowercase()
        // Un 403/404 no es un problema de red.
        if (message.contains("403") || message.contains("http error 4")) return false
        return NETWORK_ERROR_HINTS.any { message.contains(it) }
    }

    private fun isOnline(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun pauseAllForNetworkLoss() {
        val ids = synchronized(lock) { running.keys.toList() }
        ids.forEach { id ->
            networkPaused.add(id)
            pauseRequested.add(id)
            activeProcesses[id]?.let { YtdlManager.cancel(it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida de cada descarga
    // ---------------------------------------------------------------------------------------

    /**
     * Quita la descarga de las activas, arranca la siguiente de la cola y ajusta el foreground.
     * Solo se para el servicio cuando no queda nada en curso.
     */
    private fun finishDownload(downloadId: Long) {
        synchronized(lock) { running.remove(downloadId) }
        activeProcesses.remove(downloadId)
        lastProgressAt.remove(downloadId)
        lastDbAt.remove(downloadId)
        progressValues.remove(downloadId)
        pauseRequested.remove(downloadId)
        cancelRequested.remove(downloadId)
        networkPaused.remove(downloadId)
        NotificationManagerCompat.from(this).cancel(progressNotificationId(downloadId))
        pump()
        refreshForeground()
    }

    private fun handleProgress(downloadId: Long, progress: Float) {
        if (!isRunning(downloadId)) return
        val now = System.currentTimeMillis()
        val last = lastProgressAt[downloadId] ?: 0L
        if (now - last < 250) return
        lastProgressAt[downloadId] = now
        val safe = progress.coerceIn(0f, 100f)
        progressValues[downloadId] = safe
        // La base de datos se escribe como mucho una vez por segundo (la lista se redibuja con cada cambio).
        val lastDb = lastDbAt[downloadId] ?: 0L
        if (now - lastDb >= 1000) {
            lastDbAt[downloadId] = now
            scope.launch {
                // Solo se actualiza si sigue descargando: una pausa/cancelación no debe revertirse.
                DownloadRepository.get(this@DownloadService)
                    .dao()
                    .updateProgressIf(downloadId, safe, DownloadStatus.DOWNLOADING.name)
            }
        }
        val manager = NotificationManagerCompat.from(this)
        val isForeground = synchronized(lock) { foregroundDownloadId == downloadId }
        manager.notify(
            progressNotificationId(downloadId),
            buildProgressNotification(safe, downloadId, ongoing = isForeground)
        )
    }

    private fun ensureForeground(downloadId: Long) {
        val promote = synchronized(lock) {
            if (foregroundDownloadId != -1L) {
                false
            } else {
                foregroundDownloadId = downloadId
                true
            }
        }
        if (!promote) return
        ServiceCompat.startForeground(
            this,
            progressNotificationId(downloadId),
            buildProgressNotification(0f, downloadId, text = getString(R.string.status_queued)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun refreshForeground() {
        var promote: Long? = null
        var stop = false
        synchronized(lock) {
            val active = running.keys.minOrNull() ?: queue.firstOrNull()?.first
            if (active == null) {
                if (foregroundDownloadId != -1L) foregroundDownloadId = -1L
                stop = pendingOps.get() == 0
            } else if (foregroundDownloadId == -1L || !isActiveLocked(foregroundDownloadId)) {
                foregroundDownloadId = active
                promote = active
            }
        }
        promote?.let { id ->
            val progress = progressValues[id] ?: 0f
            ServiceCompat.startForeground(
                this,
                progressNotificationId(id),
                buildProgressNotification(
                    progress,
                    id,
                    text = if (progress > 0f) progressText(progress) else getString(R.string.status_queued)
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
        if (stop) {
            // Pequeña espera antes de apagar el servicio: evita perder un start que llega a la vez.
            scope.launch {
                delay(300)
                val idle = synchronized(lock) {
                    running.isEmpty() && queue.isEmpty() && pendingOps.get() == 0
                }
                if (idle) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun isActiveLocked(downloadId: Long): Boolean =
        running.containsKey(downloadId) || queue.any { it.first == downloadId }

    /**
     * Ejecuta trabajo de base de datos sin que el servicio se pare a mitad
     * (stopSelf cancelaría el scope y la fila quedaría sin actualizar).
     */
    private fun launchBackground(block: suspend () -> Unit) {
        pendingOps.incrementAndGet()
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
            } finally {
                if (pendingOps.decrementAndGet() == 0) refreshForeground()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notificaciones
    // ---------------------------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        progress: Float,
        downloadId: Long,
        ongoing: Boolean = true,
        text: String = progressText(progress)
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.toInt(), false)
            .setContentIntent(mainPendingIntent())
            .addAction(0, getString(R.string.action_pause), pausePendingIntent(downloadId))
            .addAction(0, getString(R.string.notification_cancel), cancelPendingIntent(downloadId))
            .build()
    }

    private fun notifyPaused(downloadId: Long, dueToNetwork: Boolean) {
        NotificationManagerCompat.from(this).cancel(progressNotificationId(downloadId))
        val text = if (dueToNetwork) {
            getString(R.string.notification_paused_network)
        } else {
            getString(R.string.notification_paused)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(mainPendingIntent())
            .addAction(0, getString(R.string.action_resume), resumePendingIntent(downloadId))
            .addAction(0, getString(R.string.action_cancel), cancelPendingIntent(downloadId))
            .build()
        NotificationManagerCompat.from(this).notify(resultNotificationId(downloadId), notification)
    }

    private fun moveToDestination(treeUriString: String, files: List<File>): List<String> {
        if (files.isEmpty()) return emptyList()
        val treeUri = try {
            Uri.parse(treeUriString)
        } catch (e: Exception) {
            return emptyList()
        }
        return SafeDocuments.moveInto(contentResolver, treeUri, files) { FileUtils.mimeOf(it) }
    }

    private fun notifyDone(entity: DownloadEntity, uri: Uri, mime: String) {
        val openIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val contentIntent = PendingIntent.getActivity(
            this,
            entity.id.toInt() + RESUME_REQUEST_OFFSET,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_done))
            .setContentText(entity.title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(this).notify(resultNotificationId(entity.id), notification)
    }

    private fun notifyEnd(downloadId: Long, failed: Boolean, canceled: Boolean) {
        val text = when {
            canceled -> getString(R.string.notification_canceled)
            failed -> getString(R.string.notification_failed)
            else -> getString(R.string.notification_done)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (failed || canceled) android.R.drawable.stat_notify_error
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify(resultNotificationId(downloadId), notification)
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pausePendingIntent(downloadId: Long): PendingIntent =
        servicePendingIntent(ACTION_PAUSE, downloadId, PAUSE_REQUEST_OFFSET)

    private fun resumePendingIntent(downloadId: Long): PendingIntent =
        servicePendingIntent(ACTION_RESUME, downloadId, RESUME_REQUEST_OFFSET)

    private fun cancelPendingIntent(downloadId: Long): PendingIntent =
        servicePendingIntent(ACTION_CANCEL, downloadId, CANCEL_REQUEST_OFFSET)

    private fun servicePendingIntent(action: String, downloadId: Long, offset: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, downloadId)
        }
        return PendingIntent.getService(
            this,
            downloadId.toInt() + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressText(progress: Float): String = "${progress.toInt()}%"
}
