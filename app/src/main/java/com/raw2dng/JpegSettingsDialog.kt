package com.raw2dng

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog for configuring JPEG conversion settings.
 * Settings are persisted and used as defaults for all JPEG conversions.
 */
class JpegSettingsDialog : DialogFragment() {

    companion object {
        const val TAG = "JpegSettingsDialog"
        const val PREFS_NAME = "raw2dng_prefs"
        
        // SharedPreferences keys
        const val KEY_JPEG_QUALITY = "jpeg_quality"
        const val KEY_JPEG_CHROMA = "jpeg_chroma_subsampling"
        const val KEY_JPEG_OPTIMIZE = "jpeg_optimize_coding"
        
        // Defaults matching DNGConverter constants
        const val DEFAULT_QUALITY = 95
        const val DEFAULT_CHROMA = DNGConverter.CHROMA_SUBSAMPLING_444
        const val DEFAULT_OPTIMIZE = true
        
        fun newInstance(): JpegSettingsDialog {
            return JpegSettingsDialog()
        }
        
        /**
         * Get the configured JPEG quality (1-100).
         */
        fun getJpegQuality(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_QUALITY)
        }
        
        /**
         * Get the configured chroma subsampling mode.
         * Returns one of DNGConverter.CHROMA_SUBSAMPLING_444/422/420.
         */
        fun getJpegChroma(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_JPEG_CHROMA, DEFAULT_CHROMA)
        }
        
        /**
         * Get whether Huffman table optimization is enabled.
         */
        fun getJpegOptimize(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_JPEG_OPTIMIZE, DEFAULT_OPTIMIZE)
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_jpeg_settings, null)
        
        val seekQuality = view.findViewById<SeekBar>(R.id.seekJpegQuality)
        val txtQualityValue = view.findViewById<TextView>(R.id.txtQualityValue)
        val radioGroupChroma = view.findViewById<RadioGroup>(R.id.radioGroupChroma)
        val chkOptimize = view.findViewById<CheckBox>(R.id.chkOptimizeHuffman)
        val btnOk = view.findViewById<Button>(R.id.btnOk)
        
        // Load current values from preferences
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_QUALITY)
        val currentChroma = prefs.getInt(KEY_JPEG_CHROMA, DEFAULT_CHROMA)
        val currentOptimize = prefs.getBoolean(KEY_JPEG_OPTIMIZE, DEFAULT_OPTIMIZE)
        
        // Setup quality SeekBar
        seekQuality.progress = currentQuality
        txtQualityValue.text = currentQuality.toString()
        
        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Ensure minimum of 1
                val quality = maxOf(1, progress)
                txtQualityValue.text = quality.toString()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup chroma subsampling radio group
        when (currentChroma) {
            DNGConverter.CHROMA_SUBSAMPLING_444 -> radioGroupChroma.check(R.id.radioChroma444)
            DNGConverter.CHROMA_SUBSAMPLING_422 -> radioGroupChroma.check(R.id.radioChroma422)
            DNGConverter.CHROMA_SUBSAMPLING_420 -> radioGroupChroma.check(R.id.radioChroma420)
        }
        
        // Setup optimize checkbox
        chkOptimize.isChecked = currentOptimize
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
        
        btnOk.setOnClickListener {
            // Save all settings
            val editor = prefs.edit()
            
            val quality = maxOf(1, seekQuality.progress)
            editor.putInt(KEY_JPEG_QUALITY, quality)
            
            val chroma = when (radioGroupChroma.checkedRadioButtonId) {
                R.id.radioChroma444 -> DNGConverter.CHROMA_SUBSAMPLING_444
                R.id.radioChroma422 -> DNGConverter.CHROMA_SUBSAMPLING_422
                R.id.radioChroma420 -> DNGConverter.CHROMA_SUBSAMPLING_420
                else -> DEFAULT_CHROMA
            }
            editor.putInt(KEY_JPEG_CHROMA, chroma)
            
            editor.putBoolean(KEY_JPEG_OPTIMIZE, chkOptimize.isChecked)
            
            editor.apply()
            dismiss()
        }
        
        return dialog
    }
}
