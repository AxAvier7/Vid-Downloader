package com.ytdownloader.app.util

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "ytdownloader_settings"
    private const val KEY_FORMAT = "default_format"
    private const val KEY_RESOLUTION = "default_resolution"
    private const val KEY_AUDIO_QUALITY = "default_audio_quality"
    private const val KEY_SUBTITLES = "default_subtitles"
    private const val KEY_DESTINATION_URI = "destination_uri"
    private const val KEY_YTDL_NEXT_UPDATE = "ytdl_next_update"
    private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
    const val MAX_CONCURRENT_LIMIT = 4

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun format(context: Context): String =
        prefs(context).getString(KEY_FORMAT, "video") ?: "video"

    fun setFormat(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FORMAT, value).apply()
    }

    fun resolution(context: Context): String =
        prefs(context).getString(KEY_RESOLUTION, "1080") ?: "1080"

    fun setResolution(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RESOLUTION, value).apply()
    }

    fun audioQuality(context: Context): String =
        prefs(context).getString(KEY_AUDIO_QUALITY, "0") ?: "0"

    fun setAudioQuality(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AUDIO_QUALITY, value).apply()
    }

    fun subtitles(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUBTITLES, true)

    fun setSubtitles(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBTITLES, value).apply()
    }

    fun destinationUri(context: Context): String =
        prefs(context).getString(KEY_DESTINATION_URI, "") ?: ""

    fun setDestinationUri(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DESTINATION_URI, value).apply()
    }

    /** Momento (epoch ms) en el que toca volver a comprobar si hay una versión nueva de yt-dlp. */
    fun ytdlNextUpdateAt(context: Context): Long =
        prefs(context).getLong(KEY_YTDL_NEXT_UPDATE, 0L)

    fun setYtdlNextUpdateAt(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_YTDL_NEXT_UPDATE, value).apply()
    }

    /** Cuántas descargas se ejecutan a la vez (1..MAX_CONCURRENT_LIMIT). */
    fun maxConcurrent(context: Context): Int =
        prefs(context).getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, MAX_CONCURRENT_LIMIT)

    fun setMaxConcurrent(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, MAX_CONCURRENT_LIMIT)).apply()
    }
}