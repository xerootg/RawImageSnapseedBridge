package com.raw2dng

import android.app.Dialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog for configuring regeneration settings.
 * Pre-populates with app defaults but does NOT persist changes.
 *
 * The confirmed settings are delivered through the Fragment Result API under
 * [REQUEST_KEY] (see [parseResult]) so that they survive a configuration
 * change while the dialog is open; the RAW URIs to regenerate travel with the
 * dialog's arguments for the same reason.
 */
class RegenerateDialog : DialogFragment() {

    /** Settings confirmed by the user together with the RAW files they apply to. */
    data class Request(
        val rawUris: List<Uri>,
        val outputFormat: OutputFormat,
        val jpegQuality: Int,
        val jpegChroma: Int,
        val jpegOptimize: Boolean,
        val jpegEmbeddedPolicy: Int
    )

    companion object {
        const val TAG = "RegenerateDialog"
        const val REQUEST_KEY = "regenerate_request"

        private const val ARG_RAW_URIS = "raw_uris"
        private const val RESULT_FORMAT = "format"
        private const val RESULT_QUALITY = "quality"
        private const val RESULT_CHROMA = "chroma"
        private const val RESULT_OPTIMIZE = "optimize"
        private const val RESULT_EMBEDDED = "embedded"

        fun newInstance(rawUris: List<Uri>): RegenerateDialog {
            return RegenerateDialog().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_RAW_URIS, ArrayList(rawUris))
                }
            }
        }

        /** Decode a result bundle posted under [REQUEST_KEY]; null when it is malformed. */
        fun parseResult(result: Bundle): Request? {
            val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.getParcelableArrayList(ARG_RAW_URIS, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.getParcelableArrayList(ARG_RAW_URIS)
            } ?: return null
            val format = result.getString(RESULT_FORMAT)?.let { name ->
                OutputFormat.values().firstOrNull { it.name == name }
            } ?: return null
            return Request(
                rawUris = uris,
                outputFormat = format,
                jpegQuality = result.getInt(RESULT_QUALITY, DNGConverter.DEFAULT_QUALITY),
                jpegChroma = result.getInt(RESULT_CHROMA, DNGConverter.CHROMA_SUBSAMPLING_444),
                jpegOptimize = result.getBoolean(RESULT_OPTIMIZE, true),
                jpegEmbeddedPolicy = result.getInt(RESULT_EMBEDDED, DNGConverter.DEFAULT_EMBEDDED_POLICY)
            )
        }
    }

    private fun rawUris(): ArrayList<Uri> {
        val args = arguments ?: return arrayListOf()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            args.getParcelableArrayList(ARG_RAW_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            args.getParcelableArrayList(ARG_RAW_URIS)
        } ?: arrayListOf()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_regenerate, null)

        val radioGroupFormat = view.findViewById<RadioGroup>(R.id.radioGroupFormat)
        val jpegSettingsSection = view.findViewById<LinearLayout>(R.id.jpegSettingsSection)
        val seekQuality = view.findViewById<SeekBar>(R.id.seekQuality)
        val txtQualityValue = view.findViewById<TextView>(R.id.txtQualityValue)
        val radioGroupChroma = view.findViewById<RadioGroup>(R.id.radioGroupChroma)
        val radioGroupEmbedded = view.findViewById<RadioGroup>(R.id.radioGroupEmbedded)
        val chkOptimize = view.findViewById<CheckBox>(R.id.chkOptimize)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnRegenerate = view.findViewById<Button>(R.id.btnRegenerate)

        // Load app defaults for JPEG settings (but don't persist changes)
        val defaultQuality = JpegSettingsDialog.getJpegQuality(requireContext())
        val defaultChroma = JpegSettingsDialog.getJpegChroma(requireContext())
        val defaultOptimize = JpegSettingsDialog.getJpegOptimize(requireContext())
        val defaultEmbedded = JpegSettingsDialog.getJpegEmbeddedPolicy(requireContext())
        radioGroupEmbedded.check(JpegSettingsDialog.embeddedPolicyToRadioId(defaultEmbedded))

        // Initialize JPEG settings with app defaults
        seekQuality.progress = defaultQuality
        txtQualityValue.text = defaultQuality.toString()

        when (defaultChroma) {
            DNGConverter.CHROMA_SUBSAMPLING_444 -> radioGroupChroma.check(R.id.radioChroma444)
            DNGConverter.CHROMA_SUBSAMPLING_422 -> radioGroupChroma.check(R.id.radioChroma422)
            DNGConverter.CHROMA_SUBSAMPLING_420 -> radioGroupChroma.check(R.id.radioChroma420)
        }

        chkOptimize.isChecked = defaultOptimize

        // Quality SeekBar listener
        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = maxOf(80, progress)
                txtQualityValue.text = quality.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Format selection listener - show/hide JPEG settings
        radioGroupFormat.setOnCheckedChangeListener { _, checkedId ->
            jpegSettingsSection.visibility = if (checkedId == R.id.radioFormatJpeg) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        // Initial visibility based on default selection (JPEG)
        jpegSettingsSection.visibility = View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnRegenerate.setOnClickListener {
            val outputFormat = if (radioGroupFormat.checkedRadioButtonId == R.id.radioFormatDng) {
                OutputFormat.DNG
            } else {
                OutputFormat.JPEG
            }

            val quality = maxOf(80, seekQuality.progress)

            val chroma = when (radioGroupChroma.checkedRadioButtonId) {
                R.id.radioChroma444 -> DNGConverter.CHROMA_SUBSAMPLING_444
                R.id.radioChroma422 -> DNGConverter.CHROMA_SUBSAMPLING_422
                R.id.radioChroma420 -> DNGConverter.CHROMA_SUBSAMPLING_420
                else -> DNGConverter.CHROMA_SUBSAMPLING_444
            }

            val optimize = chkOptimize.isChecked
            val embeddedPolicy = JpegSettingsDialog.radioIdToEmbeddedPolicy(radioGroupEmbedded.checkedRadioButtonId)

            val result = Bundle().apply {
                putParcelableArrayList(ARG_RAW_URIS, rawUris())
                putString(RESULT_FORMAT, outputFormat.name)
                putInt(RESULT_QUALITY, quality)
                putInt(RESULT_CHROMA, chroma)
                putBoolean(RESULT_OPTIMIZE, optimize)
                putInt(RESULT_EMBEDDED, embeddedPolicy)
            }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, result)
            dismiss()
        }

        return dialog
    }
}
