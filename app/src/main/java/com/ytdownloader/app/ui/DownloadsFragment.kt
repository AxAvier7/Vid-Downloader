package com.ytdownloader.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.ytdownloader.app.util.SafeDocuments
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ytdownloader.app.R
import com.ytdownloader.app.data.DownloadEntity
import com.ytdownloader.app.data.DownloadRepository
import com.ytdownloader.app.databinding.FragmentDownloadsBinding
import com.ytdownloader.app.download.DownloadService
import com.ytdownloader.app.model.DownloadStatus
import com.ytdownloader.app.util.FileUtils
import com.ytdownloader.app.util.UrlParser
import com.ytdownloader.app.util.deleteDestination
import com.ytdownloader.app.util.destUris
import com.ytdownloader.app.util.files
import com.ytdownloader.app.util.mediaFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DownloadsAdapter
    private var collectJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        adapter = DownloadsAdapter(object : DownloadsAdapter.Callbacks {
            override fun onOpen(entity: DownloadEntity) = openFile(entity)
            override fun onShare(entity: DownloadEntity) = shareFile(entity)
            override fun onDelete(entity: DownloadEntity) = confirmDelete(entity)
            override fun onPause(entity: DownloadEntity) =
                DownloadService.pause(requireContext(), entity.id)
            override fun onResume(entity: DownloadEntity) =
                DownloadService.resume(requireContext(), entity.id)
            override fun onCancel(entity: DownloadEntity) =
                DownloadService.cancel(requireContext(), entity.id)
        })
        binding.rvDownloads.adapter = adapter

        binding.btnAnalyze.setOnClickListener { analyzeUrl() }

        collectJob = lifecycleScope.launch {
            DownloadRepository.observeDownloads(requireContext()).collect { list ->
                adapter.submit(list)
                binding.tvEmpty.isVisible = list.isEmpty()
            }
        }
    }

    private fun analyzeUrl() {
        val text = binding.etUrl.text?.toString()?.trim().orEmpty()
        val parsed = UrlParser.parse(text)
        if (parsed == null) {
            Toast.makeText(requireContext(), R.string.parse_error, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), OptionsActivity::class.java)
            .putExtra(OptionsActivity.EXTRA_URL, parsed.url)
        startActivity(intent)
        binding.etUrl.text?.clear()
    }

    private fun openFile(entity: DownloadEntity) {
        val destUris = entity.destUris()
        if (destUris.isNotEmpty()) {
            val context = requireContext()
            if (destUris.size == 1) {
                openUri(destUris[0])
            } else {
                val names = destUris.map { uri ->
                    SafeDocuments.name(context.contentResolver, uri) ?: uri.lastPathSegment ?: "?"
                }.toTypedArray()
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.choose_file)
                    .setItems(names) { _, which -> openUri(destUris[which]) }
                    .show()
            }
            return
        }

        val media = entity.mediaFiles()
        if (media.isEmpty()) {
            Toast.makeText(requireContext(), R.string.info_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (media.size == 1) {
            openWithViewer(media[0])
        } else {
            val context = requireContext()
            val names = media.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.choose_file)
                .setItems(names) { _, which -> openWithViewer(media[which]) }
                .show()
        }
    }

    private fun openUri(uri: Uri) {
        val context = requireContext()
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.info_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWithViewer(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileUtils.mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.info_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(entity: DownloadEntity) {
        val destUris = entity.destUris()
        if (destUris.isNotEmpty()) {
            shareUri(destUris[0], requireContext().contentResolver.getType(destUris[0]) ?: "application/octet-stream")
            return
        }
        val file = entity.mediaFiles().firstOrNull() ?: return
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        shareUri(uri, FileUtils.mimeOf(file))
    }

    private fun shareUri(uri: Uri, mime: String) {
        val context = requireContext()
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun confirmDelete(entity: DownloadEntity) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appContext = context.applicationContext
                val status = runCatching { DownloadStatus.valueOf(entity.status) }
                    .getOrDefault(DownloadStatus.ERROR)
                if (status == DownloadStatus.QUEUED ||
                    status == DownloadStatus.DOWNLOADING ||
                    status == DownloadStatus.PAUSED
                ) {
                    DownloadService.cancel(appContext, entity.id)
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val paths = entity.files().map { it.absolutePath }.toMutableList()
                        entity.thumbPath.takeIf { it.isNotBlank() }?.let { paths.add(it) }
                        FileUtils.deleteAll(paths)
                        entity.deleteDestination(appContext)
                        DownloadRepository.get(appContext).dao().delete(entity.id)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        collectJob?.cancel()
        _binding = null
        super.onDestroyView()
    }
}