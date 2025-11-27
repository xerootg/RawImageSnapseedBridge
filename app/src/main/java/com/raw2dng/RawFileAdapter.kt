package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for displaying RAW files in a grid with multi-select support.
 */
class RawFileAdapter(
    private val onSelectionToggle: (RawFileItem) -> Unit,
    private val onDoubleTap: (RawFileItem) -> Unit
) : ListAdapter<RawFileItem, RawFileAdapter.ViewHolder>(DiffCallback()) {

    private val selectedUris = mutableSetOf<Uri>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_raw_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoading()
    }

    fun getSelectedItems(): List<RawFileItem> {
        return currentList.filter { selectedUris.contains(it.uri) }
    }

    fun toggleSelection(item: RawFileItem) {
        if (selectedUris.contains(item.uri)) {
            selectedUris.remove(item.uri)
        } else {
            // Don't allow selection of dimmed items
            if (!item.isDimmed) {
                selectedUris.add(item.uri)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll(items: List<RawFileItem>) {
        selectedUris.clear()
        // Only select non-dimmed items
        items.filter { !it.isDimmed }.forEach { selectedUris.add(it.uri) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedUris.clear()
        notifyDataSetChanged()
    }

    fun addToSelection(item: RawFileItem) {
        if (!selectedUris.contains(item.uri)) {
            selectedUris.add(item.uri)
            notifyDataSetChanged()
        }
    }

    fun removeFromSelection(item: RawFileItem) {
        if (selectedUris.contains(item.uri)) {
            selectedUris.remove(item.uri)
            notifyDataSetChanged()
        }
    }

    fun getSelectedCount(): Int = selectedUris.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.rawThumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.rawFileName)
        private val fileInfo: TextView = itemView.findViewById(R.id.rawFileInfo)
        private val checkbox: CheckBox = itemView.findViewById(R.id.rawCheckbox)
        private val convertedBadge: TextView = itemView.findViewById(R.id.convertedBadge)
        private var loadJob: Job? = null

        fun bind(item: RawFileItem) {
            fileName.text = item.name
            fileInfo.text = item.formattedSize

            // Handle dimmed state (already converted, shown grayed out)
            if (item.isDimmed) {
                // Heavily dimmed - not selectable
                itemView.alpha = 0.35f
                checkbox.visibility = View.GONE
                
                // Still show the badge
                when {
                    item.isConvertedToDng && item.isConvertedToJpeg -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "DNG, JPEG"
                    }
                    item.isConvertedToDng -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "DNG"
                    }
                    item.isConvertedToJpeg -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "JPEG"
                    }
                    else -> {
                        convertedBadge.visibility = View.GONE
                    }
                }
            } else {
                // Normal state - show converted badge with slight dim
                checkbox.visibility = View.VISIBLE
                when {
                    item.isConvertedToDng && item.isConvertedToJpeg -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "DNG, JPEG"
                        itemView.alpha = 0.6f
                    }
                    item.isConvertedToDng -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "DNG"
                        itemView.alpha = 0.6f
                    }
                    item.isConvertedToJpeg -> {
                        convertedBadge.visibility = View.VISIBLE
                        convertedBadge.text = "JPEG"
                        itemView.alpha = 0.6f
                    }
                    else -> {
                        convertedBadge.visibility = View.GONE
                        itemView.alpha = 1.0f
                    }
                }
            }

            // Checkbox state - only if not dimmed
            val isSelected = !item.isDimmed && selectedUris.contains(item.uri)
            checkbox.isChecked = isSelected

            // Cancel any previous loading job
            loadJob?.cancel()
            
            // Set placeholder immediately
            thumbnail.setImageResource(R.drawable.ic_raw_file)
            
            // Load thumbnail asynchronously
            loadJob = CoroutineScope(Dispatchers.Main).launch {
                val bitmap = loadThumbnail(item)
                if (bitmap != null) {
                    thumbnail.setImageBitmap(bitmap)
                }
            }

            // Use GestureDetector for double-tap detection
            val gestureDetector = GestureDetector(itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Don't allow selection of dimmed items
                    if (!item.isDimmed) {
                        onSelectionToggle(item)
                    }
                    return true
                }
                
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Allow preview of any item, even dimmed ones
                    onDoubleTap(item)
                    return true
                }
            })
            
            // Apply gesture detector to thumbnail and item
            thumbnail.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
            
            itemView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }

            // Checkbox click toggles selection directly (no double-tap needed)
            // Only if not dimmed
            checkbox.setOnClickListener {
                if (!item.isDimmed) {
                    onSelectionToggle(item)
                }
            }
        }

        fun cancelLoading() {
            loadJob?.cancel()
            loadJob = null
        }

        private suspend fun loadThumbnail(item: RawFileItem): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val contentResolver = itemView.context.contentResolver
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use loadThumbnail for Android 10+
                    try {
                        contentResolver.loadThumbnail(item.uri, Size(128, 128), null)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    // For older Android, extract ID from URI and use deprecated method
                    try {
                        val id = android.content.ContentUris.parseId(item.uri)
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver,
                            id,
                            MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // If OS couldn't load thumbnail, try native extraction
                if (bitmap == null) {
                    ThumbnailCache.extractAndCacheThumbnail(itemView.context, item.uri, 128)
                } else {
                    bitmap
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RawFileItem>() {
        override fun areItemsTheSame(oldItem: RawFileItem, newItem: RawFileItem): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: RawFileItem, newItem: RawFileItem): Boolean {
            return oldItem == newItem
        }
    }
}
