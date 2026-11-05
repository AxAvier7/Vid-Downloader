package com.ytdownloader.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.ytdownloader.app.R
import com.ytdownloader.app.data.DownloadEntity
import com.ytdownloader.app.data.DownloadRepository
import com.ytdownloader.app.databinding.ActivityOptionsBinding
import com.ytdownloader.app.download.DownloadService
import com.ytdownloader.app.model.DownloadFormat
import com.ytdownloader.app.model.DownloadOptions
import com.ytdownloader.app.model.DownloadScope
import com.ytdownloader.app.model.MediaInfo
import com.ytdownloader.app.model.QualityOption
import com.ytdownloader.app.model.ResolutionOptions
import com.ytdownloader.app.util.Prefs
import com.ytdownloader.app.util.UrlParser
import com.ytdownloader.app.ytdl.YtdlInitProvider
import com.ytdownloader.app.ytdl.YtdlManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OptionsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var binding: ActivityOptionsBinding
    private var parsed: UrlParser.Parsed? = null
    private var fetchJob: Job? = null
    private var oEmbedJob: Job? = null
    private var infoProcessId: String? = null
    private var infoLoaded = false
    private var mediaTitle = ""
    private var mediaUploader = ""
    private var mediaThumbUrl: String? = null
    private var mediaPlaylistCount: Int? = null
    private var currentResolutionChoices: List<QualityOption> = ResolutionOptions.RESOLUTIONS

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        parsed = UrlParser.parse(intent.getStringExtra(EXTRA_URL))
        if (parsed == null) {
            Toast.makeText(this, R.string.parse_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupFormat()
        populateQualities()
        setupSwitches()
        preloadQuickInfo()

        binding.btnDownload.setOnClickListener { onDownloadClicked() }
        binding.progressLoading.isVisible = true
        fetchInfo()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        fetchJob?.cancel()
        oEmbedJob?.cancel()
        infoProcessId?.let { YtdlManager.cancel(it) }
        infoProcessId = null
        super.onDestroy()
    }

    private fun setupFormat() {
        val initialVideo = Prefs.format(this) != "audio"
        binding.btnVideo.isChecked = initialVideo
        binding.btnAudio.isChecked = !initialVideo
        binding.fmtGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) applyFormat(checkedId == R.id.btn_video)
        }
        applyFormat(initialVideo)
    }

    private fun applyFormat(isVideo: Boolean) {
        binding.tvQualityLabel.text = getString(if (isVideo) R.string.resolution else R.string.audio_quality)
        binding.rgResolution.isVisible = isVideo
        binding.rgQuality.isVisible = !isVideo
        ensureChecked(binding.rgResolution)
        ensureChecked(binding.rgQuality)
    }

    private fun ensureChecked(group: RadioGroup) {
        if (group.checkedRadioButtonId == -1) {
            group.getChildAt(0)?.let { group.check(it.id) }
        }
    }

    private fun populateQualities(heights: List<Int>? = null) {
        populateResolutions(heights)

        if (binding.rgQuality.childCount == 0) {
            val defaultQuality = Prefs.audioQuality(this)
            ResolutionOptions.AUDIO_QUALITIES.forEach { opt ->
                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = getString(opt.labelRes)
                    tag = opt.value
                }
                binding.rgQuality.addView(rb)
                if (opt.value == defaultQuality) binding.rgQuality.check(rb.id)
            }
            ensureChecked(binding.rgQuality)
        }
    }

    /**
     * Solo se muestran las resoluciones que el vídeo ofrece de verdad.
     * Si aún no se conoce la info (o es una playlist), se muestran todas.
     */
    private fun populateResolutions(heights: List<Int>?) {
        val desired = selectedValue(binding.rgResolution) ?: Prefs.resolution(this)
        binding.rgResolution.removeAllViews()

        val choices = resolutionChoices(heights)
        currentResolutionChoices = choices
        choices.forEach { opt ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (opt.labelRes != 0) {
                    getString(opt.labelRes)
                } else {
                    getString(R.string.res_value, opt.maxHeight ?: 0)
                }
                tag = opt.value
            }
            binding.rgResolution.addView(rb)
        }
        checkResolution(choices, desired)
    }

    private fun resolutionChoices(heights: List<Int>?): List<QualityOption> {
        val auto = QualityOption(R.string.res_auto, "auto", null)
        if (heights.isNullOrEmpty()) return ResolutionOptions.RESOLUTIONS

        val known = ResolutionOptions.RESOLUTIONS
            .mapNotNull { opt -> opt.maxHeight?.let { it to opt } }
            .toMap()
        val result = mutableListOf(auto)
        heights.distinct().sortedDescending().forEach { height ->
            result.add(known[height] ?: QualityOption(0, height.toString(), height))
        }
        return result
    }

    /** Mantiene la elección si sigue disponible; si no, baja a la más cercana por debajo. */
    private fun checkResolution(choices: List<QualityOption>, desiredValue: String) {
        val exact = choices.firstOrNull { it.value == desiredValue }
        val target = exact ?: desiredValue.toIntOrNull()?.let { wanted ->
            choices.filter { it.maxHeight != null && it.maxHeight <= wanted }
                .maxByOrNull { it.maxHeight ?: 0 }
        } ?: choices.first()

        for (i in 0 until binding.rgResolution.childCount) {
            val child = binding.rgResolution.getChildAt(i)
            if (child.tag == target.value) {
                binding.rgResolution.check(child.id)
                return
            }
        }
        binding.rgResolution.getChildAt(0)?.let { binding.rgResolution.check(it.id) }
    }

    private fun selectedValue(group: RadioGroup): String? {
        val checkedId = group.checkedRadioButtonId
        if (checkedId == -1) return null
        return group.findViewById<View>(checkedId)?.tag as? String
    }

    private fun setupSwitches() {
        binding.switchSubtitles.isChecked = Prefs.subtitles(this)
        // Recuerda la última elección del usuario para la próxima descarga.
        binding.switchSubtitles.setOnCheckedChangeListener { _, checked ->
            Prefs.setSubtitles(this@OptionsActivity, checked)
        }
        val hasPlaylist = parsed?.playlistId != null
        binding.switchPlaylist.isEnabled = hasPlaylist
        binding.tvPlaylistSummary.alpha = if (hasPlaylist) 1f else 0.4f
        binding.switchPlaylist.isChecked = false
        binding.switchPlaylist.setOnCheckedChangeListener { _, checked ->
            updatePlaylistSummary()
            if (!checked) preloadQuickInfo()
            fetchInfo()
        }
    }

    private fun updatePlaylistSummary() {
        if (binding.switchPlaylist.isChecked) {
            binding.tvPlaylistSummary.text =
                getString(R.string.download_playlist_summary, mediaPlaylistCount?.toString() ?: "…")
        } else {
            binding.tvPlaylistSummary.setText(R.string.download_playlist)
        }
    }

    private fun fetchInfo() {
        fetchJob?.cancel()
        infoProcessId?.let { YtdlManager.cancel(it) }
        infoProcessId = null
        binding.progressLoading.isVisible = true
        fetchJob = lifecycleScope.launch {
            val targetUrl = buildTargetUrl()
            val scope = if (binding.switchPlaylist.isChecked) DownloadScope.PLAYLIST else DownloadScope.SINGLE
            val processId = "info-${System.currentTimeMillis()}"
            infoProcessId = processId
            val info = if (targetUrl == null) null else withContext(Dispatchers.IO) {
                if (YtdlInitProvider.init(applicationContext)) {
                    YtdlManager.fetchInfo(targetUrl, scope, processId)
                } else {
                    null
                }
            }
            if (infoProcessId == processId) infoProcessId = null
            binding.progressLoading.isVisible = false
            if (info != null) {
                bindInfo(info)
                updatePlaylistSummary()
            } else {
                if (mediaTitle.isBlank()) {
                    binding.tvTitle.text = buildTargetUrl() ?: parsed?.url ?: getString(R.string.info_error)
                    binding.tvUploader.text = getString(R.string.info_error)
                }
            }
        }
    }

    /**
     * Muestra miniatura y título en cuanto se conoce el id del vídeo, sin esperar a yt-dlp:
     * la miniatura sale de i.ytimg.com y el título del endpoint oEmbed de YouTube.
     */
    private fun preloadQuickInfo() {
        val p = parsed ?: return
        if (binding.switchPlaylist.isChecked) return
        val videoId = p.videoId ?: return

        val thumb = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        mediaThumbUrl = thumb
        binding.ivThumb.load(thumb) { crossfade(true) }

        if (mediaTitle.isNotBlank()) return
        oEmbedJob?.cancel()
        oEmbedJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { fetchOEmbed(videoId) } ?: return@launch
            // Si yt-dlp ya respondió, su información manda.
            if (infoLoaded || binding.switchPlaylist.isChecked) return@launch
            mediaTitle = data.first
            mediaUploader = data.second
            binding.tvTitle.text = data.first
            if (data.second.isNotBlank()) binding.tvUploader.text = data.second
        }
    }

    private fun fetchOEmbed(videoId: String): Pair<String, String>? = runCatching {
        val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        val connection = URL("https://www.youtube.com/oembed?format=json&url=$watchUrl")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val title = json.optString("title")
            if (title.isBlank()) null else title to json.optString("author_name")
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun buildTargetUrl(): String? {
        val p = parsed ?: return null
        return if (binding.switchPlaylist.isChecked) {
            when {
                p.videoId != null && p.playlistId != null ->
                    "https://www.youtube.com/watch?v=${p.videoId}&list=${p.playlistId}"
                p.playlistId != null -> "https://www.youtube.com/playlist?list=${p.playlistId}"
                else -> p.url
            }
        } else {
            if (p.videoId != null) "https://www.youtube.com/watch?v=${p.videoId}" else p.url
        }
    }

    private fun bindInfo(info: MediaInfo) {
        infoLoaded = true
        mediaTitle = info.title
        mediaUploader = info.uploader
        mediaPlaylistCount = info.playlistCount
        mediaThumbUrl = info.thumbnailUrl ?: mediaThumbUrl
        binding.tvTitle.text = info.title
        binding.tvUploader.text = info.uploader.ifBlank { "" }
        binding.tvDuration.text = if (info.durationSec > 0) formatDuration(info.durationSec) else ""
        if (mediaThumbUrl != null) {
            binding.ivThumb.load(mediaThumbUrl) { crossfade(true) }
        } else {
            binding.ivThumb.setImageResource(R.drawable.ic_download)
        }
        populateResolutions(info.availableHeights)
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    private fun selectedOption(group: RadioGroup, options: List<QualityOption>): QualityOption {
        val checkedId = group.checkedRadioButtonId
        if (checkedId != -1) {
            val value = group.findViewById<View>(checkedId)?.tag as? String
            options.firstOrNull { it.value == value }?.let { return it }
        }
        return options.first()
    }

    private fun onDownloadClicked() {
        val targetUrl = buildTargetUrl()
        if (targetUrl == null) {
            Toast.makeText(this, R.string.parse_error, Toast.LENGTH_SHORT).show()
            return
        }

        val isVideo = binding.btnVideo.isChecked
        val format = if (isVideo) DownloadFormat.VIDEO else DownloadFormat.AUDIO
        val maxHeight = if (isVideo) {
            selectedOption(binding.rgResolution, currentResolutionChoices).maxHeight
        } else {
            null
        }
        val audioQuality = if (isVideo) {
            ""
        } else {
            selectedOption(binding.rgQuality, ResolutionOptions.AUDIO_QUALITIES).value
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val options = DownloadOptions(
            url = targetUrl,
            format = format,
            maxHeight = maxHeight,
            audioQuality = audioQuality,
            withSubtitles = binding.switchSubtitles.isChecked,
            scope = if (binding.switchPlaylist.isChecked) DownloadScope.PLAYLIST else DownloadScope.SINGLE
        )
        Prefs.setSubtitles(this, options.withSubtitles)

        val entity = DownloadEntity(
            url = targetUrl,
            kind = "${options.format.name}|${options.scope.name}",
            title = mediaTitle.ifBlank { parsed?.url ?: targetUrl },
            uploader = mediaUploader,
            thumbnailUrl = mediaThumbUrl ?: "",
            maxHeight = options.maxHeight,
            audioQuality = options.audioQuality,
            withSubtitles = options.withSubtitles
        )

        lifecycleScope.launch {
            val id = DownloadRepository.get(this@OptionsActivity).dao().insert(entity)
            DownloadService.start(this@OptionsActivity, id, options)
        }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        finish()
    }
}