package com.raw2dng

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings dialog for app configuration.
 */
class SettingsDialog : DialogFragment() {

    /**
     * Callback interface for when settings are saved.
     */
    interface OnSettingsSavedListener {
        fun onSettingsSaved()
    }
    
    private var onSettingsSavedListener: OnSettingsSavedListener? = null
    
    fun setOnSettingsSavedListener(listener: OnSettingsSavedListener) {
        onSettingsSavedListener = listener
    }

    companion object {
        const val TAG = "SettingsDialog"
        const val PREFS_NAME = "raw2dng_prefs"
        const val KEY_AUTONAV_TIMEOUT = "autonav_timeout_seconds"
        const val KEY_ENABLED_RAW_TYPES = "enabled_raw_types"
        const val KEY_HIDE_CONVERTED = "hide_converted_images"
        const val DEFAULT_TIMEOUT = 3
        
        // All supported RAW extensions (sorted alphabetically for display)
        val ALL_RAW_EXTENSIONS = arrayOf(
            "3fr", "arw", "cr2", "cr3", "dcr", "erf", "iiq", "k25", "kdc",
            "mef", "mos", "nef", "nrw", "orf", "pef", "raf", "rw2", "sr2", "srf"
        )
        
        fun newInstance(): SettingsDialog {
            return SettingsDialog()
        }
        
        /**
         * Get the set of enabled RAW extensions from preferences.
         * Returns all extensions if none are configured (default).
         */
        fun getEnabledRawTypes(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_ENABLED_RAW_TYPES, null)
            return saved ?: ALL_RAW_EXTENSIONS.toSet()
        }
        
        /**
         * Get whether to hide already converted images (true) or show them dimmed (false).
         * Default is true (hide them).
         */
        fun getHideConverted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HIDE_CONVERTED, true)
        }
    }
    
    // Track current selection state
    private lateinit var selectedTypes: BooleanArray
    private lateinit var btnSelectRawTypes: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_settings, null)
        
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        val seekAutonavTimeout = view.findViewById<SeekBar>(R.id.seekAutonavTimeout)
        val txtAutonavValue = view.findViewById<TextView>(R.id.txtAutonavValue)
        val txtAutonavWarning = view.findViewById<TextView>(R.id.txtAutonavWarning)
        btnSelectRawTypes = view.findViewById(R.id.btnSelectRawTypes)
        
        // Load current values from preferences
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTimeout = prefs.getInt(KEY_AUTONAV_TIMEOUT, DEFAULT_TIMEOUT)
        val enabledTypes = getEnabledRawTypes(requireContext())
        
        // Initialize selection state
        selectedTypes = BooleanArray(ALL_RAW_EXTENSIONS.size) { i ->
            enabledTypes.contains(ALL_RAW_EXTENSIONS[i])
        }
        
        // Setup auto-nav timeout
        seekAutonavTimeout.progress = currentTimeout
        updateTimeoutDisplay(txtAutonavValue, txtAutonavWarning, currentTimeout)
        
        seekAutonavTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTimeoutDisplay(txtAutonavValue, txtAutonavWarning, progress)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup RAW types button
        updateRawTypesButtonText()
        btnSelectRawTypes.setOnClickListener {
            showRawTypesDialog()
        }
        
        // Setup hide converted checkbox
        val chkHideConverted = view.findViewById<android.widget.CheckBox>(R.id.chkHideConverted)
        chkHideConverted.isChecked = getHideConverted(requireContext())
        
        // Setup JPEG settings button
        val btnJpegSettings = view.findViewById<Button>(R.id.btnJpegSettings)
        btnJpegSettings.setOnClickListener {
            JpegSettingsDialog.newInstance().show(childFragmentManager, JpegSettingsDialog.TAG)
        }
        
        // Setup licenses button
        val btnLicenses = view.findViewById<Button>(R.id.btnLicenses)
        btnLicenses.setOnClickListener {
            LicensesDialog.newInstance().show(childFragmentManager, LicensesDialog.TAG)
        }
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
        
        btnSave.setOnClickListener {
            // Save all settings
            val editor = prefs.edit()
            editor.putInt(KEY_AUTONAV_TIMEOUT, seekAutonavTimeout.progress)
            
            // Save enabled RAW types
            val enabledSet = mutableSetOf<String>()
            for (i in ALL_RAW_EXTENSIONS.indices) {
                if (selectedTypes[i]) {
                    enabledSet.add(ALL_RAW_EXTENSIONS[i])
                }
            }
            editor.putStringSet(KEY_ENABLED_RAW_TYPES, enabledSet)
            
            // Save hide converted setting
            editor.putBoolean(KEY_HIDE_CONVERTED, chkHideConverted.isChecked)
            
            editor.apply()
            
            onSettingsSavedListener?.onSettingsSaved()
            dismiss()
        }
        
        return dialog
    }
    
    private fun updateTimeoutDisplay(valueText: TextView, warningText: TextView, seconds: Int) {
        valueText.text = "${seconds}s"
        warningText.visibility = if (seconds == 0) View.VISIBLE else View.GONE
    }
    
    private fun updateRawTypesButtonText() {
        val selectedCount = selectedTypes.count { it }
        val totalCount = ALL_RAW_EXTENSIONS.size
        btnSelectRawTypes.text = getString(R.string.raw_types_count, selectedCount, totalCount)
    }
    
    private fun showRawTypesDialog() {
        // Create uppercase labels for display
        val displayLabels = ALL_RAW_EXTENSIONS.map { it.uppercase() }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.raw_types_dialog_title)
            .setMultiChoiceItems(displayLabels, selectedTypes) { _, which, isChecked ->
                selectedTypes[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                updateRawTypesButtonText()
            }
            .setNeutralButton(R.string.select_all) { dialog, _ ->
                // Select all
                for (i in selectedTypes.indices) {
                    selectedTypes[i] = true
                }
                updateRawTypesButtonText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.clear_all) { dialog, _ ->
                // Deselect all (but keep at least one)
                for (i in selectedTypes.indices) {
                    selectedTypes[i] = false
                }
                // Keep first one selected to prevent empty selection
                if (selectedTypes.none { it }) {
                    selectedTypes[0] = true
                }
                updateRawTypesButtonText()
                dialog.dismiss()
            }
            .show()
    }
}
