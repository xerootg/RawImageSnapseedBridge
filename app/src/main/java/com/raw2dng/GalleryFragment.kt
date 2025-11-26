package com.raw2dng

import android.Manifest
import android.app.Activity
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.raw2dng.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: GalleryAdapter
    private val tag = "GalleryFragment"

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
        checkPermissionsAndLoad()
    }

    private fun setupUI() {
        adapter = GalleryAdapter { item ->
            openImageWith(item)
        }

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        binding.clearFolderButton.setOnClickListener {
            showClearFolderConfirmation()
        }
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
        
        Log.d(tag, "Looking for files in: ${raw2dngDir.absolutePath}")
        Log.d(tag, "Directory exists: ${raw2dngDir.exists()}")
        
        if (raw2dngDir.exists() && raw2dngDir.isDirectory) {
            val files = raw2dngDir.listFiles()
            Log.d(tag, "Files found: ${files?.size ?: 0}")
            
            files?.filter { it.isFile && it.extension.lowercase() == "dng" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    Log.d(tag, "Adding file: ${file.name}")
                    
                    // For Android 10+, get content URI via MediaStore query by DATA path
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
                            dateModified = file.lastModified()
                        )
                    )
                }
        }
        
        Log.d(tag, "Total items: ${items.size}")
        return items
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
        // Count files directly from filesystem
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        val fileCount = raw2dngDir.listFiles()?.filter { 
            it.isFile && it.extension.lowercase() == "dng" 
        }?.size ?: 0

        if (fileCount == 0) {
            Toast.makeText(requireContext(), getString(R.string.folder_empty), Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Folder")
            .setMessage(getString(R.string.clear_folder_confirm, fileCount))
            .setPositiveButton("Delete") { _, _ ->
                clearRaw2DNGFolder()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearRaw2DNGFolder() {
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
                    val message = getString(R.string.folder_cleared, deletedCount)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    loadImages()
                }
            }
        }
    }

    private fun requestDeleteWithMediaStore() {
        viewLifecycleOwner.lifecycleScope.launch {
            val urisToDelete = withContext(Dispatchers.IO) {
                getMediaStoreUris()
            }

            if (urisToDelete.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.folder_empty), Toast.LENGTH_SHORT).show()
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

        raw2dngDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "dng" }?.forEach { file ->
            // Get content URI for this file
            val uri = getContentUriForFile(file)
            if (uri != null) {
                uris.add(uri)
                Log.d(tag, "Found URI for ${file.name}: $uri")
            } else {
                Log.w(tag, "No MediaStore entry for ${file.name}")
            }
        }

        return uris
    }
    
    private fun deleteFilesDirect(): Int {
        var deletedCount = 0
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val raw2dngDir = File(picturesDir, "Raw2DNG")
        
        raw2dngDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "dng" }?.forEach { file ->
            Log.d(tag, "Deleting file: ${file.absolutePath}")
            
            // Delete the actual file
            if (file.exists() && file.delete()) {
                deletedCount++
                Log.d(tag, "File deleted successfully")
                
                // Notify MediaScanner about deletion
                context?.let {
                    MediaScannerConnection.scanFile(it, arrayOf(file.absolutePath), null, null)
                }
            } else {
                Log.e(tag, "Failed to delete file: ${file.absolutePath}")
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

    override fun onResume() {
        super.onResume()
        checkPermissionsAndLoad()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
