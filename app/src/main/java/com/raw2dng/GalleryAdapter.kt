package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
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
 * Format file size in human-readable format (KB, MB, GB)
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateModified: Long,
    val fileSize: Long = 0
)

class GalleryAdapter(
    private val onItemClick: (GalleryItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onDoubleTap: (GalleryItem) -> Unit
) : ListAdapter<GalleryItem, GalleryAdapter.ViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    var isMultiSelectMode = false
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoading()
    }
    
    fun getSelectedItems(): List<GalleryItem> {
        return currentList.filter { selectedIds.contains(it.id) }
    }
    
    fun getSelectedCount(): Int = selectedIds.size
    
    fun getAllItems(): List<GalleryItem> = currentList
    
    fun clearSelection() {
        selectedIds.clear()
        isMultiSelectMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }
    
    /**
     * Update the selection state based on a set of selected URIs.
     * Called after the preview dialog is closed to sync selection state.
     */
    fun setSelectionFromUris(selectedUriSet: Set<Uri>) {
        selectedIds.clear()
        currentList.forEach { item ->
            if (selectedUriSet.contains(item.uri)) {
                selectedIds.add(item.id)
            }
        }
        isMultiSelectMode = selectedIds.isNotEmpty()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }
    
    private fun toggleSelection(item: GalleryItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        
        // Exit multi-select if no items selected
        if (selectedIds.isEmpty()) {
            isMultiSelectMode = false
        }
        
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }
    
    private fun startMultiSelect(item: GalleryItem) {
        isMultiSelectMode = true
        selectedIds.add(item.id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val fileSize: TextView = itemView.findViewById(R.id.fileSize)
        private val checkbox: CheckBox = itemView.findViewById(R.id.selectionCheckbox)
        private var loadJob: Job? = null

        fun bind(item: GalleryItem) {
            fileName.text = item.name
            fileSize.text = formatFileSize(item.fileSize)
            
            // Show/hide checkbox based on multi-select mode
            checkbox.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            checkbox.isChecked = selectedIds.contains(item.id)
            
            // Visual feedback for selection
            itemView.alpha = if (isMultiSelectMode && selectedIds.contains(item.id)) 0.7f else 1.0f
            
            // Cancel any previous loading job
            loadJob?.cancel()
            
            // Set placeholder immediately
            thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            
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
                    if (isMultiSelectMode) {
                        toggleSelection(item)
                    } else {
                        onItemClick(item)
                    }
                    return true
                }
                
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTap(item)
                    return true
                }
                
                override fun onLongPress(e: MotionEvent) {
                    if (!isMultiSelectMode) {
                        startMultiSelect(item)
                    }
                }
            })
            
            itemView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        fun cancelLoading() {
            loadJob?.cancel()
            loadJob = null
        }

        private suspend fun loadThumbnail(item: GalleryItem): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val contentResolver = itemView.context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use loadThumbnail for Android 10+
                    contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
                } else {
                    // For older Android, use deprecated method
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        contentResolver,
                        item.id,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GalleryItem>() {
        override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
            return oldItem == newItem
        }
    }
}
