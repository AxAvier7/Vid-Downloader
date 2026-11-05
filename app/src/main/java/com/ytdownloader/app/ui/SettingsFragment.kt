package com.ytdownloader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ytdownloader.app.R
import com.ytdownloader.app.databinding.FragmentSettingsBinding
import com.ytdownloader.app.model.ResolutionOptions
import com.ytdownloader.app.util.Prefs
import com.ytdownloader.app.util.SafeDocuments

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                Prefs.setDestinationUri(requireContext(), uri.toString())
                refreshValues()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rowFormat.setOnClickListener { chooseFormat() }
        binding.rowResolution.setOnClickListener { chooseResolution() }
        binding.rowAudioQuality.setOnClickListener { chooseAudioQuality() }
        binding.rowConcurrent.setOnClickListener { chooseConcurrency() }
        binding.rowFolder.setOnClickListener { chooseFolder() }

        binding.switchSubtitles.isChecked = Prefs.subtitles(requireContext())
        binding.switchSubtitles.setOnCheckedChangeListener { _, checked ->
            Prefs.setSubtitles(requireContext(), checked)
        }

        binding.tvAboutValue.text = getString(R.string.settings_about_value)

        refreshValues()
    }

    private fun refreshValues() {
        val context = requireContext()
        binding.tvFormatValue.text =
            if (Prefs.format(context) == "audio") getString(R.string.format_audio)
            else getString(R.string.format_video)

        val resolution = ResolutionOptions.RESOLUTIONS.firstOrNull { it.value == Prefs.resolution(context) }
        binding.tvResolutionValue.text = resolution?.let { getString(it.labelRes) }
            ?: getString(R.string.res_auto)

        val quality = ResolutionOptions.AUDIO_QUALITIES.firstOrNull { it.value == Prefs.audioQuality(context) }
        binding.tvAudioQualityValue.text = quality?.let { getString(it.labelRes) }
            ?: getString(R.string.audio_best)

        binding.tvConcurrentValue.text =
            getString(R.string.settings_concurrent_value, Prefs.maxConcurrent(context))

        binding.tvFolderValue.text = folderLabel(context)
    }

    private fun folderLabel(context: android.content.Context): String {
        val uriText = Prefs.destinationUri(context)
        if (uriText.isBlank()) return getString(R.string.settings_folder_default)
        return try {
            SafeDocuments.name(context.contentResolver, Uri.parse(uriText)) ?: uriText
        } catch (e: Exception) {
            uriText
        }
    }

    private fun chooseFolder() {
        val context = requireContext()
        if (Prefs.destinationUri(context).isBlank()) {
            folderPickerLauncher.launch(null)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_folder)
            .setItems(
                arrayOf(
                    getString(R.string.folder_change),
                    getString(R.string.folder_reset)
                )
            ) { _, which ->
                when (which) {
                    0 -> folderPickerLauncher.launch(null)
                    1 -> {
                        Prefs.setDestinationUri(context, "")
                        refreshValues()
                    }
                }
            }
            .show()
    }

    private fun chooseFormat() {
        val context = requireContext()
        val options = arrayOf(getString(R.string.format_video), getString(R.string.format_audio))
        val current = if (Prefs.format(context) == "audio") 1 else 0
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_format)
            .setSingleChoiceItems(options, current) { dialog, which ->
                Prefs.setFormat(context, if (which == 1) "audio" else "video")
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseResolution() {
        val context = requireContext()
        val options = ResolutionOptions.RESOLUTIONS
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val current = options.indexOfFirst { it.value == Prefs.resolution(context) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_resolution)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setResolution(context, options[which].value)
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseAudioQuality() {
        val context = requireContext()
        val options = ResolutionOptions.AUDIO_QUALITIES
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val current = options.indexOfFirst { it.value == Prefs.audioQuality(context) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_audio_quality)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setAudioQuality(context, options[which].value)
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseConcurrency() {
        val context = requireContext()
        val values = (1..Prefs.MAX_CONCURRENT_LIMIT).toList()
        val labels = values
            .map { getString(R.string.settings_concurrent_value, it) }
            .toTypedArray()
        val current = values.indexOf(Prefs.maxConcurrent(context)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_concurrent_dialog)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setMaxConcurrent(context, values[which])
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}