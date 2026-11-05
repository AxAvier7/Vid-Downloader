package com.ytdownloader.app.ui

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.ytdownloader.app.R
import com.ytdownloader.app.data.DownloadEntity
import com.ytdownloader.app.databinding.ItemDownloadBinding
import com.ytdownloader.app.model.DownloadStatus
import com.ytdownloader.app.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsAdapter(private val callbacks: Callbacks) :
    RecyclerView.Adapter<DownloadsAdapter.VH>() {

    interface Callbacks {
        fun onOpen(entity: DownloadEntity)
        fun onShare(entity: DownloadEntity)
        fun onDelete(entity: DownloadEntity)
        fun onPause(entity: DownloadEntity)
        fun onResume(entity: DownloadEntity)
        fun onCancel(entity: DownloadEntity)
    }

    private val items = mutableListOf<DownloadEntity>()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun submit(list: List<DownloadEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context get() = binding.root.context

        fun bind(entity: DownloadEntity) {
            val res = context.resources
            val status = runCatching { DownloadStatus.valueOf(entity.status) }.getOrDefault(DownloadStatus.ERROR)
            val kindLabel = kindLabel(entity.kind, res)
            val date = dateFormat.format(Date(entity.createdAt))

            binding.tvTitle.text = entity.title.ifBlank { entity.url }

            binding.tvSubtitle.text = when (status) {
                DownloadStatus.DOWNLOADING -> "$kindLabel · ${entity.progress.toInt()}%"
                DownloadStatus.QUEUED -> "$kindLabel · ${res.getString(R.string.status_queued)}"
                DownloadStatus.PAUSED -> "$kindLabel · ${res.getString(R.string.status_paused)}" +
                    if (entity.error.isNotBlank()) " · ${entity.error}" else ""
                DownloadStatus.DONE -> "$kindLabel · ${FileUtils.humanSize(entity.sizeBytes)} · $date"
                DownloadStatus.CANCELED -> "$kindLabel · ${res.getString(R.string.status_canceled)}"
                DownloadStatus.ERROR -> "$kindLabel · ${res.getString(R.string.status_error)}" +
                    if (entity.error.isNotBlank()) " · ${entity.error}" else ""
            }

            val active = status == DownloadStatus.QUEUED ||
                status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.PAUSED
            binding.progressRow.isVisible = active
            binding.controlRow.isVisible = active
            binding.actionsRow.isVisible = !active
            binding.btnPause.isVisible = status != DownloadStatus.PAUSED
            binding.btnResume.isVisible = status == DownloadStatus.PAUSED
            if (active) {
                binding.progressBar.progress = entity.progress.toInt().coerceIn(0, 100)
                binding.tvPercent.text = "${entity.progress.toInt()}%"
            }

            loadThumbnail(entity)

            binding.root.setOnClickListener {
                if (status == DownloadStatus.DONE) callbacks.onOpen(entity)
            }
            binding.btnOpen.setOnClickListener { callbacks.onOpen(entity) }
            binding.btnShare.setOnClickListener { callbacks.onShare(entity) }
            binding.btnDelete.setOnClickListener { callbacks.onDelete(entity) }
            binding.btnPause.setOnClickListener { callbacks.onPause(entity) }
            binding.btnResume.setOnClickListener { callbacks.onResume(entity) }
            binding.btnCancel.setOnClickListener { callbacks.onCancel(entity) }
        }

        private fun loadThumbnail(entity: DownloadEntity) {
            val thumbPath = entity.thumbPath.takeIf { it.isNotBlank() && File(it).exists() }
            val thumbUrl = entity.thumbnailUrl.takeIf { it.isNotBlank() }
            when {
                thumbPath != null -> binding.ivThumb.load(File(thumbPath)) { crossfade(true) }
                thumbUrl != null -> binding.ivThumb.load(thumbUrl) { crossfade(true) }
                else -> binding.ivThumb.setImageResource(R.drawable.ic_download)
            }
        }
    }

    private fun kindLabel(kind: String, res: Resources): String {
        val parts = kind.split("|")
        val base = if (parts.getOrNull(0) == "AUDIO") {
            res.getString(R.string.format_audio)
        } else {
            res.getString(R.string.format_video)
        }
        return if (parts.getOrNull(1) == "PLAYLIST") "$base · ${res.getString(R.string.playlist)}" else base
    }
}