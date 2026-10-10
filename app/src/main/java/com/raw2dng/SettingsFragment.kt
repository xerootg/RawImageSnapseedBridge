package com.raw2dng

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.raw2dng.rrc.RrcConfig
import com.raw2dng.rrc.RrcPairing
import com.raw2dng.source.DocumentTreeSource
import com.raw2dng.source.SourceFetchCache
import com.raw2dng.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings fragment for app configuration.
 * This is a full-screen fragment that appears as a tab in the main activity.
 */
class SettingsFragment : Fragment() {

    companion object {
        const val PREFS_NAME = "raw2dng_prefs"
        const val KEY_AUTONAV_TIMEOUT = "autonav_timeout_seconds"
        const val KEY_ENABLED_RAW_TYPES = "enabled_raw_types"
        const val KEY_HIDE_CONVERTED = "hide_converted_images"
        const val KEY_OPEN_IN_SNAPSEED = "open_single_dng_in_snapseed"
        const val KEY_SHARE_SINGLE_JPEG = "share_single_jpeg_on_completion"
        const val KEY_CONVERSION_PARALLELISM = "conversion_parallelism"
        const val KEY_RRC_SERVICE = "rrc_pairing_service"
        const val DEFAULT_TIMEOUT = 3
        const val DEFAULT_PARALLELISM = 2
        const val SNAPSEED_PACKAGE = "com.niksoftware.snapseed"
        
        // All supported RAW extensions - sourced from RawTypesDialogFragment.MANUFACTURERS
        // Note: DNG is NOT included - it's an output format, not an input format
        val ALL_RAW_EXTENSIONS: Array<String>
            get() = RawTypesDialogFragment.getAllExtensions()
        
        fun newInstance(): SettingsFragment {
            return SettingsFragment()
        }
        
        /**
         * Get the set of enabled RAW extensions from preferences.
         * Returns all extensions if none are configured (default).
         */
        fun getEnabledRawTypes(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_ENABLED_RAW_TYPES, null)
            // Drop extensions a previous backend offered but the current one cannot decode
            return saved?.intersect(ALL_RAW_EXTENSIONS.toSet()) ?: ALL_RAW_EXTENSIONS.toSet()
        }
        
        /**
         * Get whether to hide already converted images (true) or show them dimmed (false).
         * Default is true (hide them).
         */
        fun getHideConverted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HIDE_CONVERTED, true)
        }
        
        /**
         * Get whether to open single DNG conversions in Snapseed instead of navigating to gallery.
         * Default is false.
         */
        fun getOpenInSnapseed(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_OPEN_IN_SNAPSEED, false)
        }
        
        /**
         * Get whether to prompt share for single JPEG conversions instead of navigating to gallery.
         * Default is false.
         */
        fun getShareSingleJpeg(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SHARE_SINGLE_JPEG, false)
        }
        
        /**
         * Check if Snapseed is installed on the device.
         */
        fun isSnapseedInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(SNAPSEED_PACKAGE, 0)
                true
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
        }
        
        /**
         * Get the auto-navigate timeout in seconds.
         */
        fun getAutoNavTimeout(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_AUTONAV_TIMEOUT, DEFAULT_TIMEOUT)
        }
        
        /**
         * Get the conversion parallelism (number of concurrent conversion threads).
         * Returns a value between 1 and the number of CPU cores.
         */
        fun getConversionParallelism(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val numCores = Runtime.getRuntime().availableProcessors()
            val saved = prefs.getInt(KEY_CONVERSION_PARALLELISM, DEFAULT_PARALLELISM)
            // Clamp to valid range (1 to numCores)
            return saved.coerceIn(1, numCores)
        }
        
        /**
         * Get the number of available CPU cores.
         */
        fun getNumCores(): Int {
            return Runtime.getRuntime().availableProcessors()
        }
    }
    
    // Track current selection state for RAW types
    private lateinit var selectedTypes: BooleanArray
    private lateinit var btnSelectRawTypes: Button
    
    // UI elements that need to be accessed for saving
    private lateinit var seekAutonavTimeout: SeekBar
    private lateinit var seekConversionCores: SeekBar
    private lateinit var chkHideConverted: CheckBox
    private lateinit var chkOpenInSnapseed: CheckBox
    private lateinit var chkShareJpeg: CheckBox

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        seekAutonavTimeout = view.findViewById(R.id.seekAutonavTimeout)
        val txtAutonavValue = view.findViewById<TextView>(R.id.txtAutonavValue)
        val txtAutonavWarning = view.findViewById<TextView>(R.id.txtAutonavWarning)
        btnSelectRawTypes = view.findViewById(R.id.btnSelectRawTypes)
        chkHideConverted = view.findViewById(R.id.chkHideConverted)
        chkOpenInSnapseed = view.findViewById(R.id.chkOpenInSnapseed)
        val txtSnapseedHint = view.findViewById<TextView>(R.id.txtSnapseedHint)
        val btnJpegSettings = view.findViewById<Button>(R.id.btnJpegSettings)
        val btnLicenses = view.findViewById<Button>(R.id.btnLicenses)
        
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
                // Auto-save when changed
                saveSettings()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup conversion parallelism
        seekConversionCores = view.findViewById(R.id.seekConversionCores)
        val txtConversionCoresValue = view.findViewById<TextView>(R.id.txtConversionCoresValue)
        val numCores = getNumCores()
        val currentParallelism = getConversionParallelism(requireContext())
        
        // SeekBar range is 0 to (numCores-1), representing 1 to numCores
        seekConversionCores.max = numCores - 1
        seekConversionCores.progress = currentParallelism - 1
        updateParallelismDisplay(txtConversionCoresValue, currentParallelism, numCores)
        
        seekConversionCores.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val parallelism = progress + 1  // Convert 0-based to 1-based
                updateParallelismDisplay(txtConversionCoresValue, parallelism, numCores)
                // Auto-save when changed
                saveSettings()
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
        chkHideConverted.isChecked = getHideConverted(requireContext())
        chkHideConverted.setOnCheckedChangeListener { _, _ ->
            saveSettings()
            notifySettingsChanged()
        }
        
        // Setup Snapseed checkbox
        val snapseedInstalled = isSnapseedInstalled(requireContext())
        val currentSnapseedPref = getOpenInSnapseed(requireContext())
        android.util.Log.d("SettingsFragment", "Loading settings: snapseedInstalled=$snapseedInstalled, currentPref=$currentSnapseedPref")
        chkOpenInSnapseed.isChecked = currentSnapseedPref && snapseedInstalled
        chkOpenInSnapseed.isEnabled = snapseedInstalled
        if (!snapseedInstalled) {
            txtSnapseedHint.setText(R.string.snapseed_not_installed)
            txtSnapseedHint.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
        }
        chkOpenInSnapseed.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("SettingsFragment", "Snapseed checkbox changed to: $isChecked")
            saveSettings()
        }
        
        // Setup Share JPEG checkbox
        chkShareJpeg = view.findViewById(R.id.chkShareJpeg)
        chkShareJpeg.isChecked = getShareSingleJpeg(requireContext())
        chkShareJpeg.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("SettingsFragment", "Share JPEG checkbox changed to: $isChecked")
            saveSettings()
        }
        
        // Setup JPEG settings button
        btnJpegSettings.setOnClickListener {
            JpegSettingsDialog.newInstance().show(childFragmentManager, JpegSettingsDialog.TAG)
        }
        
        // Setup licenses button
        btnLicenses.setOnClickListener {
            LicensesDialog.newInstance().show(childFragmentManager, LicensesDialog.TAG)
        }
        
        // Show which native RAW engine is compiled in
        view.findViewById<TextView>(R.id.txtBackendInfo).text =
            getString(R.string.backend_info, DNGConverter.backendName, DNGConverter.backendVersion)

        setupSourcesSection(view)
    }

    // === Sources: RapidRawCloud pairing, saved folders, fetch cache ===

    private fun setupSourcesSection(view: View) {
        val txtStatus = view.findViewById<TextView>(R.id.txtRrcStatus)
        val serviceLayout = view.findViewById<View>(R.id.rrcServiceLayout)
        val editService = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editRrcService)
        val btnPair = view.findViewById<Button>(R.id.btnRrcPair)
        val btnUnpair = view.findViewById<Button>(R.id.btnRrcUnpair)

        fun refreshRrc() {
            val cfg = RrcConfig.load(requireContext())
            if (cfg != null) {
                txtStatus.text = getString(R.string.rrc_paired, cfg.bucket, cfg.host)
                serviceLayout.visibility = View.GONE
                btnPair.visibility = View.GONE
                btnUnpair.visibility = View.VISIBLE
            } else {
                txtStatus.text = getString(R.string.rrc_not_paired)
                serviceLayout.visibility = View.VISIBLE
                btnPair.visibility = View.VISIBLE
                btnUnpair.visibility = View.GONE
            }
        }
        refreshRrc()
        editService.setText(requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_RRC_SERVICE, ""))

        btnPair.setOnClickListener {
            val service = editService.text?.toString() ?: ""
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_RRC_SERVICE, service).apply()
            btnPair.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    RrcPairing.start(requireContext(), service)
                    android.widget.Toast.makeText(requireContext(), R.string.rrc_pairing_started, android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.rrc_pairing_failed, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                } finally {
                    btnPair.isEnabled = true
                }
            }
        }
        btnUnpair.setOnClickListener {
            RrcPairing.unpair(requireContext())
            refreshRrc()
        }

        val folderList = view.findViewById<android.widget.LinearLayout>(R.id.sourceFolderList)
        fun refreshFolders() {
            folderList.removeAllViews()
            val trees = SourceRegistry.all().filterIsInstance<DocumentTreeSource>()
            if (trees.isEmpty()) {
                folderList.addView(TextView(requireContext()).apply {
                    text = getString(R.string.sources_no_folders)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                })
                return
            }
            for (tree in trees) {
                val row = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(TextView(requireContext()).apply {
                    text = "${tree.descriptor.displayName}\n${tree.descriptor.detail}"
                    textSize = 13f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.source_forget)
                    setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.source_forget_confirm, tree.descriptor.displayName))
                            .setPositiveButton(R.string.source_forget) { _, _ ->
                                SourceRegistry.removeDocumentTree(requireContext(), tree)
                                refreshFolders()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                })
                folderList.addView(row)
            }
        }
        refreshFolders()

        val btnClearCache = view.findViewById<Button>(R.id.btnClearFetchCache)
        fun refreshCacheLabel() {
            val bytes = SourceFetchCache.sizeBytes(requireContext())
            val label = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
            btnClearCache.text = getString(R.string.sources_clear_cache, label)
        }
        refreshCacheLabel()
        btnClearCache.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { SourceFetchCache.clear(requireContext()) }
                refreshCacheLabel()
            }
        }
    }
    
    private fun saveSettings() {
        if (!isAdded) return
        
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putInt(KEY_AUTONAV_TIMEOUT, seekAutonavTimeout.progress)
        
        // Save conversion parallelism (convert 0-based seekbar to 1-based value)
        editor.putInt(KEY_CONVERSION_PARALLELISM, seekConversionCores.progress + 1)
        
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
        
        // Save Snapseed setting
        editor.putBoolean(KEY_OPEN_IN_SNAPSEED, chkOpenInSnapseed.isChecked)
        
        // Save Share JPEG setting
        editor.putBoolean(KEY_SHARE_SINGLE_JPEG, chkShareJpeg.isChecked)
        
        editor.apply()
        
        android.util.Log.d("SettingsFragment", "Settings saved - Snapseed: ${chkOpenInSnapseed.isChecked}, ShareJpeg: ${chkShareJpeg.isChecked}")
    }
    
    private fun notifySettingsChanged() {
        // Notify MainActivity to refresh the convert tab when relevant settings change
        (activity as? MainActivity)?.refreshConvertTab()
    }
    
    private fun updateTimeoutDisplay(valueText: TextView, warningText: TextView, seconds: Int) {
        valueText.text = "${seconds}s"
        warningText.visibility = if (seconds == 0) View.VISIBLE else View.GONE
    }
    
    private fun updateParallelismDisplay(valueText: TextView, parallelism: Int, numCores: Int) {
        valueText.text = getString(R.string.conversion_parallelism_value, parallelism, numCores)
    }
    
    private fun updateRawTypesButtonText() {
        val selectedCount = selectedTypes.count { it }
        val totalCount = ALL_RAW_EXTENSIONS.size
        btnSelectRawTypes.text = getString(R.string.raw_types_count, selectedCount, totalCount)
    }
    
    private fun showRawTypesDialog() {
        val dialog = RawTypesDialogFragment.newInstance()
        dialog.onSelectionConfirmed = { selectedExtensions ->
            // Update selection state from the dialog result
            for (i in ALL_RAW_EXTENSIONS.indices) {
                selectedTypes[i] = selectedExtensions.contains(ALL_RAW_EXTENSIONS[i])
            }
            updateRawTypesButtonText()
            saveSettings()
            notifySettingsChanged()
        }
        dialog.show(childFragmentManager, RawTypesDialogFragment.TAG)
    }
}
