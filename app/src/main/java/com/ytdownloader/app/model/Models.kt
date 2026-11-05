package com.ytdownloader.app.model

import android.os.Parcelable
import androidx.annotation.StringRes
import com.ytdownloader.app.R
import kotlinx.parcelize.Parcelize

enum class DownloadFormat { VIDEO, AUDIO }

enum class DownloadScope { SINGLE, PLAYLIST }

@Parcelize
data class DownloadOptions(
    val url: String,
    val format: DownloadFormat,
    val maxHeight: Int?,
    val audioQuality: String,
    val withSubtitles: Boolean,
    val scope: DownloadScope
) : Parcelable

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR, CANCELED }

data class QualityOption(
    @StringRes val labelRes: Int,
    val value: String,
    val maxHeight: Int?
)

object ResolutionOptions {
    val RESOLUTIONS = listOf(
        QualityOption(R.string.res_auto, "auto", null),
        QualityOption(R.string.res_2160, "2160", 2160),
        QualityOption(R.string.res_1440, "1440", 1440),
        QualityOption(R.string.res_1080, "1080", 1080),
        QualityOption(R.string.res_720, "720", 720),
        QualityOption(R.string.res_480, "480", 480),
        QualityOption(R.string.res_360, "360", 360),
        QualityOption(R.string.res_240, "240", 240),
        QualityOption(R.string.res_144, "144", 144)
    )

    val AUDIO_QUALITIES = listOf(
        QualityOption(R.string.audio_best, "0", null),
        QualityOption(R.string.audio_320, "320K", null),
        QualityOption(R.string.audio_192, "192K", null),
        QualityOption(R.string.audio_128, "128K", null)
    )
}

data class MediaInfo(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val playlistCount: Int?,
    /** Alturas (en píxeles) que realmente ofrece este vídeo. Vacío = desconocido. */
    val availableHeights: List<Int> = emptyList()
)