package com.raw2dng

import android.Manifest
import android.content.ContentValues
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.raw2dng.databinding.FragmentRawPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RawFilePickerFragment : Fragment() {

    private var _binding: FragmentRawPickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: RawFileAdapter
    private var allRawFiles = mutableListOf<RawFileItem>()
    private var currentFilter: FileFilter = FileFilter.NOT_DNG
    private var hadPermissionsLastCheck = false
    private var lastConversionFormat: OutputFormat = OutputFormat.DNG

    private val tag = "RawFilePicker"

    // Filter options for the file picker
    enum class FileFilter {
        ALL,
        NOT_DNG,
        NOT_JPEG
    }

    // Conversion
    private val converter = DNGConverter()
    private var conversionQueue: ConversionQueue? = null

    // Supported RAW extensions (case-insensitive)
    private val rawExtensions = setOf(
        "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "pef",
        "rw2", "3fr", "iiq", "dcr", "k25", "kdc", "erf", "mef", "mos", "raf"
    )

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
     */
    private fun refreshConversionStatus() {
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
                applyFilter()
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
            onThumbnailClick = { item ->
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
            (activity as? MainActivity)?.navigateToGallery(lastConversionFormat)
            // Reset the view for next time
            showPickerContent()
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

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn) ?: continue
                val size = it.getLong(sizeColumn)
                val date = it.getLong(dateColumn)

                // Check if it's a RAW file by extension
                val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                if (ext !in rawExtensions) continue

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
        return when (currentFilter) {
            FileFilter.ALL -> allRawFiles
            FileFilter.NOT_DNG -> allRawFiles.filter { !it.isConvertedToDng }
            FileFilter.NOT_JPEG -> allRawFiles.filter { !it.isConvertedToJpeg }
        }
    }

    private fun applyFilter() {
        val filtered = getFilteredFiles()
        adapter.submitList(filtered)
        
        val totalCount = allRawFiles.size
        val dngCount = allRawFiles.count { it.isConvertedToDng }
        val jpegCount = allRawFiles.count { it.isConvertedToJpeg }
        val showingCount = filtered.size
        
        binding.selectionCount.text = when (currentFilter) {
            FileFilter.ALL -> "$totalCount files ($dngCount DNG, $jpegCount JPEG converted)"
            FileFilter.NOT_DNG -> "$showingCount not converted to DNG ($dngCount already DNG)"
            FileFilter.NOT_JPEG -> "$showingCount not converted to JPEG ($jpegCount already JPEG)"
        }

        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val selectedCount = adapter.getSelectedItems().size
        val totalVisible = getFilteredFiles().size

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

        binding.btnSelectAll.text = if (selectedCount == totalVisible && totalVisible > 0) {
            "Deselect All"
        } else {
            getString(R.string.select_all)
        }
    }

    private fun showImagePreview(item: RawFileItem) {
        // Get the currently visible (filtered) list of files
        val visibleFiles = getFilteredFiles()
        
        // Build lists of URIs and filenames
        val uris = ArrayList(visibleFiles.map { it.uri })
        val fileNames = ArrayList(visibleFiles.map { it.name })
        
        // Get currently selected URIs
        val selectedUris = ArrayList(adapter.getSelectedItems().map { it.uri })
        
        // Find the position of the clicked item in the filtered list
        val position = visibleFiles.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        
        val previewDialog = ImagePreviewDialog.newInstance(uris, fileNames, position, selectedUris)
        
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

        // Handle convert request from preview dialog - default to DNG
        previewDialog.setOnConvertRequestedListener(object : ImagePreviewDialog.OnConvertRequestedListener {
            override fun onConvertRequested() {
                val selected = adapter.getSelectedItems()
                if (selected.isNotEmpty()) {
                    previewDialog.dismiss()
                    startConversion(selected, OutputFormat.DNG)
                }
            }
        })
        
        previewDialog.show(childFragmentManager, "image_preview")
    }

    // === Conversion Logic ===

    private fun startConversion(selectedFiles: List<RawFileItem>, outputFormat: OutputFormat) {
        if (selectedFiles.isEmpty()) return
        
        // Track the format for navigation after completion
        lastConversionFormat = outputFormat

        // Show conversion overlay
        showConversionOverlay()

        binding.conversionProgressBar.max = selectedFiles.size
        binding.conversionProgressBar.progress = 0
        binding.btnDone.isEnabled = false
        
        val formatName = if (outputFormat == OutputFormat.DNG) "DNG" else "JPEG"
        val extension = if (outputFormat == OutputFormat.DNG) "dng" else "jpg"

        logMessage("Starting $formatName conversion of ${selectedFiles.size} file(s)...")
        logMessage("Output directory: Pictures/Raw2DNG")

        val tasks = selectedFiles.mapNotNull { rawFile ->
            try {
                val fileName = rawFile.name
                val inputPath = copyUriToCache(rawFile.uri, fileName)
                val outputFileName = fileName.substringBeforeLast('.') + ".$extension"
                val outputPath = File(requireContext().cacheDir, outputFileName).absolutePath

                ConversionTask(rawFile.uri, inputPath, outputPath, fileName, outputFormat)
            } catch (e: Exception) {
                Log.e(tag, "Error preparing task for ${rawFile.uri}", e)
                logMessage("Error: ${e.message}")
                null
            }
        }

        conversionQueue = ConversionQueue(
            converter = converter,
            onProgress = { current, total ->
                activity?.runOnUiThread {
                    binding.conversionProgressBar.progress = current
                    binding.conversionStatus.text = getString(R.string.converting, current, total)
                }
            },
            onTaskComplete = { result ->
                activity?.runOnUiThread {
                    if (result.success) {
                        val outputFile = File(result.task.outputPath)
                        val finalUri = saveToPublicStorage(outputFile, result.task.outputFormat)
                        outputFile.delete()
                        
                        val ext = if (result.task.outputFormat == OutputFormat.DNG) "dng" else "jpg"

                        if (finalUri != null) {
                            val outputName = result.task.fileName.substringBeforeLast('.') + ".$ext"
                            logMessage("✓ ${result.task.fileName} -> $outputName")
                        } else {
                            logMessage("✓ ${result.task.fileName} (warning: couldn't add to gallery)")
                        }
                    } else {
                        logMessage("✗ ${result.task.fileName}: ${result.errorMessage}")
                    }
                }
            },
            onAllComplete = { successful, failed ->
                activity?.runOnUiThread {
                    val message = "Completed: $successful successful, $failed failed"
                    binding.conversionStatus.text = message
                    logMessage(message)
                    logMessage("Output files saved to: Pictures/Raw2DNG")

                    binding.btnDone.isEnabled = true
                    
                    // Clear selection and reload files to update converted status
                    adapter.clearSelection()
                    loadRawFiles()
                    
                    // Notify gallery to refresh
                    (activity as? MainActivity)?.refreshGallery()
                }
            }
        )

        conversionQueue?.addTasks(tasks)
        conversionQueue?.start()
    }

    private fun showConversionOverlay() {
        binding.pickerContent.visibility = View.GONE
        binding.conversionOverlay.visibility = View.VISIBLE
        binding.conversionLogText.text = ""
    }

    private fun showPickerContent() {
        binding.conversionOverlay.visibility = View.GONE
        binding.pickerContent.visibility = View.VISIBLE
    }

    private fun logMessage(message: String) {
        Log.d(tag, message)
        activity?.runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val currentLog = binding.conversionLogText.text.toString()
            binding.conversionLogText.text = if (currentLog.isEmpty()) {
                "[$timestamp] $message"
            } else {
                "$currentLog\n[$timestamp] $message"
            }

            binding.conversionLogScrollView.post {
                binding.conversionLogScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun copyUriToCache(uri: Uri, fileName: String): String {
        val cacheFile = File(requireContext().cacheDir, fileName)
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile.absolutePath
    }

    private fun saveToPublicStorage(sourceFile: File, outputFormat: OutputFormat = OutputFormat.DNG): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(sourceFile, outputFormat)
        } else {
            saveToPublicDirectory(sourceFile, outputFormat)
        }
    }

    private fun saveToMediaStore(sourceFile: File, outputFormat: OutputFormat): Uri? {
        val mimeType = when (outputFormat) {
            OutputFormat.DNG -> "image/x-adobe-dng"
            OutputFormat.JPEG -> "image/jpeg"
        }
        
        val relativePath = when (outputFormat) {
            OutputFormat.DNG -> "${Environment.DIRECTORY_PICTURES}/Raw2DNG"
            OutputFormat.JPEG -> "${Environment.DIRECTORY_PICTURES}/Raw2DNG/JPEG"
        }
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.name)
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
                it
            } catch (e: Exception) {
                Log.e(tag, "Error saving to MediaStore", e)
                requireContext().contentResolver.delete(it, null, null)
                null
            }
        }
    }

    private fun saveToPublicDirectory(sourceFile: File, outputFormat: OutputFormat): Uri? {
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

            val destFile = File(outputDir, sourceFile.name)
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

            Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(tag, "Error saving to public directory", e)
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        conversionQueue?.cancel()
        _binding = null
    }

    companion object {
        fun newInstance(): RawFilePickerFragment {
            return RawFilePickerFragment()
        }
    }
}
