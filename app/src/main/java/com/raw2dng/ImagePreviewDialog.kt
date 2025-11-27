package com.raw2dng

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var btnOpenWith: ImageButton
    private lateinit var selectionCountText: TextView
    private lateinit var exifOverlay: FrameLayout
    private lateinit var exifText: TextView
    private lateinit var exifScrollView: ScrollView
    private lateinit var jsonScrollView: ScrollView
    private lateinit var jsonText: TextView
    private lateinit var btnAdvanced: android.widget.Button
    private lateinit var exifTapToClose: TextView
    private lateinit var btnInfo: ImageButton
    
    private var isAdvancedView = false
    private var currentRawJson: String = ""

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
        btnInfo = view.findViewById(R.id.btnInfo)
        exifOverlay = view.findViewById(R.id.exifOverlay)
        exifText = view.findViewById(R.id.exifText)
        exifScrollView = view.findViewById(R.id.exifScrollView)
        jsonScrollView = view.findViewById(R.id.jsonScrollView)
        jsonText = view.findViewById(R.id.jsonText)
        btnAdvanced = view.findViewById(R.id.btnAdvanced)
        exifTapToClose = view.findViewById(R.id.exifTapToClose)
        
        val closeButton: ImageButton = view.findViewById(R.id.closeButton)
        val btnZoomIn: ImageButton = view.findViewById(R.id.btnZoomIn)
        val btnZoomOut: ImageButton = view.findViewById(R.id.btnZoomOut)
        val btnFitScreen: ImageButton = view.findViewById(R.id.btnFitScreen)

        // Setup ViewPager2 with adapter - pass fileNames and fileSizes for EXIF overlay
        imagePagerAdapter = ImagePagerAdapter(requireContext(), imageUris, fileNames, fileSizes)
        imagePagerAdapter.setOnZoomChangeListener { scale ->
            val percentage = (scale * 100).toInt()
            zoomLevel.text = "$percentage%"
            // Update EXIF overlay visibility based on zoom
            imagePagerAdapter.updateZoomState(scale)
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
        
        // EXIF info button
        btnInfo.setOnClickListener {
            toggleExifOverlay()
        }
        
        // Tap overlay to close (basic view) or go back (advanced view)
        val closeExifListener = View.OnClickListener { 
            if (isAdvancedView) {
                showBasicView()
            } else {
                hideExifOverlay() 
            }
        }
        exifOverlay.setOnClickListener(closeExifListener)
        view.findViewById<View>(R.id.exifScrollView).setOnClickListener(closeExifListener)
        view.findViewById<View>(R.id.exifContent).setOnClickListener(closeExifListener)
        view.findViewById<View>(R.id.exifTitle).setOnClickListener(closeExifListener)
        view.findViewById<View>(R.id.exifText).setOnClickListener(closeExifListener)
        view.findViewById<View>(R.id.exifTapToClose).setOnClickListener(closeExifListener)

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
            // If no images selected, use the current image
            val urisToShare = if (selectedUris.isNotEmpty()) {
                selectedUris.toList()
            } else {
                imageUris.getOrNull(currentPosition)?.let { listOf(it) } ?: emptyList()
            }
            
            if (urisToShare.isNotEmpty()) {
                openWithRequestedListener?.onOpenWithRequested(urisToShare)
                    ?: openWithDefaultHandler(urisToShare)
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
    
    private fun openWithDefaultHandler(urisToOpen: List<Uri>) {
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
                    updateSelectionCount()
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
        
        // Refresh EXIF overlay if it's currently visible
        if (exifOverlay.visibility == View.VISIBLE) {
            refreshExifOverlay()
        }
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
        updateSelectionCount()
        
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
        // Open/share button is always enabled in gallery mode - shares current image if nothing selected
        btnOpenWith.isEnabled = previewMode == PreviewMode.GALLERY_VIEW || hasSelection
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
    
    private fun toggleExifOverlay() {
        if (exifOverlay.visibility == View.VISIBLE) {
            hideExifOverlay()
        } else {
            showExifOverlay()
        }
    }
    
    private fun showExifOverlay() {
        exifOverlay.visibility = View.VISIBLE
        isAdvancedView = false
        showBasicView()
        refreshExifOverlay()
        setupAdvancedButton()
        setupJsonGestureDetector()
    }
    
    private fun setupAdvancedButton() {
        btnAdvanced.setOnClickListener {
            if (!isAdvancedView) {
                showAdvancedView()
            }
        }
    }
    
    private fun setupJsonGestureDetector() {
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Single tap - go back to basic view
                if (isAdvancedView) {
                    showBasicView()
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Double tap - copy JSON to clipboard
                if (isAdvancedView && currentRawJson.isNotEmpty()) {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("EXIF JSON", currentRawJson)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), R.string.json_copied, Toast.LENGTH_SHORT).show()
                }
                return true
            }
        })
        
        jsonScrollView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            // Let scroll view handle scrolling
            false
        }
        
        jsonText.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }
    
    private fun showBasicView() {
        isAdvancedView = false
        exifScrollView.visibility = View.VISIBLE
        jsonScrollView.visibility = View.GONE
        exifTapToClose.text = getString(R.string.tap_to_close)
        btnAdvanced.text = getString(R.string.advanced)
    }
    
    private fun showAdvancedView() {
        isAdvancedView = true
        exifScrollView.visibility = View.GONE
        jsonScrollView.visibility = View.VISIBLE
        exifTapToClose.text = getString(R.string.tap_to_go_back)
        
        // Load raw JSON
        jsonText.text = getString(R.string.loading_exif)
        lifecycleScope.launch {
            val json = loadRawJson()
            if (isAdded && exifOverlay.visibility == View.VISIBLE && isAdvancedView) {
                currentRawJson = json
                jsonText.text = json
            }
        }
    }
    
    private suspend fun loadRawJson(): String = withContext(Dispatchers.IO) {
        val uri = imageUris.getOrNull(currentPosition) ?: return@withContext "{\"error\": \"No image\"}"
        val fileName = fileNames.getOrNull(currentPosition) ?: ""
        
        try {
            ExifData.extractRawJsonFromUri(requireContext(), uri, fileName)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
    }
    
    private fun refreshExifOverlay() {
        if (isAdvancedView) {
            // Refresh JSON view
            jsonText.text = getString(R.string.loading_exif)
            lifecycleScope.launch {
                val json = loadRawJson()
                if (isAdded && exifOverlay.visibility == View.VISIBLE && isAdvancedView) {
                    currentRawJson = json
                    jsonText.text = json
                }
            }
        } else {
            // Refresh basic view
            exifText.text = getString(R.string.loading_exif)
            lifecycleScope.launch {
                val exifData = loadExifData()
                if (isAdded && exifOverlay.visibility == View.VISIBLE && !isAdvancedView) {
                    exifText.text = formatExifData(exifData)
                }
            }
        }
    }
    
    private fun hideExifOverlay() {
        exifOverlay.visibility = View.GONE
        isAdvancedView = false
        currentRawJson = ""
    }
    
    private suspend fun loadExifData(): ExifData = withContext(Dispatchers.IO) {
        val uri = imageUris.getOrNull(currentPosition) ?: return@withContext ExifData(error = "No image")
        val fileName = fileNames.getOrNull(currentPosition) ?: ""
        val fileSize = fileSizes.getOrNull(currentPosition) ?: 0L
        val fileType = fileTypes.getOrNull(currentPosition) ?: ""
        
        // Use LibRaw for all file types (RAW, DNG, JPEG)
        try {
            ExifData.extractFromUri(requireContext(), uri, fileName, fileSize)
        } catch (e: Exception) {
            ExifData(fileName = fileName, fileSize = fileSize, error = "Failed to read metadata: ${e.message}")
        }
    }
    
    private fun formatExifData(exif: ExifData): String {
        val sb = StringBuilder()
        
        if (exif.error != null) {
            sb.appendLine("Error: ${exif.error}")
            sb.appendLine()
        }
        
        // File info
        if (exif.fileName.isNotEmpty()) {
            sb.appendLine("📄 ${exif.fileName}")
        }
        if (exif.fileSize > 0) {
            sb.appendLine("💾 ${formatFileSize(exif.fileSize)}")
        }
        if (exif.fileType.isNotEmpty()) {
            sb.appendLine("📁 ${exif.fileType}")
        }
        sb.appendLine()
        
        // Camera info
        if (exif.camera.isNotEmpty()) {
            sb.appendLine("📷 ${exif.camera}")
        }
        if (exif.bodySerial.isNotEmpty()) {
            sb.appendLine("    Serial: ${exif.bodySerial}")
        }
        if (exif.lens.isNotEmpty()) {
            sb.appendLine("🔭 ${exif.lens}")
        }
        if (exif.lensRangeString.isNotEmpty() && exif.lens.isNotEmpty()) {
            sb.appendLine("    Range: ${exif.lensRangeString}")
        }
        if (exif.lensSerial.isNotEmpty()) {
            sb.appendLine("    Serial: ${exif.lensSerial}")
        }
        sb.appendLine()
        
        // Exposure settings
        val exposureInfo = mutableListOf<String>()
        if (exif.focalLengthString.isNotEmpty()) exposureInfo.add(exif.focalLengthString)
        if (exif.apertureString.isNotEmpty()) exposureInfo.add(exif.apertureString)
        if (exif.shutterSpeed.isNotEmpty()) exposureInfo.add(exif.shutterSpeed)
        if (exif.isoString.isNotEmpty()) exposureInfo.add(exif.isoString)
        
        if (exposureInfo.isNotEmpty()) {
            sb.appendLine("⚙️ ${exposureInfo.joinToString("  •  ")}")
        }
        
        // 35mm equivalent focal length (only show if different from actual focal length)
        if (exif.focalLength35mmString.isNotEmpty() && exif.focalLength35mm != exif.focalLength.toInt()) {
            sb.appendLine("    ${exif.focalLength35mmString}")
        }
        
        // Exposure program and metering mode
        if (exif.exposureProgramString.isNotEmpty()) {
            sb.appendLine("    Program: ${exif.exposureProgramString}")
        }
        if (exif.meteringModeString.isNotEmpty()) {
            sb.appendLine("    Metering: ${exif.meteringModeString}")
        }
        sb.appendLine()
        
        // Dimensions
        if (exif.dimensionsString.isNotEmpty()) {
            sb.appendLine("📐 ${exif.dimensionsString}")
        }
        if (exif.rawDimensionsString.isNotEmpty()) {
            sb.appendLine("    Sensor: ${exif.rawDimensionsString}")
        }
        
        // Bayer pattern
        if (exif.bayerPattern.isNotEmpty()) {
            sb.appendLine("    Pattern: ${exif.bayerPattern}")
        }
        
        // GPS
        if (exif.hasGps) {
            sb.appendLine()
            sb.appendLine("📍 ${exif.gpsString}")
            if (exif.gpsAltitudeString.isNotEmpty()) {
                sb.appendLine("    Altitude: ${exif.gpsAltitudeString}")
            }
        }
        
        // Date
        if (exif.dateTime.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("📅 ${exif.dateTime}")
        }
        
        // Artist and description
        if (exif.artist.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("👤 ${exif.artist}")
        }
        if (exif.description.isNotEmpty()) {
            sb.appendLine("📝 ${exif.description}")
        }
        
        return sb.toString().trimEnd()
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
