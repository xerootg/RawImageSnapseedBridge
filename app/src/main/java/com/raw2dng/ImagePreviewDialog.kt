package com.raw2dng

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Preview mode determines which UI elements are shown
 */
enum class PreviewMode {
    RAW_CONVERSION,  // Shows convert buttons (JPEG/DNG), for RAW file picker
    GALLERY_VIEW     // Shows "Open with" button, for gallery preview
}

class ImagePreviewDialog : DialogFragment() {

    interface OnSelectionChangeListener {
        fun onSelectionChanged(uri: Uri, isSelected: Boolean)
    }

    interface OnConvertRequestedListener {
        fun onConvertRequested(format: OutputFormat)
    }

    interface OnOpenWithRequestedListener {
        fun onOpenWithRequested(uris: List<Uri>)
    }

    private var imageUris: ArrayList<Uri> = arrayListOf()
    private var fileNames: ArrayList<String> = arrayListOf()
    private var dngStatus: ArrayList<Boolean> = arrayListOf()
    private var jpegStatus: ArrayList<Boolean> = arrayListOf()
    private var fileTypes: ArrayList<String> = arrayListOf()
    private var fileSizes: ArrayList<Long> = arrayListOf()
    private var selectedUris: HashSet<Uri> = hashSetOf()
    private var currentPosition: Int = 0
    private var previewMode: PreviewMode = PreviewMode.RAW_CONVERSION
    private var selectionChangeListener: OnSelectionChangeListener? = null
    private var convertRequestedListener: OnConvertRequestedListener? = null
    private var openWithRequestedListener: OnOpenWithRequestedListener? = null

    private lateinit var thumbnailStripAdapter: ThumbnailStripAdapter
    private lateinit var imagePagerAdapter: ImagePagerAdapter
    private lateinit var previewPager: ViewPager2
    private lateinit var previewFileName: TextView
    private lateinit var previewFileSize: TextView
    private lateinit var zoomLevel: TextView
    private lateinit var thumbnailStrip: RecyclerView
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnSelectImage: ImageButton
    private lateinit var btnConvertJpeg: android.widget.Button
    private lateinit var btnConvertDng: android.widget.Button
    private lateinit var btnOpenWith: android.widget.Button
    private lateinit var selectionCountText: TextView

    companion object {
        private const val ARG_URIS = "uris"
        private const val ARG_FILENAMES = "filenames"
        private const val ARG_POSITION = "position"
        private const val ARG_SELECTED_URIS = "selected_uris"
        private const val ARG_DNG_STATUS = "dng_status"
        private const val ARG_JPEG_STATUS = "jpeg_status"
        private const val ARG_FILE_TYPES = "file_types"
        private const val ARG_FILE_SIZES = "file_sizes"
        private const val ARG_PREVIEW_MODE = "preview_mode"

        fun newInstance(
            uris: ArrayList<Uri>,
            fileNames: ArrayList<String>,
            initialPosition: Int,
            selectedUris: ArrayList<Uri> = arrayListOf(),
            dngStatus: ArrayList<Boolean> = arrayListOf(),
            jpegStatus: ArrayList<Boolean> = arrayListOf(),
            fileTypes: ArrayList<String> = arrayListOf(),
            fileSizes: ArrayList<Long> = arrayListOf(),
            mode: PreviewMode = PreviewMode.RAW_CONVERSION
        ): ImagePreviewDialog {
            return ImagePreviewDialog().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_URIS, uris)
                    putStringArrayList(ARG_FILENAMES, fileNames)
                    putInt(ARG_POSITION, initialPosition)
                    putParcelableArrayList(ARG_SELECTED_URIS, selectedUris)
                    // Store boolean arrays as serializable
                    putSerializable(ARG_DNG_STATUS, dngStatus)
                    putSerializable(ARG_JPEG_STATUS, jpegStatus)
                    putStringArrayList(ARG_FILE_TYPES, fileTypes)
                    putSerializable(ARG_FILE_SIZES, fileSizes)
                    putString(ARG_PREVIEW_MODE, mode.name)
                }
            }
        }

        // Convenience method for single image (backwards compatibility)
        fun newInstance(uri: Uri, fileName: String): ImagePreviewDialog {
            return newInstance(arrayListOf(uri), arrayListOf(fileName), 0, arrayListOf(), arrayListOf(false), arrayListOf(false))
        }
    }

    fun setOnSelectionChangeListener(listener: OnSelectionChangeListener) {
        selectionChangeListener = listener
    }

    fun setOnConvertRequestedListener(listener: OnConvertRequestedListener) {
        convertRequestedListener = listener
    }

    fun setOnOpenWithRequestedListener(listener: OnOpenWithRequestedListener) {
        openWithRequestedListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
        arguments?.let {
            imageUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableArrayList(ARG_URIS, Uri::class.java) ?: arrayListOf()
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableArrayList(ARG_URIS) ?: arrayListOf()
            }
            fileNames = it.getStringArrayList(ARG_FILENAMES) ?: arrayListOf()
            currentPosition = it.getInt(ARG_POSITION, 0)
            
            val selectedList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableArrayList(ARG_SELECTED_URIS, Uri::class.java) ?: arrayListOf()
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableArrayList<Uri>(ARG_SELECTED_URIS) ?: arrayListOf()
            }
            selectedUris = HashSet(selectedList)
            
            // Get conversion status arrays
            @Suppress("UNCHECKED_CAST")
            dngStatus = (it.getSerializable(ARG_DNG_STATUS) as? ArrayList<Boolean>) ?: arrayListOf()
            @Suppress("UNCHECKED_CAST")
            jpegStatus = (it.getSerializable(ARG_JPEG_STATUS) as? ArrayList<Boolean>) ?: arrayListOf()
            
            // Get file types
            fileTypes = it.getStringArrayList(ARG_FILE_TYPES) ?: arrayListOf()
            
            // Get file sizes
            @Suppress("UNCHECKED_CAST")
            fileSizes = (it.getSerializable(ARG_FILE_SIZES) as? ArrayList<Long>) ?: arrayListOf()
            
            // Get preview mode
            previewMode = try {
                PreviewMode.valueOf(it.getString(ARG_PREVIEW_MODE) ?: PreviewMode.RAW_CONVERSION.name)
            } catch (e: IllegalArgumentException) {
                PreviewMode.RAW_CONVERSION
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_image_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewPager = view.findViewById(R.id.previewPager)
        previewFileName = view.findViewById(R.id.previewFileName)
        previewFileSize = view.findViewById(R.id.previewFileSize)
        thumbnailStrip = view.findViewById(R.id.thumbnailStrip)
        zoomLevel = view.findViewById(R.id.zoomLevel)
        btnPrevious = view.findViewById(R.id.btnPrevious)
        btnNext = view.findViewById(R.id.btnNext)
        btnSelectImage = view.findViewById(R.id.btnSelectImage)
        
        val closeButton: ImageButton = view.findViewById(R.id.closeButton)
        val btnZoomIn: ImageButton = view.findViewById(R.id.btnZoomIn)
        val btnZoomOut: ImageButton = view.findViewById(R.id.btnZoomOut)
        val btnFitScreen: ImageButton = view.findViewById(R.id.btnFitScreen)

        // Setup ViewPager2 with adapter
        imagePagerAdapter = ImagePagerAdapter(requireContext(), imageUris)
        imagePagerAdapter.setOnZoomChangeListener { scale ->
            val percentage = (scale * 100).toInt()
            zoomLevel.text = "$percentage%"
        }
        previewPager.adapter = imagePagerAdapter

        // Listen for page changes (swipe)
        previewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position != currentPosition) {
                    currentPosition = position
                    updateUIForCurrentPosition()
                }
            }
        })

        closeButton.setOnClickListener {
            dismiss()
        }

        btnZoomIn.setOnClickListener {
            getCurrentZoomableImageView()?.zoomIn()
        }

        btnZoomOut.setOnClickListener {
            getCurrentZoomableImageView()?.zoomOut()
        }

        btnFitScreen.setOnClickListener {
            getCurrentZoomableImageView()?.fitToScreen()
        }

        btnPrevious.setOnClickListener {
            if (currentPosition > 0) {
                previewPager.setCurrentItem(currentPosition - 1, true)
            }
        }

        btnNext.setOnClickListener {
            if (currentPosition < imageUris.size - 1) {
                previewPager.setCurrentItem(currentPosition + 1, true)
            }
        }

        btnSelectImage.setOnClickListener {
            toggleCurrentImageSelection()
        }

        selectionCountText = view.findViewById(R.id.selectionCountText)
        btnConvertJpeg = view.findViewById(R.id.btnConvertJpeg)
        btnConvertDng = view.findViewById(R.id.btnConvertDng)
        btnOpenWith = view.findViewById(R.id.btnOpenWith)
        
        btnConvertJpeg.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                convertRequestedListener?.onConvertRequested(OutputFormat.JPEG)
            }
        }
        
        btnConvertDng.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                convertRequestedListener?.onConvertRequested(OutputFormat.DNG)
            }
        }
        
        btnOpenWith.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                openWithRequestedListener?.onOpenWithRequested(selectedUris.toList())
                    ?: openWithDefaultHandler()
            }
        }
        
        // Show/hide buttons based on preview mode
        setupModeSpecificUI()
        updateConvertButtons()

        // Setup thumbnail strip
        setupThumbnailStrip()

        // Set initial position
        if (imageUris.isNotEmpty()) {
            previewPager.setCurrentItem(currentPosition, false)
            updateUIForCurrentPosition()
        }
    }
    
    private fun setupModeSpecificUI() {
        when (previewMode) {
            PreviewMode.RAW_CONVERSION -> {
                btnConvertJpeg.visibility = View.VISIBLE
                btnConvertDng.visibility = View.VISIBLE
                btnOpenWith.visibility = View.GONE
            }
            PreviewMode.GALLERY_VIEW -> {
                btnConvertJpeg.visibility = View.GONE
                btnConvertDng.visibility = View.GONE
                btnOpenWith.visibility = View.VISIBLE
            }
        }
    }
    
    private fun openWithDefaultHandler() {
        val urisToOpen = selectedUris.toList()
        if (urisToOpen.isEmpty()) return
        
        try {
            val intent = if (urisToOpen.size == 1) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(urisToOpen.first(), "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(urisToOpen))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            
            val chooser = Intent.createChooser(intent, getString(R.string.open_with))
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to open images", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentZoomableImageView(): ZoomableImageView? {
        // ViewPager2 is backed by RecyclerView, get the current item's view
        val recyclerView = previewPager.getChildAt(0) as? RecyclerView ?: return null
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
        return viewHolder?.itemView?.findViewById(R.id.pageImage)
    }

    private fun setupThumbnailStrip() {
        val selectionToggleHandler: ((Int) -> Unit)? = if (previewMode == PreviewMode.GALLERY_VIEW) {
            { position ->
                // Toggle selection for this item
                val uri = imageUris.getOrNull(position)
                if (uri != null) {
                    val isNowSelected = if (selectedUris.contains(uri)) {
                        selectedUris.remove(uri)
                        false
                    } else {
                        selectedUris.add(uri)
                        true
                    }
                    thumbnailStripAdapter.updateItemSelection(position, isNowSelected)
                    updateConvertButtons()
                    selectionChangeListener?.onSelectionChanged(uri, isNowSelected)
                }
            }
        } else null
        
        thumbnailStripAdapter = ThumbnailStripAdapter(
            onThumbnailClick = { position ->
                previewPager.setCurrentItem(position, true)
            },
            onSelectionToggle = selectionToggleHandler
        )

        thumbnailStrip.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailStripAdapter
        }

        // Build thumbnail strip items with conversion/selection status and file type
        val thumbnailItems = imageUris.mapIndexed { index, uri ->
            ThumbnailStripItem(
                uri = uri,
                isConvertedToDng = dngStatus.getOrElse(index) { false },
                isConvertedToJpeg = jpegStatus.getOrElse(index) { false },
                isSelected = selectedUris.contains(uri),
                fileType = fileTypes.getOrElse(index) { null }
            )
        }
        thumbnailStripAdapter.submitList(thumbnailItems)
        
        // Enable selection checkmarks and file type badges for gallery mode
        if (previewMode == PreviewMode.GALLERY_VIEW) {
            thumbnailStripAdapter.setShowSelectionCheckmarks(true)
            thumbnailStripAdapter.setShowFileTypeBadge(true)
        }
        
        // Set initial selection and scroll to it
        if (currentPosition > 0) {
            thumbnailStripAdapter.setSelectedPosition(currentPosition)
            thumbnailStrip.scrollToPosition(currentPosition)
        }
    }

    private fun updateUIForCurrentPosition() {
        val fileName = if (currentPosition < fileNames.size) fileNames[currentPosition] else ""
        previewFileName.text = fileName
        
        // Update file size display
        val fileSize = if (currentPosition < fileSizes.size) fileSizes[currentPosition] else 0L
        previewFileSize.text = if (fileSize > 0) formatFileSize(fileSize) else ""
        
        // Update thumbnail strip selection
        thumbnailStripAdapter.setSelectedPosition(currentPosition)
        
        // Scroll thumbnail strip to make current item visible
        thumbnailStrip.smoothScrollToPosition(currentPosition)

        // Update navigation buttons visibility
        updateNavigationButtons()

        // Update selection button state
        updateSelectionButton()
        
        // Update selection count and total size
        updateSelectionCount()
    }

    private fun updateNavigationButtons() {
        // Show/hide previous button based on position
        btnPrevious.visibility = if (currentPosition > 0) View.VISIBLE else View.INVISIBLE
        
        // Show/hide next button based on position
        btnNext.visibility = if (currentPosition < imageUris.size - 1) View.VISIBLE else View.INVISIBLE
    }

    private fun updateSelectionButton() {
        val currentUri = imageUris.getOrNull(currentPosition) ?: return
        val isSelected = selectedUris.contains(currentUri)
        
        btnSelectImage.setImageResource(
            if (isSelected) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        btnSelectImage.contentDescription = getString(
            if (isSelected) R.string.deselect_image else R.string.select_image
        )
    }

    private fun toggleCurrentImageSelection() {
        val currentUri = imageUris.getOrNull(currentPosition) ?: return
        
        val isNowSelected = if (selectedUris.contains(currentUri)) {
            selectedUris.remove(currentUri)
            false
        } else {
            selectedUris.add(currentUri)
            true
        }
        
        updateSelectionButton()
        updateConvertButtons()
        
        // Update thumbnail strip checkmark in gallery mode
        if (previewMode == PreviewMode.GALLERY_VIEW) {
            thumbnailStripAdapter.updateItemSelection(currentPosition, isNowSelected)
        }
        
        selectionChangeListener?.onSelectionChanged(currentUri, isNowSelected)
    }

    private fun updateConvertButtons() {
        val count = selectedUris.size
        val hasSelection = count > 0
        btnConvertJpeg.isEnabled = hasSelection
        btnConvertDng.isEnabled = hasSelection
        btnOpenWith.isEnabled = hasSelection
        selectionCountText.text = if (count > 0) count.toString() else ""
    }
    
    private fun updateSelectionCount() {
        if (previewMode != PreviewMode.GALLERY_VIEW) return
        
        val count = selectedUris.size
        if (count > 0) {
            // Calculate total size of selected images
            val totalSize = imageUris.mapIndexedNotNull { index, uri ->
                if (selectedUris.contains(uri) && index < fileSizes.size) fileSizes[index] else null
            }.sum()
            
            selectionCountText.text = getString(R.string.selection_summary_short, count, formatFileSize(totalSize))
        } else {
            selectionCountText.text = ""
        }
    }
    
    /**
     * Get the current set of selected URIs.
     * Useful for syncing selection state after dialog is dismissed.
     */
    fun getSelectedUris(): Set<Uri> = selectedUris.toSet()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imagePagerAdapter.cancelAllLoads()
    }
}
