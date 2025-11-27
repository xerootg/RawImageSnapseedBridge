package com.raw2dng

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.raw2dng.databinding.FragmentRawPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import java.util.UUID

class RawFilePickerFragment : Fragment() {

    private var _binding: FragmentRawPickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: RawFileAdapter
    private var allRawFiles = mutableListOf<RawFileItem>()
    private var currentFilter: FileFilter = FileFilter.NOT_DNG
    private var hadPermissionsLastCheck = false
    private var lastConversionFormat: OutputFormat = OutputFormat.DNG

    private val tag = "RawFilePicker"
    
    // Auto-navigate countdown
    private var countdownJob: Job? = null
    private var conversionCompletedSuccessfully = false  // Track if all conversions succeeded
    private var conversionWarningCount = 0  // Track warnings (e.g., couldn't overwrite)
    private val PREFS_NAME = "raw2dng_prefs"
    private val KEY_AUTO_NAVIGATE = "auto_navigate_gallery"
    private val KEY_SHOW_LOG = "show_conversion_log"
    private val KEY_AUTONAV_TIMEOUT = "autonav_timeout_seconds"
    private val DEFAULT_COUNTDOWN_SECONDS = 3
    
    // Log view
    private var showingLog = false
    private val logMessages = StringBuilder()

    // Filter options for the file picker
    enum class FileFilter {
        ALL,
        NOT_DNG,
        NOT_JPEG
    }
    
    // Save result to track warnings
    sealed class SaveResult {
        data class Success(val uri: Uri) : SaveResult()
        data class SuccessWithWarning(val uri: Uri, val warning: String) : SaveResult()
        object Failed : SaveResult()
    }

    // Conversion
    private val converter = DNGConverter()
    private var conversionQueue: ConversionQueue? = null
    private lateinit var conversionThumbnailAdapter: ConversionThumbnailAdapter

    // Supported RAW extensions - loaded from settings
    private fun getEnabledRawExtensions(): Set<String> {
        return SettingsDialog.getEnabledRawTypes(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRawPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadRawFiles()
    }

    override fun onResume() {
        super.onResume()
        // Check if permissions changed since last check
        val hasPermissions = hasRequiredPermissions()
        if (hasPermissions && !hadPermissionsLastCheck && allRawFiles.isEmpty()) {
            // Permissions were just granted, reload files
            Log.d(tag, "Permissions granted, reloading files")
            loadRawFiles()
        } else if (hasPermissions && allRawFiles.isNotEmpty()) {
            // Refresh conversion status in case files were deleted from gallery
            refreshConversionStatus()
        }
        hadPermissionsLastCheck = hasPermissions
    }
    
    /**
     * Refresh the conversion status of all cached RAW files.
     * This is called on resume to reflect any changes made in the gallery (e.g., deleted files).
     * Also called by MainActivity when gallery deletes files.
     */
    fun refreshConversionStatus() {
        if (_binding == null) {
            Log.d(tag, "refreshConversionStatus: binding is null, skipping")
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            var changed = false
            withContext(Dispatchers.IO) {
                allRawFiles.forEach { item ->
                    val wasDng = item.isConvertedToDng
                    val wasJpeg = item.isConvertedToJpeg
                    item.isConvertedToDng = ConvertedFilesHelper.isConvertedToDng(item.name)
                    item.isConvertedToJpeg = ConvertedFilesHelper.isConvertedToJpeg(item.name)
                    if (wasDng != item.isConvertedToDng || wasJpeg != item.isConvertedToJpeg) {
                        changed = true
                        Log.d(tag, "Conversion status changed for ${item.name}: DNG=$wasDng->${item.isConvertedToDng}, JPEG=$wasJpeg->${item.isConvertedToJpeg}")
                    }
                }
            }
            if (changed && _binding != null) {
                Log.d(tag, "Refreshing UI after status change")
                applyFilter()
                // Force the adapter to rebind all items
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val context = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshFiles() {
        loadRawFiles()
    }

    private fun setupUI() {
        adapter = RawFileAdapter(
            onSelectionToggle = { item ->
                adapter.toggleSelection(item)
                updateSelectionUI()
            },
            onDoubleTap = { item ->
                showImagePreview(item)
            }
        )

        binding.rawFilesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.rawFilesRecycler.adapter = adapter

        // Filter chip listeners
        binding.chipShowAll.setOnClickListener {
            currentFilter = FileFilter.ALL
            binding.chipShowAll.isChecked = true
            binding.chipNotDng.isChecked = false
            binding.chipNotJpeg.isChecked = false
            applyFilter()
        }

        binding.chipNotDng.setOnClickListener {
            currentFilter = FileFilter.NOT_DNG
            binding.chipShowAll.isChecked = false
            binding.chipNotDng.isChecked = true
            binding.chipNotJpeg.isChecked = false
            applyFilter()
        }
        
        binding.chipNotJpeg.setOnClickListener {
            currentFilter = FileFilter.NOT_JPEG
            binding.chipShowAll.isChecked = false
            binding.chipNotDng.isChecked = false
            binding.chipNotJpeg.isChecked = true
            applyFilter()
        }

        binding.btnSelectAll.setOnClickListener {
            val visibleFiles = getFilteredFiles()
            if (adapter.getSelectedItems().size == visibleFiles.size) {
                adapter.clearSelection()
            } else {
                adapter.selectAll(visibleFiles)
            }
            updateSelectionUI()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnConvertDng.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                startConversion(selected, OutputFormat.DNG)
            }
        }
        
        binding.btnConvertJpeg.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                startConversion(selected, OutputFormat.JPEG)
            }
        }

        // Done button navigates to Gallery tab with the appropriate filter
        binding.btnDone.setOnClickListener {
            cancelCountdown()
            (activity as? MainActivity)?.navigateToGallery(lastConversionFormat)
            // Reset the view for next time
            showPickerContent()
        }
        
        // Auto-navigate checkbox - load saved preference
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.checkAutoNavigate.isChecked = prefs.getBoolean(KEY_AUTO_NAVIGATE, false)
        binding.checkAutoNavigate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_NAVIGATE, isChecked).apply()
            if (isChecked && conversionCompletedSuccessfully) {
                // User checked the box after successful conversion - start countdown
                startAutoNavigateCountdown()
            } else if (!isChecked) {
                // If unchecked during countdown, cancel it
                cancelCountdown()
                binding.btnDone.text = getString(R.string.done)
            }
        }
        
        // Setup conversion thumbnail grid
        conversionThumbnailAdapter = ConversionThumbnailAdapter()
        binding.conversionThumbnailGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.conversionThumbnailGrid.adapter = conversionThumbnailAdapter
        
        // Log/Thumbnails toggle - load saved preference
        showingLog = prefs.getBoolean(KEY_SHOW_LOG, false)
        updateLogToggleView()
        binding.btnToggleView.setOnClickListener {
            showingLog = !showingLog
            prefs.edit().putBoolean(KEY_SHOW_LOG, showingLog).apply()
            updateLogToggleView()
        }
    }
    
    private fun updateLogToggleView() {
        if (showingLog) {
            binding.conversionThumbnailGrid.visibility = View.GONE
            binding.conversionLogScroll.visibility = View.VISIBLE
            binding.btnToggleView.text = getString(R.string.show_thumbnails)
            // Auto-scroll to bottom
            binding.conversionLogScroll.post {
                binding.conversionLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        } else {
            binding.conversionThumbnailGrid.visibility = View.VISIBLE
            binding.conversionLogScroll.visibility = View.GONE
            binding.btnToggleView.text = getString(R.string.show_log)
        }
    }
    
    private fun appendLog(message: String) {
        logMessages.append(message).append("\n")
        binding.conversionLogText.text = logMessages.toString()
        // Auto-scroll if in log view
        if (showingLog) {
            binding.conversionLogScroll.post {
                binding.conversionLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun loadRawFiles() {
        // Check permissions first
        val hasPermissions = hasRequiredPermissions()
        hadPermissionsLastCheck = hasPermissions

        if (!hasPermissions) {
            binding.loadingProgress.visibility = View.GONE
            binding.emptyText.text = getString(R.string.permission_required)
            binding.emptyText.visibility = View.VISIBLE
            binding.rawFilesRecycler.visibility = View.GONE
            Log.d(tag, "Storage permission not granted")
            return
        }

        binding.loadingProgress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        binding.rawFilesRecycler.visibility = View.GONE

        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                queryRawFiles()
            }

            allRawFiles.clear()
            allRawFiles.addAll(files)

            binding.loadingProgress.visibility = View.GONE

            if (files.isEmpty()) {
                binding.emptyText.text = getString(R.string.no_raw_files_found)
                binding.emptyText.visibility = View.VISIBLE
                binding.rawFilesRecycler.visibility = View.GONE
            } else {
                binding.emptyText.visibility = View.GONE
                binding.rawFilesRecycler.visibility = View.VISIBLE
                applyFilter()
            }

            Log.d(tag, "Loaded ${files.size} RAW files")
        }
    }

    private fun queryRawFiles(): List<RawFileItem> {
        val files = mutableListOf<RawFileItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val cursor: Cursor? = requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            val enabledExtensions = getEnabledRawExtensions()
            
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn) ?: continue
                val size = it.getLong(sizeColumn)
                val date = it.getLong(dateColumn)

                // Check if it's a RAW file by extension
                val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                if (ext !in enabledExtensions) continue

                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val isConvertedToDng = ConvertedFilesHelper.isConvertedToDng(name)
                val isConvertedToJpeg = ConvertedFilesHelper.isConvertedToJpeg(name)

                files.add(RawFileItem(uri, name, size, date, "", isConvertedToDng, isConvertedToJpeg))
            }
        }

        return files
    }

    private fun getFilteredFiles(): List<RawFileItem> {
        val hideConverted = SettingsDialog.getHideConverted(requireContext())
        
        return when (currentFilter) {
            FileFilter.ALL -> {
                // In ALL mode, no dimming - show everything as-is
                allRawFiles.map { it.copy(isDimmed = false) }
            }
            FileFilter.NOT_DNG -> {
                if (hideConverted) {
                    // Hide converted: filter them out completely
                    allRawFiles.filter { !it.isConvertedToDng }.map { it.copy(isDimmed = false) }
                } else {
                    // Show converted but dimmed
                    allRawFiles.map { it.copy(isDimmed = it.isConvertedToDng) }
                }
            }
            FileFilter.NOT_JPEG -> {
                if (hideConverted) {
                    // Hide converted: filter them out completely
                    allRawFiles.filter { !it.isConvertedToJpeg }.map { it.copy(isDimmed = false) }
                } else {
                    // Show converted but dimmed
                    allRawFiles.map { it.copy(isDimmed = it.isConvertedToJpeg) }
                }
            }
        }
    }

    private fun applyFilter() {
        val filtered = getFilteredFiles()
        adapter.submitList(filtered)
        
        val totalCount = allRawFiles.size
        val dngCount = allRawFiles.count { it.isConvertedToDng }
        val jpegCount = allRawFiles.count { it.isConvertedToJpeg }
        val selectableCount = filtered.count { !it.isDimmed }
        val showingCount = filtered.size
        
        binding.selectionCount.text = when (currentFilter) {
            FileFilter.ALL -> "$totalCount files ($dngCount DNG, $jpegCount JPEG converted)"
            FileFilter.NOT_DNG -> {
                if (selectableCount == showingCount) {
                    "$showingCount not converted to DNG ($dngCount already DNG)"
                } else {
                    "$selectableCount selectable, $dngCount grayed out (already DNG)"
                }
            }
            FileFilter.NOT_JPEG -> {
                if (selectableCount == showingCount) {
                    "$showingCount not converted to JPEG ($jpegCount already JPEG)"
                } else {
                    "$selectableCount selectable, $jpegCount grayed out (already JPEG)"
                }
            }
        }

        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val selectedCount = adapter.getSelectedItems().size
        // Only count non-dimmed items as selectable
        val selectableCount = getFilteredFiles().count { !it.isDimmed }

        binding.btnConvertDng.isEnabled = selectedCount > 0
        binding.btnConvertJpeg.isEnabled = selectedCount > 0
        
        binding.btnConvertDng.text = if (selectedCount > 0) {
            "DNG ($selectedCount)"
        } else {
            getString(R.string.to_dng)
        }
        
        binding.btnConvertJpeg.text = if (selectedCount > 0) {
            "JPEG ($selectedCount)"
        } else {
            getString(R.string.to_jpeg)
        }

        binding.btnSelectAll.text = if (selectedCount == selectableCount && selectableCount > 0) {
            "Deselect All"
        } else {
            getString(R.string.select_all)
        }
    }

    private fun showImagePreview(item: RawFileItem) {
        // Get the currently visible (filtered) list of files
        val visibleFiles = getFilteredFiles()
        
        // Build lists of URIs, filenames, and conversion status
        val uris = ArrayList(visibleFiles.map { it.uri })
        val fileNames = ArrayList(visibleFiles.map { it.name })
        val dngStatus = ArrayList(visibleFiles.map { it.isConvertedToDng })
        val jpegStatus = ArrayList(visibleFiles.map { it.isConvertedToJpeg })
        
        // Get currently selected URIs
        val selectedUris = ArrayList(adapter.getSelectedItems().map { it.uri })
        
        // Find the position of the clicked item in the filtered list
        val position = visibleFiles.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        
        val previewDialog = ImagePreviewDialog.newInstance(uris, fileNames, position, selectedUris, dngStatus, jpegStatus)
        
        // Handle selection changes from the preview dialog
        previewDialog.setOnSelectionChangeListener(object : ImagePreviewDialog.OnSelectionChangeListener {
            override fun onSelectionChanged(uri: Uri, isSelected: Boolean) {
                // Find the item with this URI and toggle its selection
                val fileItem = visibleFiles.find { it.uri == uri }
                fileItem?.let {
                    if (isSelected) {
                        adapter.addToSelection(it)
                    } else {
                        adapter.removeFromSelection(it)
                    }
                    updateSelectionUI()
                }
            }
        })

        // Handle convert request from preview dialog with format selection
        previewDialog.setOnConvertRequestedListener(object : ImagePreviewDialog.OnConvertRequestedListener {
            override fun onConvertRequested(format: OutputFormat) {
                val selected = adapter.getSelectedItems()
                if (selected.isNotEmpty()) {
                    previewDialog.dismiss()
                    startConversion(selected, format)
                }
            }
        })
        
        previewDialog.show(childFragmentManager, "image_preview")
    }

    // === Conversion Logic ===

    private fun startConversion(selectedFiles: List<RawFileItem>, outputFormat: OutputFormat) {
        if (selectedFiles.isEmpty()) return
        
        // Reset the success flag and warning count for new conversion
        conversionCompletedSuccessfully = false
        conversionWarningCount = 0
        
        // Track the format for navigation after completion
        lastConversionFormat = outputFormat

        // Show conversion overlay
        showConversionOverlay()
        
        // Determine which files need overwrite confirmation
        val (needsConfirmation, readyToConvert) = selectedFiles.partition { rawFile ->
            when (outputFormat) {
                OutputFormat.DNG -> rawFile.isConvertedToDng
                OutputFormat.JPEG -> rawFile.isConvertedToJpeg
            }
        }

        val fileCount = selectedFiles.size
        val initialConvertCount = readyToConvert.size
        binding.conversionProgressBar.max = fileCount
        binding.conversionProgressBar.progress = 0
        binding.conversionStatus.text = "0/$fileCount"
        binding.btnDone.isEnabled = false
        
        // Populate the thumbnail grid with selected files
        val thumbnailItems = selectedFiles.map { rawFile ->
            val needsOverwriteFlag = when (outputFormat) {
                OutputFormat.DNG -> rawFile.isConvertedToDng
                OutputFormat.JPEG -> rawFile.isConvertedToJpeg
            }
            ConversionThumbnailItem(
                uri = rawFile.uri,
                fileName = rawFile.name,
                status = if (needsOverwriteFlag) ConversionItemStatus.NEEDS_CONFIRMATION else ConversionItemStatus.PENDING,
                needsOverwrite = needsOverwriteFlag
            )
        }
        conversionThumbnailAdapter.setItems(thumbnailItems)
        
        val formatName = if (outputFormat == OutputFormat.DNG) "DNG" else "JPEG"
        val extension = if (outputFormat == OutputFormat.DNG) "dng" else "jpg"
        
        if (needsConfirmation.isNotEmpty()) {
            appendLog("${needsConfirmation.size} file(s) already converted - tap to confirm overwrite\n")
        }

        Log.d(tag, "Starting $formatName conversion of $fileCount file(s) (${needsConfirmation.size} need confirmation)...")
        if (initialConvertCount > 0) {
            appendLog("Starting $formatName conversion of $initialConvertCount file(s)...\n")
        }
        
        // Create a map for looking up RawFileItems by URI (for dynamic task creation)
        val uriToRawFile = selectedFiles.associateBy { it.uri }
        
        // Set up overwrite confirmation callback
        conversionThumbnailAdapter.setOnOverwriteConfirmed { uri ->
            val rawFile = uriToRawFile[uri] ?: return@setOnOverwriteConfirmed
            try {
                val fileName = rawFile.name
                val uniqueId = UUID.randomUUID().toString().take(8)
                val inputPath = copyUriToCache(uri, fileName, uniqueId)
                val outputFileName = fileName.substringBeforeLast('.') + "_${uniqueId}.$extension"
                val outputPath = File(requireContext().cacheDir, outputFileName).absolutePath
                
                val task = ConversionTask(uri, inputPath, outputPath, fileName, outputFormat)
                conversionThumbnailAdapter.confirmOverwrite(uri)
                appendLog("Overwrite confirmed: $fileName\n")
                conversionQueue?.addTaskDynamic(task)
            } catch (e: Exception) {
                Log.e(tag, "Error preparing task for $uri", e)
                conversionThumbnailAdapter.markError(uri, e.message ?: "Unknown error")
                appendLog("✗ ${rawFile.name}: ${e.message ?: "Unknown error"}")
            }
        }

        // Only create initial tasks for files that don't need confirmation
        val tasks = readyToConvert.mapNotNull { rawFile ->
            try {
                val fileName = rawFile.name
                val uniqueId = UUID.randomUUID().toString().take(8)
                val inputPath = copyUriToCache(rawFile.uri, fileName, uniqueId)
                val outputFileName = fileName.substringBeforeLast('.') + "_${uniqueId}.$extension"
                val outputPath = File(requireContext().cacheDir, outputFileName).absolutePath

                ConversionTask(rawFile.uri, inputPath, outputPath, fileName, outputFormat)
            } catch (e: Exception) {
                Log.e(tag, "Error preparing task for ${rawFile.uri}", e)
                // Mark as error in thumbnail grid
                conversionThumbnailAdapter.markError(rawFile.uri, e.message ?: "Unknown error")
                appendLog("✗ ${rawFile.name}: ${e.message ?: "Unknown error"}")
                null
            }
        }

        // Read JPEG settings from preferences
        val jpegQuality = JpegSettingsDialog.getJpegQuality(requireContext())
        val jpegChroma = JpegSettingsDialog.getJpegChroma(requireContext())
        val jpegOptimize = JpegSettingsDialog.getJpegOptimize(requireContext())

        conversionQueue = ConversionQueue(
            converter = converter,
            onTaskStarting = { task, _, _ ->
                activity?.runOnUiThread {
                    conversionThumbnailAdapter.markInProgress(task.inputUri)
                    appendLog("Converting: ${task.fileName}...")
                }
            },
            onTaskComplete = { result ->
                activity?.runOnUiThread {
                    // Update progress bar on completion
                    val completed = conversionThumbnailAdapter.getCompletedCount() + 1
                    binding.conversionProgressBar.progress = completed
                    binding.conversionStatus.text = "$completed/$fileCount"
                    
                    // Clean up cache files (both input and output)
                    val inputFile = File(result.task.inputPath)
                    val outputFile = File(result.task.outputPath)
                    
                    if (result.success) {
                        // Use original filename (without UUID) for public storage
                        val originalBaseName = result.task.fileName.substringBeforeLast('.')
                        val outputExtension = if (result.task.outputFormat == OutputFormat.DNG) "dng" else "jpg"
                        val publicFileName = "$originalBaseName.$outputExtension"
                        
                        val saveResult = saveToPublicStorage(outputFile, result.task.outputFormat, publicFileName)
                        outputFile.delete()
                        inputFile.delete()  // Clean up cached input file
                        
                        when (saveResult) {
                            is SaveResult.Success -> {
                                // Mark success in thumbnail grid
                                conversionThumbnailAdapter.markSuccess(result.task.inputUri)
                                Log.d(tag, "✓ ${result.task.fileName}")
                                appendLog("✓ ${result.task.fileName}")
                            }
                            is SaveResult.SuccessWithWarning -> {
                                // Mark success but log warning
                                conversionThumbnailAdapter.markSuccess(result.task.inputUri)
                                conversionWarningCount++
                                Log.w(tag, "⚠ ${result.task.fileName}: ${saveResult.warning}")
                                appendLog("⚠ ${result.task.fileName}: ${saveResult.warning}")
                            }
                            is SaveResult.Failed -> {
                                // Mark error in thumbnail grid
                                conversionThumbnailAdapter.markError(result.task.inputUri, "Failed to save file")
                                Log.e(tag, "✗ ${result.task.fileName}: Failed to save file")
                                appendLog("✗ ${result.task.fileName}: Failed to save file")
                            }
                        }
                        
                        // Immediately update the RawFileItem's conversion status (even with warning, file was saved)
                        if (saveResult !is SaveResult.Failed) {
                            updateItemConversionStatus(result.task.inputUri, result.task.outputFormat)
                        }
                    } else {
                        // Clean up cache files even on failure
                        outputFile.delete()
                        inputFile.delete()
                        
                        // Mark error in thumbnail grid
                        conversionThumbnailAdapter.markError(result.task.inputUri, result.errorMessage)
                        Log.e(tag, "✗ ${result.task.fileName}: ${result.errorMessage}")
                        appendLog("✗ ${result.task.fileName}: ${result.errorMessage}")
                    }
                }
            },
            onAllComplete = { successful, failed ->
                activity?.runOnUiThread {
                    val pendingOverwrites = conversionThumbnailAdapter.getPendingOverwriteCount()
                    
                    if (pendingOverwrites > 0) {
                        // Some files still need overwrite confirmation
                        val message = if (successful + failed > 0) {
                            "Converted: $successful, Failed: $failed. $pendingOverwrites awaiting confirmation."
                        } else {
                            "$pendingOverwrites file(s) awaiting overwrite confirmation"
                        }
                        binding.conversionStatus.text = message
                        appendLog("\n$message")
                        // Don't enable Done or auto-navigate yet
                    } else {
                        // Check if there were any warnings
                        val hasWarnings = conversionWarningCount > 0
                        
                        val message = if (hasWarnings) {
                            if (conversionWarningCount == 1) {
                                "Completed with 1 warning. See log for details."
                            } else {
                                "Completed with $conversionWarningCount warnings. See log for details."
                            }
                        } else {
                            getString(R.string.conversion_complete, successful, failed)
                        }
                        
                        binding.conversionStatus.text = message
                        binding.btnDone.isEnabled = true
                        appendLog("\n" + getString(R.string.conversion_complete, successful, failed))
                        
                        // Change progress bar color to orange if there were warnings
                        if (hasWarnings) {
                            binding.conversionProgressBar.progressTintList = 
                                android.content.res.ColorStateList.valueOf(
                                    ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                                )
                        }
                        
                        // Clear selection (status already updated per-item during conversion)
                        adapter.clearSelection()
                        
                        // Refresh the list to show updated badges
                        applyFilter()
                        
                        // Notify gallery to refresh
                        (activity as? MainActivity)?.refreshGallery()
                        
                        // Track if conversion completed successfully (for checkbox trigger)
                        // Don't count as successful if there were warnings - no auto-navigate
                        conversionCompletedSuccessfully = (failed == 0 && successful > 0 && !hasWarnings)
                        
                        // Auto-navigate if checkbox is checked AND all conversions succeeded without warnings
                        if (binding.checkAutoNavigate.isChecked && conversionCompletedSuccessfully) {
                            startAutoNavigateCountdown()
                        }
                    }
                }
            },
            jpegQuality = jpegQuality,
            jpegChroma = jpegChroma,
            jpegOptimize = jpegOptimize
        )

        conversionQueue?.addTasks(tasks)
        conversionQueue?.start()
    }
    
    /**
     * Update the conversion status of a specific item after successful conversion.
     * This provides immediate feedback without requiring a full file reload.
     */
    private fun updateItemConversionStatus(uri: Uri, format: OutputFormat) {
        allRawFiles.find { it.uri == uri }?.let { item ->
            when (format) {
                OutputFormat.DNG -> item.isConvertedToDng = true
                OutputFormat.JPEG -> item.isConvertedToJpeg = true
            }
            Log.d(tag, "Updated status for ${item.name}: DNG=${item.isConvertedToDng}, JPEG=${item.isConvertedToJpeg}")
        }
    }

    private fun showConversionOverlay() {
        binding.pickerContent.visibility = View.GONE
        binding.conversionOverlay.visibility = View.VISIBLE
        conversionThumbnailAdapter.clear()
        cancelCountdown()
        // Clear log and reset view state
        logMessages.clear()
        binding.conversionLogText.text = ""
        updateLogToggleView()
        // Reset progress bar color to default (primary color)
        binding.conversionProgressBar.progressTintList = null
    }

    private fun showPickerContent() {
        binding.conversionOverlay.visibility = View.GONE
        binding.pickerContent.visibility = View.VISIBLE
        cancelCountdown()
        conversionCompletedSuccessfully = false  // Reset flag when leaving conversion overlay
        binding.btnDone.text = getString(R.string.done)
    }
    
    /**
     * Clear the conversion overlay if visible. Called when user navigates away from this tab.
     */
    fun clearConversionOverlayIfDone() {
        if (_binding == null) return
        // Only clear if done button is enabled (conversion is complete)
        if (binding.conversionOverlay.visibility == View.VISIBLE && binding.btnDone.isEnabled) {
            showPickerContent()
        }
    }
    
    private fun startAutoNavigateCountdown() {
        countdownJob?.cancel()
        
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val countdownSeconds = prefs.getInt(KEY_AUTONAV_TIMEOUT, DEFAULT_COUNTDOWN_SECONDS)
        
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            if (countdownSeconds == 0) {
                // Immediate navigation - no countdown
                (activity as? MainActivity)?.navigateToGallery(lastConversionFormat)
                showPickerContent()
            } else {
                for (seconds in countdownSeconds downTo 1) {
                    binding.btnDone.text = getString(R.string.done_countdown, seconds)
                    delay(1000)
                }
                // Time's up - navigate to gallery
                (activity as? MainActivity)?.navigateToGallery(lastConversionFormat)
                showPickerContent()
            }
        }
    }
    
    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * Copy a URI to cache with a unique filename to prevent parallel access conflicts.
     * @param uri The source URI to copy
     * @param fileName Original filename (used for extension)
     * @param uniqueId Unique identifier to prevent filename collisions
     * @return Path to the cached file
     */
    private fun copyUriToCache(uri: Uri, fileName: String, uniqueId: String): String {
        val extension = fileName.substringAfterLast('.', "")
        val baseName = fileName.substringBeforeLast('.')
        val uniqueFileName = "${baseName}_${uniqueId}.$extension"
        val cacheFile = File(requireContext().cacheDir, uniqueFileName)
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile.absolutePath
    }

    private fun saveToPublicStorage(sourceFile: File, outputFormat: OutputFormat = OutputFormat.DNG, displayName: String = sourceFile.name): SaveResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(sourceFile, outputFormat, displayName)
        } else {
            saveToPublicDirectory(sourceFile, outputFormat, displayName)
        }
    }

    private fun saveToMediaStore(sourceFile: File, outputFormat: OutputFormat, displayName: String): SaveResult {
        val mimeType = when (outputFormat) {
            OutputFormat.DNG -> "image/x-adobe-dng"
            OutputFormat.JPEG -> "image/jpeg"
        }
        
        val relativePath = when (outputFormat) {
            OutputFormat.DNG -> "${Environment.DIRECTORY_PICTURES}/Raw2DNG"
            OutputFormat.JPEG -> "${Environment.DIRECTORY_PICTURES}/Raw2DNG/JPEG"
        }
        
        // Track if we couldn't overwrite an existing file
        var couldNotOverwrite = false
        
        // Check for existing file with the same name and try to delete/overwrite it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findExistingFile(displayName, relativePath)?.let { existingUri ->
                // Try to delete the existing file first
                var deleted = false
                try {
                    val rowsDeleted = requireContext().contentResolver.delete(existingUri, null, null)
                    deleted = rowsDeleted > 0
                    if (deleted) {
                        Log.d(tag, "Deleted existing file before overwrite: $displayName")
                    }
                } catch (e: android.app.RecoverableSecurityException) {
                    // File was created by a different app/installation - can't delete without user consent
                    Log.d(tag, "Cannot delete file (not owner): $displayName")
                } catch (e: SecurityException) {
                    // Other security exception
                    Log.d(tag, "Security exception deleting file: $displayName")
                }
                
                // If we couldn't delete, try to overwrite in place
                if (!deleted) {
                    try {
                        requireContext().contentResolver.openOutputStream(existingUri, "wt")?.use { outputStream ->
                            sourceFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        Log.d(tag, "Overwrote existing file: $displayName")
                        return SaveResult.Success(existingUri)
                    } catch (e: android.app.RecoverableSecurityException) {
                        // Also can't overwrite - file created by different app
                        Log.w(tag, "Cannot overwrite '$displayName' - file owned by another app. Delete from Gallery first.")
                        couldNotOverwrite = true
                    } catch (e: SecurityException) {
                        Log.w(tag, "Security exception overwriting '$displayName'. Delete from Gallery first.")
                        couldNotOverwrite = true
                    }
                    // Fall through to create new file (will get "(1)" suffix from MediaStore)
                }
            }
        }
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        return uri?.let {
            try {
                requireContext().contentResolver.openOutputStream(it)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                requireContext().contentResolver.update(it, contentValues, null, null)

                Log.d(tag, "Saved to MediaStore: $it")
                
                if (couldNotOverwrite) {
                    SaveResult.SuccessWithWarning(it, "Could not overwrite '$displayName' - file saved with different name. Delete old file from Gallery.")
                } else {
                    SaveResult.Success(it)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error saving to MediaStore", e)
                requireContext().contentResolver.delete(it, null, null)
                SaveResult.Failed
            }
        } ?: SaveResult.Failed
    }

    private fun saveToPublicDirectory(sourceFile: File, outputFormat: OutputFormat, displayName: String): SaveResult {
        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val subDir = when (outputFormat) {
                OutputFormat.DNG -> "Raw2DNG"
                OutputFormat.JPEG -> "Raw2DNG/JPEG"
            }
            val outputDir = File(picturesDir, subDir)
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val destFile = File(outputDir, displayName)
            sourceFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val mimeType = when (outputFormat) {
                OutputFormat.DNG -> "image/x-adobe-dng"
                OutputFormat.JPEG -> "image/jpeg"
            }
            
            MediaScannerConnection.scanFile(
                requireContext(),
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d(tag, "MediaScanner scanned: $path -> $uri")
            }

            SaveResult.Success(Uri.fromFile(destFile))
        } catch (e: Exception) {
            Log.e(tag, "Error saving to public directory", e)
            SaveResult.Failed
        }
    }

    /**
     * Find an existing file in MediaStore by display name and relative path.
     * Returns the Uri if found, null otherwise.
     */
    private fun findExistingFile(displayName: String, relativePath: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        // MediaStore stores relative path with trailing slash
        val selectionArgs = arrayOf(displayName, "$relativePath/")
        
        val cursor = requireContext().contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        
        return null
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog.newInstance()
        dialog.setOnSettingsSavedListener(object : SettingsDialog.OnSettingsSavedListener {
            override fun onSettingsSaved() {
                // Reload the file list with the new RAW type settings
                loadRawFiles()
            }
        })
        dialog.show(childFragmentManager, SettingsDialog.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        conversionQueue?.cancel()
        cancelCountdown()
        _binding = null
    }

    companion object {
        fun newInstance(): RawFilePickerFragment {
            return RawFilePickerFragment()
        }
    }
}
