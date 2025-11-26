package com.raw2dng

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class ImagePreviewDialog : DialogFragment() {

    interface OnSelectionChangeListener {
        fun onSelectionChanged(uri: Uri, isSelected: Boolean)
    }

    interface OnConvertRequestedListener {
        fun onConvertRequested()
    }

    private var imageUris: ArrayList<Uri> = arrayListOf()
    private var fileNames: ArrayList<String> = arrayListOf()
    private var selectedUris: HashSet<Uri> = hashSetOf()
    private var currentPosition: Int = 0
    private var selectionChangeListener: OnSelectionChangeListener? = null
    private var convertRequestedListener: OnConvertRequestedListener? = null

    private lateinit var thumbnailStripAdapter: ThumbnailStripAdapter
    private lateinit var imagePagerAdapter: ImagePagerAdapter
    private lateinit var previewPager: ViewPager2
    private lateinit var previewFileName: TextView
    private lateinit var zoomLevel: TextView
    private lateinit var thumbnailStrip: RecyclerView
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnSelectImage: ImageButton
    private lateinit var btnConvertPreview: android.widget.Button

    companion object {
        private const val ARG_URIS = "uris"
        private const val ARG_FILENAMES = "filenames"
        private const val ARG_POSITION = "position"
        private const val ARG_SELECTED_URIS = "selected_uris"

        fun newInstance(
            uris: ArrayList<Uri>,
            fileNames: ArrayList<String>,
            initialPosition: Int,
            selectedUris: ArrayList<Uri> = arrayListOf()
        ): ImagePreviewDialog {
            return ImagePreviewDialog().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_URIS, uris)
                    putStringArrayList(ARG_FILENAMES, fileNames)
                    putInt(ARG_POSITION, initialPosition)
                    putParcelableArrayList(ARG_SELECTED_URIS, selectedUris)
                }
            }
        }

        // Convenience method for single image (backwards compatibility)
        fun newInstance(uri: Uri, fileName: String): ImagePreviewDialog {
            return newInstance(arrayListOf(uri), arrayListOf(fileName), 0, arrayListOf())
        }
    }

    fun setOnSelectionChangeListener(listener: OnSelectionChangeListener) {
        selectionChangeListener = listener
    }

    fun setOnConvertRequestedListener(listener: OnConvertRequestedListener) {
        convertRequestedListener = listener
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

        btnConvertPreview = view.findViewById(R.id.btnConvertPreview)
        btnConvertPreview.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                convertRequestedListener?.onConvertRequested()
            }
        }
        updateConvertButton()

        // Setup thumbnail strip
        setupThumbnailStrip()

        // Set initial position
        if (imageUris.isNotEmpty()) {
            previewPager.setCurrentItem(currentPosition, false)
            updateUIForCurrentPosition()
        }
    }

    private fun getCurrentZoomableImageView(): ZoomableImageView? {
        // ViewPager2 is backed by RecyclerView, get the current item's view
        val recyclerView = previewPager.getChildAt(0) as? RecyclerView ?: return null
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
        return viewHolder?.itemView?.findViewById(R.id.pageImage)
    }

    private fun setupThumbnailStrip() {
        thumbnailStripAdapter = ThumbnailStripAdapter { position ->
            previewPager.setCurrentItem(position, true)
        }

        thumbnailStrip.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailStripAdapter
        }

        thumbnailStripAdapter.submitList(imageUris.toList())
        
        // Set initial selection and scroll to it
        if (currentPosition > 0) {
            thumbnailStripAdapter.setSelectedPosition(currentPosition)
            thumbnailStrip.scrollToPosition(currentPosition)
        }
    }

    private fun updateUIForCurrentPosition() {
        val fileName = if (currentPosition < fileNames.size) fileNames[currentPosition] else ""
        previewFileName.text = fileName
        
        // Update thumbnail strip selection
        thumbnailStripAdapter.setSelectedPosition(currentPosition)
        
        // Scroll thumbnail strip to make current item visible
        thumbnailStrip.smoothScrollToPosition(currentPosition)

        // Update navigation buttons visibility
        updateNavigationButtons()

        // Update selection button state
        updateSelectionButton()
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
        updateConvertButton()
        selectionChangeListener?.onSelectionChanged(currentUri, isNowSelected)
    }

    private fun updateConvertButton() {
        val count = selectedUris.size
        btnConvertPreview.isEnabled = count > 0
        btnConvertPreview.text = if (count > 0) {
            "Convert ($count)"
        } else {
            getString(R.string.convert)
        }
    }

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
