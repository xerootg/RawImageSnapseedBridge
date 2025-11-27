package com.raw2dng

import android.app.Dialog
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
 */
class RegenerateDialog : DialogFragment() {

    /**
     * Callback interface for when user confirms regeneration.
     */
    interface OnRegenerateListener {
        fun onRegenerate(
            outputFormat: OutputFormat,
            jpegQuality: Int,
            jpegChroma: Int,
            jpegOptimize: Boolean
        )
    }
    
    private var listener: OnRegenerateListener? = null
    
    fun setOnRegenerateListener(listener: OnRegenerateListener) {
        this.listener = listener
    }

    companion object {
        const val TAG = "RegenerateDialog"
        
        fun newInstance(): RegenerateDialog {
            return RegenerateDialog()
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_regenerate, null)
        
        val radioGroupFormat = view.findViewById<RadioGroup>(R.id.radioGroupFormat)
        val jpegSettingsSection = view.findViewById<LinearLayout>(R.id.jpegSettingsSection)
        val seekQuality = view.findViewById<SeekBar>(R.id.seekQuality)
        val txtQualityValue = view.findViewById<TextView>(R.id.txtQualityValue)
        val radioGroupChroma = view.findViewById<RadioGroup>(R.id.radioGroupChroma)
        val chkOptimize = view.findViewById<CheckBox>(R.id.chkOptimize)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnRegenerate = view.findViewById<Button>(R.id.btnRegenerate)
        
        // Load app defaults for JPEG settings (but don't persist changes)
        val defaultQuality = JpegSettingsDialog.getJpegQuality(requireContext())
        val defaultChroma = JpegSettingsDialog.getJpegChroma(requireContext())
        val defaultOptimize = JpegSettingsDialog.getJpegOptimize(requireContext())
        
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
                val quality = maxOf(1, progress)
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
            
            val quality = maxOf(1, seekQuality.progress)
            
            val chroma = when (radioGroupChroma.checkedRadioButtonId) {
                R.id.radioChroma444 -> DNGConverter.CHROMA_SUBSAMPLING_444
                R.id.radioChroma422 -> DNGConverter.CHROMA_SUBSAMPLING_422
                R.id.radioChroma420 -> DNGConverter.CHROMA_SUBSAMPLING_420
                else -> DNGConverter.CHROMA_SUBSAMPLING_444
            }
            
            val optimize = chkOptimize.isChecked
            
            listener?.onRegenerate(outputFormat, quality, chroma, optimize)
            dismiss()
        }
        
        return dialog
    }
}
