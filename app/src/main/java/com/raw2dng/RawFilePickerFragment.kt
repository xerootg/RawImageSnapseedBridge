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
    private var hideConverted = true
    private var hadPermissionsLastCheck = false

    private val tag = "RawFilePicker"

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
        }
        hadPermissionsLastCheck = hasPermissions
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

        binding.chipShowAll.setOnClickListener {
            hideConverted = false
            binding.chipShowAll.isChecked = true
            binding.chipHideConverted.isChecked = false
            applyFilter()
        }

        binding.chipHideConverted.setOnClickListener {
            hideConverted = true
            binding.chipShowAll.isChecked = false
            binding.chipHideConverted.isChecked = true
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

        binding.btnConvertSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                startConversion(selected)
            }
        }

        // Done button navigates to Gallery tab
        binding.btnDone.setOnClickListener {
            (activity as? MainActivity)?.navigateToGallery()
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

                val isConverted = ConvertedFilesHelper.isConverted(name)

                files.add(RawFileItem(uri, name, size, date, "", isConverted))
            }
        }

        return files
    }

    private fun getFilteredFiles(): List<RawFileItem> {
        return if (hideConverted) {
            allRawFiles.filter { !it.isConverted }
        } else {
            allRawFiles
        }
    }

    private fun applyFilter() {
        val filtered = getFilteredFiles()
        adapter.submitList(filtered)
        
        val totalCount = allRawFiles.size
        val convertedCount = allRawFiles.count { it.isConverted }
        val showingCount = filtered.size
        
        binding.selectionCount.text = if (hideConverted) {
            "$showingCount unconverted files ($convertedCount already converted)"
        } else {
            "$totalCount files found ($convertedCount already converted)"
        }

        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val selectedCount = adapter.getSelectedItems().size
        val totalVisible = getFilteredFiles().size

        binding.btnConvertSelected.isEnabled = selectedCount > 0
        binding.btnConvertSelected.text = if (selectedCount > 0) {
            "Convert ($selectedCount)"
        } else {
            getString(R.string.convert_selected)
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

        // Handle convert request from preview dialog
        previewDialog.setOnConvertRequestedListener(object : ImagePreviewDialog.OnConvertRequestedListener {
            override fun onConvertRequested() {
                val selected = adapter.getSelectedItems()
                if (selected.isNotEmpty()) {
                    previewDialog.dismiss()
                    startConversion(selected)
                }
            }
        })
        
        previewDialog.show(childFragmentManager, "image_preview")
    }

    // === Conversion Logic ===

    private fun startConversion(selectedFiles: List<RawFileItem>) {
        if (selectedFiles.isEmpty()) return

        // Show conversion overlay
        showConversionOverlay()

        binding.conversionProgressBar.max = selectedFiles.size
        binding.conversionProgressBar.progress = 0
        binding.btnDone.isEnabled = false

        logMessage("Starting conversion of ${selectedFiles.size} file(s)...")
        logMessage("Output directory: Pictures/Raw2DNG")

        val tasks = selectedFiles.mapNotNull { rawFile ->
            try {
                val fileName = rawFile.name
                val inputPath = copyUriToCache(rawFile.uri, fileName)
                val outputFileName = fileName.substringBeforeLast('.') + ".dng"
                val outputPath = File(requireContext().cacheDir, outputFileName).absolutePath

                ConversionTask(rawFile.uri, inputPath, outputPath, fileName)
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
                        val finalUri = saveToPublicStorage(outputFile)
                        outputFile.delete()

                        if (finalUri != null) {
                            val outputName = result.task.fileName.substringBeforeLast('.') + ".dng"
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

    private fun saveToPublicStorage(sourceFile: File): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(sourceFile)
        } else {
            saveToPublicDirectory(sourceFile)
        }
    }

    private fun saveToMediaStore(sourceFile: File): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Raw2DNG")
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

    private fun saveToPublicDirectory(sourceFile: File): Uri? {
        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val outputDir = File(picturesDir, "Raw2DNG")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val destFile = File(outputDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            MediaScannerConnection.scanFile(
                requireContext(),
                arrayOf(destFile.absolutePath),
                arrayOf("image/x-adobe-dng")
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
