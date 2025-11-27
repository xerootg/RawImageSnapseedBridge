package com.raw2dng

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.raw2dng.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Filter options for gallery view.
 */
enum class GalleryFilter {
    ALL,
    DNG_ONLY,
    JPEG_ONLY
}

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: GalleryAdapter
    private val tag = "GalleryFragment"
    
    private var currentFilter: GalleryFilter = GalleryFilter.DNG_ONLY

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            loadImages()
        } else {
            binding.emptyText.text = "Storage permission required to view images"
            binding.emptyText.visibility = View.VISIBLE
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), "Files deleted successfully", Toast.LENGTH_SHORT).show()
            // Delay the refresh to ensure files are actually deleted from disk
            // MediaStore deletion is asynchronous
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                (activity as? MainActivity)?.refreshConvertTab()
            }
        } else {
            Toast.makeText(requireContext(), "Deletion cancelled", Toast.LENGTH_SHORT).show()
        }
        loadImages()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupBackPressHandler()
        checkPermissionsAndLoad()
    }
    
    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (adapter.isMultiSelectMode) {
                        // Exit multi-select mode
                        adapter.clearSelection()
                    } else {
                        // Let the activity handle it (switch to Convert tab)
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }

    private fun setupUI() {
        adapter = GalleryAdapter(
            onItemClick = { item ->
                openImageWith(item)
            },
            onSelectionChanged = { count ->
                updateSelectionUI(count)
            },
            onDoubleTap = { item ->
                previewSingleImage(item)
            }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        binding.clearFolderButton.setOnClickListener {
            showClearFolderConfirmation()
        }
        
        binding.openSelectedButton.setOnClickListener {
            openSelectedImages()
        }
        
        binding.previewSelectedButton.setOnClickListener {
            previewSelectedImages()
        }
        
        binding.deleteSelectedButton.setOnClickListener {
            deleteSelectedImages()
        }
        
        binding.regenerateSelectedButton.setOnClickListener {
            showRegenerateDialog()
        }
        
        // Settings button removed - settings is now a tab
        
        // Setup filter chips
        setupFilterChips()
    }
    
    private fun updateSelectionUI(count: Int) {
        if (count > 0) {
            binding.selectionButtonsContainer.visibility = View.VISIBLE
            
            // Calculate total size of selected items
            val selectedItems = adapter.getSelectedItems()
            val totalSize = selectedItems.sumOf { it.fileSize }
            val formattedSize = formatFileSize(totalSize)
            binding.selectionSummary.text = getString(R.string.selection_summary, count, formattedSize)
        } else {
            binding.selectionButtonsContainer.visibility = View.GONE
        }
    }
    
    private var currentPreviewDialog: ImagePreviewDialog? = null
    
    private fun previewSingleImage(item: GalleryItem) {
        val allItems = adapter.getAllItems()
        if (allItems.isEmpty()) return
        
        // Find the position of the double-tapped item
        val initialPosition = allItems.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        
        // Build URIs, filenames, file types, and file sizes for all items
        val uris = ArrayList(allItems.map { it.uri })
        val fileNames = ArrayList(allItems.map { it.name })
        val fileSizes = ArrayList(allItems.map { it.fileSize })
        val selectedItems = adapter.getSelectedItems()
        val selectedUris = ArrayList(selectedItems.map { it.uri })
        val fileTypes = ArrayList(allItems.map { galleryItem ->
            val ext = galleryItem.name.substringAfterLast('.', "").uppercase()
            when (ext) {
                "DNG" -> "DNG"
                "JPG", "JPEG" -> "JPEG"
                else -> ext
            }
        })
        
        // Create preview dialog in gallery mode
        val dialog = ImagePreviewDialog.newInstance(
            uris = uris,
            fileNames = fileNames,
            initialPosition = initialPosition,
            selectedUris = selectedUris,
            dngStatus = arrayListOf(),  // Not needed for gallery mode
            jpegStatus = arrayListOf(),  // Not needed for gallery mode
            fileTypes = fileTypes,
            fileSizes = fileSizes,
            mode = PreviewMode.GALLERY_VIEW
        )
        currentPreviewDialog = dialog
        
        dialog.show(childFragmentManager, "gallery_preview")
        
        // Use fragment lifecycle to detect when dialog is dismissed
        childFragmentManager.executePendingTransactions()
        dialog.dialog?.setOnDismissListener {
            // Sync selection state from dialog back to adapter
            currentPreviewDialog?.let { previewDialog ->
                val finalSelection = previewDialog.getSelectedUris()
                adapter.setSelectionFromUris(finalSelection)
            }
            currentPreviewDialog = null
        }
    }
    
    private fun previewSelectedImages() {
        val allItems = adapter.getAllItems()
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty() || allItems.isEmpty()) return
        
        // Find the position of the first selected item
        val firstSelectedUri = selectedItems.first().uri
        val initialPosition = allItems.indexOfFirst { it.uri == firstSelectedUri }.coerceAtLeast(0)
        
        // Build URIs, filenames, file types, and file sizes for all items
        val uris = ArrayList(allItems.map { it.uri })
        val fileNames = ArrayList(allItems.map { it.name })
        val fileSizes = ArrayList(allItems.map { it.fileSize })
        val selectedUris = ArrayList(selectedItems.map { it.uri })
        val fileTypes = ArrayList(allItems.map { galleryItem ->
            val ext = galleryItem.name.substringAfterLast('.', "").uppercase()
            when (ext) {
                "DNG" -> "DNG"
                "JPG", "JPEG" -> "JPEG"
                else -> ext
            }
        })
        
        // Create preview dialog in gallery mode
        val dialog = ImagePreviewDialog.newInstance(
            uris = uris,
            fileNames = fileNames,
            initialPosition = initialPosition,
            selectedUris = selectedUris,
            dngStatus = arrayListOf(),  // Not needed for gallery mode
            jpegStatus = arrayListOf(),  // Not needed for gallery mode
            fileTypes = fileTypes,
            fileSizes = fileSizes,
            mode = PreviewMode.GALLERY_VIEW
        )
        currentPreviewDialog = dialog
        
        dialog.show(childFragmentManager, "gallery_preview")
        
        // Use fragment lifecycle to detect when dialog is dismissed
        childFragmentManager.executePendingTransactions()
        dialog.dialog?.setOnDismissListener {
            // Sync selection state from dialog back to adapter
            currentPreviewDialog?.let { previewDialog ->
                val finalSelection = previewDialog.getSelectedUris()
                adapter.setSelectionFromUris(finalSelection)
            }
            currentPreviewDialog = null
        }
    }
    
    private fun deleteSelectedImages() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) return
        
        val urisToDelete = selectedItems.map { it.uri }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val deleteRequest = MediaStore.createDeleteRequest(
                    requireContext().contentResolver,
                    urisToDelete
                )
                deleteRequestLauncher.launch(
                    IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                )
                // Clear selection - adapter will be refreshed after deletion via deleteRequestLauncher
                adapter.clearSelection()
            } catch (e: Exception) {
                Log.e(tag, "Failed to create delete request", e)
                Toast.makeText(requireContext(), "Failed to delete files: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // For older Android versions, show confirmation dialog first
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete ${selectedItems.size} file(s)?")
                .setMessage("This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val deletedCount = withContext(Dispatchers.IO) {
                            var count = 0
                            selectedItems.forEach { item ->
                                try {
                                    val result = requireContext().contentResolver.delete(item.uri, null, null)
                                    if (result > 0) count++
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to delete ${item.name}", e)
                                }
                            }
                            count
                        }
                        Toast.makeText(requireContext(), "Deleted $deletedCount file(s)", Toast.LENGTH_SHORT).show()
                        adapter.clearSelection()
                        loadImages()
                        (activity as? MainActivity)?.refreshConvertTab()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun openSelectedImages() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) return
        
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                val uris = ArrayList(selectedItems.map { it.uri })
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooser = Intent.createChooser(intent, getString(R.string.open_with)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
            
            // Clear selection after opening
            adapter.clearSelection()
        } catch (e: Exception) {
            Log.e(tag, "Error opening images", e)
            Toast.makeText(requireContext(), "Failed to open images", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupFilterChips() {
        // Set initial checked state (DNG is default)
        binding.chipGalleryDng.isChecked = true
        
        binding.chipGalleryAll.setOnClickListener {
            if (currentFilter != GalleryFilter.ALL) {
                currentFilter = GalleryFilter.ALL
                updateFilterChipStates()
                // ALL is less restrictive than DNG_ONLY or JPEG_ONLY, so keep selections
                loadImages()
            }
        }
        
        binding.chipGalleryDng.setOnClickListener {
            if (currentFilter != GalleryFilter.DNG_ONLY) {
                currentFilter = GalleryFilter.DNG_ONLY
                updateFilterChipStates()
                // DNG is more restrictive than ALL, or different type than JPEG - clear selections
                clearSelectionOnFilterChange()
                loadImages()
            }
        }
        
        binding.chipGalleryJpeg.setOnClickListener {
            if (currentFilter != GalleryFilter.JPEG_ONLY) {
                currentFilter = GalleryFilter.JPEG_ONLY
                updateFilterChipStates()
                // JPEG is more restrictive than ALL, or different type than DNG - clear selections
                clearSelectionOnFilterChange()
                loadImages()
            }
        }
    }
    
    /**
     * Clear any active selection when filter changes to a more restrictive filter.
     * Selections are preserved when going to ALL (less restrictive).
     * Selections are cleared when going to DNG_ONLY or JPEG_ONLY (more restrictive or different type).
     */
    private fun clearSelectionOnFilterChange() {
        adapter.clearSelection()
        updateSelectionUI(0)
    }
    
    private fun updateFilterChipStates() {
        binding.chipGalleryAll.isChecked = currentFilter == GalleryFilter.ALL
        binding.chipGalleryDng.isChecked = currentFilter == GalleryFilter.DNG_ONLY
        binding.chipGalleryJpeg.isChecked = currentFilter == GalleryFilter.JPEG_ONLY
    }

    private fun checkPermissionsAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            loadImages()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun loadImages() {
        // Show loading state
        binding.emptyText.text = "Loading..."
        binding.emptyText.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                queryImages()
            }
            
            if (_binding != null) {
                adapter.submitList(items)
                updateUI(items.size)
            }
        }
    }
    
    private fun queryImages(): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        
        // Always use direct file access - more reliable for our use case
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        val jpegDir = File(raw2dngDir, "JPEG")
        
        Log.d(tag, "Looking for files in: ${raw2dngDir.absolutePath}")
        Log.d(tag, "Directory exists: ${raw2dngDir.exists()}")
        Log.d(tag, "Current filter: $currentFilter")
        
        // Collect DNG files if needed
        if (currentFilter == GalleryFilter.ALL || currentFilter == GalleryFilter.DNG_ONLY) {
            if (raw2dngDir.exists() && raw2dngDir.isDirectory) {
                val files = raw2dngDir.listFiles()
                files?.filter { it.isFile && it.extension.lowercase() == "dng" }
                    ?.forEach { file ->
                        Log.d(tag, "Adding DNG file: ${file.name}")
                        
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            getContentUriForFile(file) ?: Uri.fromFile(file)
                        } else {
                            Uri.fromFile(file)
                        }
                        
                        items.add(
                            GalleryItem(
                                id = file.absolutePath.hashCode().toLong(),
                                uri = uri,
                                name = file.name,
                                dateModified = file.lastModified(),
                                fileSize = file.length()
                            )
                        )
                    }
            }
        }
        
        // Collect JPEG files if needed
        if (currentFilter == GalleryFilter.ALL || currentFilter == GalleryFilter.JPEG_ONLY) {
            if (jpegDir.exists() && jpegDir.isDirectory) {
                val files = jpegDir.listFiles()
                files?.filter { it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "jpeg") }
                    ?.forEach { file ->
                        Log.d(tag, "Adding JPEG file: ${file.name}")
                        
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            getContentUriForFile(file) ?: Uri.fromFile(file)
                        } else {
                            Uri.fromFile(file)
                        }
                        
                        items.add(
                            GalleryItem(
                                id = file.absolutePath.hashCode().toLong(),
                                uri = uri,
                                name = file.name,
                                dateModified = file.lastModified(),
                                fileSize = file.length()
                            )
                        )
                    }
            }
        }
        
        // Sort all items by date descending
        val sortedItems = items.sortedByDescending { it.dateModified }
        
        Log.d(tag, "Total items: ${sortedItems.size}")
        return sortedItems
    }
    
    private fun getContentUriForFile(file: File): Uri? {
        // Query MediaStore to get content:// URI for this file
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        
        return context?.contentResolver?.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            } else {
                null
            }
        }
    }

    private fun updateUI(count: Int) {
        if (count == 0) {
            binding.emptyText.text = getString(R.string.gallery_empty)
            binding.emptyText.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.fileCountText.text = ""
        } else {
            binding.emptyText.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.fileCountText.text = getString(R.string.gallery_file_count, count)
        }
    }

    private fun openImageWith(item: GalleryItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, "image/x-adobe-dng")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooser = Intent.createChooser(intent, getString(R.string.open_with)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            Log.e(tag, "Error opening image", e)
            Toast.makeText(requireContext(), "No app found to open DNG files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearFolderConfirmation() {
        // Count files based on current filter
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        val jpegDir = File(raw2dngDir, "JPEG")
        
        val (fileCount, emptyMessage, confirmMessage, title) = when (currentFilter) {
            GalleryFilter.DNG_ONLY -> {
                val count = raw2dngDir.listFiles()?.filter { 
                    it.isFile && it.extension.lowercase() == "dng" 
                }?.size ?: 0
                Quad(count, R.string.folder_empty_dng, R.string.clear_folder_confirm_dng, "Clear DNG Files")
            }
            GalleryFilter.JPEG_ONLY -> {
                val count = jpegDir.listFiles()?.filter { 
                    it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") 
                }?.size ?: 0
                Quad(count, R.string.folder_empty_jpeg, R.string.clear_folder_confirm_jpeg, "Clear JPEG Files")
            }
            GalleryFilter.ALL -> {
                val dngCount = raw2dngDir.listFiles()?.filter { 
                    it.isFile && it.extension.lowercase() == "dng" 
                }?.size ?: 0
                val jpegCount = jpegDir.listFiles()?.filter { 
                    it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") 
                }?.size ?: 0
                Quad(dngCount + jpegCount, R.string.folder_empty, R.string.clear_folder_confirm, "Clear All Files")
            }
        }

        if (fileCount == 0) {
            Toast.makeText(requireContext(), getString(emptyMessage), Toast.LENGTH_SHORT).show()
            return
        }

        // On Android 11+, MediaStore.createDeleteRequest shows its own system dialog
        // so we don't need to show our own confirmation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clearRaw2DNGFolder()
        } else {
            // On older Android, show our own confirmation dialog
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(getString(confirmMessage, fileCount))
                .setPositiveButton("Delete") { _, _ ->
                    clearRaw2DNGFolder()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun clearRaw2DNGFolder() {
        val successMessage = when (currentFilter) {
            GalleryFilter.DNG_ONLY -> R.string.folder_cleared_dng
            GalleryFilter.JPEG_ONLY -> R.string.folder_cleared_jpeg
            GalleryFilter.ALL -> R.string.folder_cleared
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - use createDeleteRequest for user confirmation
            requestDeleteWithMediaStore()
        } else {
            // Android 10 and below - try direct deletion
            viewLifecycleOwner.lifecycleScope.launch {
                val deletedCount = withContext(Dispatchers.IO) {
                    deleteFilesDirect()
                }
                
                if (_binding != null) {
                    val message = getString(successMessage, deletedCount)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    loadImages()
                    
                    // Notify Convert tab to refresh its conversion status badges
                    (activity as? MainActivity)?.refreshConvertTab()
                }
            }
        }
    }

    private fun requestDeleteWithMediaStore() {
        val emptyMessage = when (currentFilter) {
            GalleryFilter.DNG_ONLY -> R.string.folder_empty_dng
            GalleryFilter.JPEG_ONLY -> R.string.folder_empty_jpeg
            GalleryFilter.ALL -> R.string.folder_empty
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            val urisToDelete = withContext(Dispatchers.IO) {
                getMediaStoreUris()
            }

            if (urisToDelete.isEmpty()) {
                Toast.makeText(requireContext(), getString(emptyMessage), Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val deleteRequest = MediaStore.createDeleteRequest(
                        requireContext().contentResolver,
                        urisToDelete
                    )
                    deleteRequestLauncher.launch(
                        IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Failed to create delete request", e)
                    Toast.makeText(requireContext(), "Failed to delete files: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getMediaStoreUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        val jpegDir = File(raw2dngDir, "JPEG")

        // Add DNG files if filter allows
        if (currentFilter == GalleryFilter.DNG_ONLY || currentFilter == GalleryFilter.ALL) {
            raw2dngDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "dng" }?.forEach { file ->
                val uri = getContentUriForFile(file)
                if (uri != null) {
                    uris.add(uri)
                    Log.d(tag, "Found URI for ${file.name}: $uri")
                } else {
                    Log.w(tag, "No MediaStore entry for ${file.name}")
                }
            }
        }
        
        // Add JPEG files if filter allows
        if (currentFilter == GalleryFilter.JPEG_ONLY || currentFilter == GalleryFilter.ALL) {
            jpegDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") }?.forEach { file ->
                val uri = getContentUriForFile(file)
                if (uri != null) {
                    uris.add(uri)
                    Log.d(tag, "Found URI for ${file.name}: $uri")
                } else {
                    Log.w(tag, "No MediaStore entry for ${file.name}")
                }
            }
        }

        return uris
    }
    
    private fun deleteFilesDirect(): Int {
        var deletedCount = 0
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        val jpegDir = File(raw2dngDir, "JPEG")
        
        // Delete DNG files if filter allows
        if (currentFilter == GalleryFilter.DNG_ONLY || currentFilter == GalleryFilter.ALL) {
            raw2dngDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "dng" }?.forEach { file ->
                Log.d(tag, "Deleting file: ${file.absolutePath}")
                if (file.exists() && file.delete()) {
                    deletedCount++
                    Log.d(tag, "File deleted successfully")
                    context?.let {
                        MediaScannerConnection.scanFile(it, arrayOf(file.absolutePath), null, null)
                    }
                } else {
                    Log.e(tag, "Failed to delete file: ${file.absolutePath}")
                }
            }
        }
        
        // Delete JPEG files if filter allows
        if (currentFilter == GalleryFilter.JPEG_ONLY || currentFilter == GalleryFilter.ALL) {
            jpegDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") }?.forEach { file ->
                Log.d(tag, "Deleting file: ${file.absolutePath}")
                if (file.exists() && file.delete()) {
                    deletedCount++
                    Log.d(tag, "File deleted successfully")
                    context?.let {
                        MediaScannerConnection.scanFile(it, arrayOf(file.absolutePath), null, null)
                    }
                } else {
                    Log.e(tag, "Failed to delete file: ${file.absolutePath}")
                }
            }
        }
        
        Log.d(tag, "Total deleted: $deletedCount")
        return deletedCount
    }

    fun refresh() {
        if (isAdded && _binding != null) {
            checkPermissionsAndLoad()
        }
    }
    
    /**
     * Set the gallery filter and refresh the view.
     * Called when navigating from conversion completion.
     */
    fun setFilter(format: OutputFormat) {
        currentFilter = when (format) {
            OutputFormat.DNG -> GalleryFilter.DNG_ONLY
            OutputFormat.JPEG -> GalleryFilter.JPEG_ONLY
        }
        if (isAdded && _binding != null) {
            updateFilterChipStates()
            loadImages()
        }
    }

    private fun showRegenerateDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(requireContext(), "No items selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialog = RegenerateDialog.newInstance()
        dialog.setOnRegenerateListener(object : RegenerateDialog.OnRegenerateListener {
            override fun onRegenerate(
                outputFormat: OutputFormat,
                jpegQuality: Int,
                jpegChroma: Int,
                jpegOptimize: Boolean
            ) {
                regenerateSelectedImages(selectedItems, outputFormat, jpegQuality, jpegChroma, jpegOptimize)
            }
        })
        dialog.show(childFragmentManager, RegenerateDialog.TAG)
    }
    
    private fun regenerateSelectedImages(
        selectedItems: List<GalleryItem>,
        outputFormat: OutputFormat,
        jpegQuality: Int,
        jpegChroma: Int,
        jpegOptimize: Boolean
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Find original RAW files for each selected item
            val rawFiles = mutableListOf<Pair<GalleryItem, Uri>>()
            val notFound = mutableListOf<String>()
            
            for (item in selectedItems) {
                val baseName = item.name.substringBeforeLast('.')
                val rawUri = findOriginalRaw(baseName)
                if (rawUri != null) {
                    rawFiles.add(Pair(item, rawUri))
                } else {
                    notFound.add(item.name)
                }
            }
            
            if (rawFiles.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.regenerate_no_raw_files),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            
            if (notFound.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.regenerate_found, rawFiles.size, selectedItems.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            // Show progress dialog
            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.regenerating)
                .setMessage("0/${rawFiles.size}")
                .setCancelable(false)
                .create()
            progressDialog.show()
            
            var completed = 0
            var successful = 0
            val converter = DNGConverter()
            
            for ((galleryItem, rawUri) in rawFiles) {
                val result = withContext(Dispatchers.IO) {
                    regenerateSingleFile(galleryItem, rawUri, outputFormat, jpegQuality, jpegChroma, jpegOptimize, converter)
                }
                
                completed++
                if (result) successful++
                
                progressDialog.setMessage("$completed/${rawFiles.size}")
            }
            
            progressDialog.dismiss()
            
            Toast.makeText(
                requireContext(),
                "Regenerated $successful of ${rawFiles.size} files",
                Toast.LENGTH_SHORT
            ).show()
            
            // Exit multi-select mode and refresh
            adapter.clearSelection()
            loadImages()
            
            // Refresh convert tab
            (activity as? MainActivity)?.refreshConvertTab()
        }
    }
    
    private suspend fun regenerateSingleFile(
        galleryItem: GalleryItem,
        rawUri: Uri,
        outputFormat: OutputFormat,
        jpegQuality: Int,
        jpegChroma: Int,
        jpegOptimize: Boolean,
        converter: DNGConverter
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = requireContext()
            val cacheDir = context.cacheDir
            val baseName = galleryItem.name.substringBeforeLast('.')
            val uniqueId = UUID.randomUUID().toString().take(8)
            
            // Copy RAW file to cache
            val rawExtension = getRawExtension(rawUri)
            val cacheInputFile = File(cacheDir, "${baseName}_${uniqueId}.$rawExtension")
            context.contentResolver.openInputStream(rawUri)?.use { input ->
                FileOutputStream(cacheInputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Prepare output file
            val outputExtension = if (outputFormat == OutputFormat.DNG) "dng" else "jpg"
            val cacheOutputFile = File(cacheDir, "${baseName}_${uniqueId}.$outputExtension")
            
            // Convert
            val errorMessage = when (outputFormat) {
                OutputFormat.DNG -> converter.convertToDNG(cacheInputFile.absolutePath, cacheOutputFile.absolutePath)
                OutputFormat.JPEG -> {
                    val result = converter.convertToJPEG(
                        cacheInputFile.absolutePath,
                        cacheOutputFile.absolutePath,
                        jpegQuality,
                        jpegChroma,
                        jpegOptimize
                    )
                    // If JPEG conversion succeeded, write EXIF data from the source RAW
                    if (result.isEmpty()) {
                        ExifData.writeExifToJpeg(cacheInputFile.absolutePath, cacheOutputFile.absolutePath)
                    }
                    result
                }
            }
            
            if (errorMessage.isNotEmpty()) {
                Log.e(tag, "Regenerate failed: $errorMessage")
                cacheInputFile.delete()
                cacheOutputFile.delete()
                return@withContext false
            }
            
            // Save to public storage (overwrites existing file with same name)
            val publicFileName = "$baseName.$outputExtension"
            saveToPublicStorage(cacheOutputFile, outputFormat, publicFileName)
            
            // Clean up cache
            cacheInputFile.delete()
            cacheOutputFile.delete()
            
            true
        } catch (e: Exception) {
            Log.e(tag, "Regenerate error: ${e.message}", e)
            false
        }
    }
    
    private fun getRawExtension(uri: Uri): String {
        val cursor = requireContext().contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(0)
                return name.substringAfterLast('.', "raw")
            }
        }
        return "raw"
    }
    
    private suspend fun findOriginalRaw(baseName: String): Uri? = withContext(Dispatchers.IO) {
        val contentResolver = requireContext().contentResolver
        val rawExtensions = SettingsFragment.getEnabledRawTypes(requireContext())
        
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        
        // Search for each RAW extension
        for (ext in rawExtensions) {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$baseName.$ext")
            
            val cursor = contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return@withContext Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        
        // Also try uppercase extensions
        for (ext in rawExtensions) {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$baseName.${ext.uppercase()}")
            
            val cursor = contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return@withContext Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        
        null
    }
    
    private fun saveToPublicStorage(sourceFile: File, format: OutputFormat, fileName: String) {
        val context = requireContext()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            
            val relativePath = if (format == OutputFormat.DNG) {
                "${Environment.DIRECTORY_PICTURES}/Raw2DNG"
            } else {
                "${Environment.DIRECTORY_PICTURES}/Raw2DNG/JPEG"
            }
            
            val mimeType = if (format == OutputFormat.DNG) "image/x-adobe-dng" else "image/jpeg"
            
            // Check if file already exists and delete it
            val existingUri = findExistingFile(fileName, relativePath)
            existingUri?.let {
                context.contentResolver.delete(it, null, null)
            }
            
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            
            val uri = context.contentResolver.insert(collection, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
        } else {
            // Legacy storage for older Android versions
            val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Raw2DNG")
            val outputDir = if (format == OutputFormat.DNG) baseDir else File(baseDir, "JPEG")
            outputDir.mkdirs()
            
            val destFile = File(outputDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
        }
    }
    
    private fun findExistingFile(fileName: String, relativePath: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, "$relativePath/")
        
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

    // Settings dialog removed - settings is now a dedicated tab

    override fun onResume() {
        super.onResume()
        checkPermissionsAndLoad()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
