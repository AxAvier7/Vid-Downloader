package com.ytdownloader.app.ytdl

import android.content.Context
import android.os.Environment
import com.ytdownloader.app.model.DownloadFormat
import com.ytdownloader.app.model.DownloadOptions
import com.ytdownloader.app.model.DownloadScope
import com.ytdownloader.app.model.MediaInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object YtdlManager {

    fun downloadRoot(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "YT Downloader").apply { mkdirs() }
    }

    fun fetchInfo(url: String, scope: DownloadScope, processId: String? = null): MediaInfo? {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")
        if (scope == DownloadScope.PLAYLIST) {
            request.addOption("--yes-playlist")
            request.addOption("--flat-playlist")
        } else {
            request.addOption("--no-playlist")
        }

        val response = try {
            YoutubeDL.getInstance().execute(request, processId, null)
        } catch (e: Exception) {
            return null
        }

        val root = try {
            val out = response.out
            val start = out.indexOf('{')
            val end = out.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            JSONObject(out.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }

        val title = root.optString("title").ifBlank { root.optString("fulltitle") }
        var count: Int? = null
        if (root.has("playlist_count")) count = root.optInt("playlist_count")
        root.optJSONArray("entries")?.let { count = count ?: it.length() }

        // Alturas reales del vídeo: no todos los vídeos ofrecen 4K, 1080p, etc.
        val heights = sortedSetOf<Int>()
        collectHeights(root.optJSONArray("formats"), heights)
        collectHeights(root.optJSONArray("requested_formats"), heights)

        return MediaInfo(
            title = title.ifBlank { url },
            uploader = root.optString("uploader"),
            durationSec = root.optLong("duration"),
            thumbnailUrl = root.optString("thumbnail").takeIf { it.isNotBlank() },
            playlistCount = count,
            availableHeights = heights.toList()
        )
    }

    /** Añade las alturas de los formatos que realmente incluyen vídeo. */
    private fun collectHeights(formats: JSONArray?, into: MutableSet<Int>) {
        if (formats == null) return
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            if (format.optString("vcodec", "none") == "none") continue
            val height = format.optInt("height", 0)
            if (height > 0) into.add(height)
        }
    }

    /** Corta por la fuerza un proceso de yt-dlp (si sigue vivo). */
    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    fun buildRequest(options: DownloadOptions, outDir: File, youtubeClient: String? = null): YoutubeDLRequest {
        val request = YoutubeDLRequest(options.url)
        val template = File(outDir, "%(title)s [%(id)s].%(ext)s").absolutePath

        request.addOption("-o", template)
        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--extractor-retries", "3")
        request.addOption("--socket-timeout", "30")
        if (youtubeClient != null) {
            request.addOption("--extractor-args", "youtube:player_client=$youtubeClient")
        }

        if (options.scope == DownloadScope.PLAYLIST) {
            request.addOption("--yes-playlist")
        } else {
            request.addOption("--no-playlist")
        }

        when (options.format) {
            DownloadFormat.VIDEO -> {
                val selector = if (options.maxHeight == null) {
                    "bv*+ba/b"
                } else {
                    "bv*[height<=${options.maxHeight}]+ba/b[height<=${options.maxHeight}]/b"
                }
                request.addOption("-f", selector)
                request.addOption("--merge-output-format", "mp4")
            }
            DownloadFormat.AUDIO -> {
                request.addOption("-f", "ba/b")
                request.addOption("--extract-audio")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", options.audioQuality)
            }
        }

        request.addOption("--write-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")

        if (options.withSubtitles) {
            request.addOption("--write-subs")
            request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", "all,-live_chat")
            request.addOption("--sub-format", "srt/best")
            request.addOption("--convert-subs", "srt")
        }
        return request
    }
}