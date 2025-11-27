package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Status of a conversion item in the thumbnail grid
 */
enum class ConversionItemStatus {
    PENDING,            // Waiting to be converted
    NEEDS_CONFIRMATION, // Already converted, needs user tap to confirm overwrite
    IN_PROGRESS,        // Currently being converted
    SUCCESS,            // Conversion completed successfully
    ERROR               // Conversion failed
}

/**
 * Data class for a conversion thumbnail item
 */
data class ConversionThumbnailItem(
    val uri: Uri,
    val fileName: String,
    var status: ConversionItemStatus = ConversionItemStatus.PENDING,
    var errorMessage: String = "",
    val needsOverwrite: Boolean = false  // True if file already exists and will overwrite
)

/**
 * Adapter for displaying conversion progress as a thumbnail grid.
 * Each item shows the file thumbnail with an overlay indicating conversion status.
 */
class ConversionThumbnailAdapter(
    private val onItemClick: ((ConversionThumbnailItem) -> Unit)? = null
) : RecyclerView.Adapter<ConversionThumbnailAdapter.ViewHolder>() {

    private val items = mutableListOf<ConversionThumbnailItem>()
    private val thumbnailJobs = mutableMapOf<Int, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversion_thumbnail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoading()
    }

    /**
     * Set the list of items to convert
     */
    fun setItems(newItems: List<ConversionThumbnailItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Update the status of an item by URI
     */
    fun updateStatus(uri: Uri, status: ConversionItemStatus, errorMessage: String = "") {
        val index = items.indexOfFirst { it.uri == uri }
        if (index >= 0) {
            items[index].status = status
            items[index].errorMessage = errorMessage
            notifyItemChanged(index)
        }
    }

    /**
     * Mark an item as in-progress
     */
    fun markInProgress(uri: Uri) {
        updateStatus(uri, ConversionItemStatus.IN_PROGRESS)
    }

    /**
     * Mark an item as successful
     */
    fun markSuccess(uri: Uri) {
        updateStatus(uri, ConversionItemStatus.SUCCESS)
    }

    /**
     * Mark an item as failed
     */
    fun markError(uri: Uri, errorMessage: String) {
        updateStatus(uri, ConversionItemStatus.ERROR, errorMessage)
    }
    
    /**
     * Confirm an overwrite - changes status from NEEDS_CONFIRMATION to PENDING
     */
    fun confirmOverwrite(uri: Uri) {
        updateStatus(uri, ConversionItemStatus.PENDING)
    }

    /**
     * Get the current progress (completed count)
     */
    fun getCompletedCount(): Int {
        return items.count { it.status == ConversionItemStatus.SUCCESS || it.status == ConversionItemStatus.ERROR }
    }
    
    /**
     * Get count of items still needing overwrite confirmation
     */
    fun getNeedsConfirmationCount(): Int {
        return items.count { it.status == ConversionItemStatus.NEEDS_CONFIRMATION }
    }
    
    /**
     * Alias for getNeedsConfirmationCount for backward compatibility
     */
    fun getPendingOverwriteCount(): Int = getNeedsConfirmationCount()
    
    /**
     * Set the callback for when an overwrite is confirmed
     */
    fun setOnOverwriteConfirmed(callback: (Uri) -> Unit) {
        onOverwriteConfirmed = callback
    }
    
    private var onOverwriteConfirmed: ((Uri) -> Unit)? = null
    
    /**
     * Get an item by URI
     */
    fun getItem(uri: Uri): ConversionThumbnailItem? {
        return items.find { it.uri == uri }
    }

    /**
     * Clear all items
     */
    fun clear() {
        thumbnailJobs.values.forEach { it.cancel() }
        thumbnailJobs.clear()
        items.clear()
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.conversionThumbnail)
        private val progressOverlay: View = itemView.findViewById(R.id.progressOverlay)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.itemProgressBar)
        private val statusIcon: ImageView = itemView.findViewById(R.id.statusIcon)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val overwriteBadge: TextView = itemView.findViewById(R.id.overwriteBadge)
        
        private var loadJob: Job? = null
        private var currentPosition: Int = -1

        fun bind(item: ConversionThumbnailItem, position: Int) {
            currentPosition = position
            fileName.text = item.fileName

            // Cancel previous loading job
            cancelLoading()

            // Set placeholder
            thumbnail.setImageResource(R.drawable.ic_raw_file)

            // Load thumbnail asynchronously
            loadJob = CoroutineScope(Dispatchers.Main).launch {
                val bitmap = loadThumbnail(item.uri)
                if (bitmap != null) {
                    thumbnail.setImageBitmap(bitmap)
                }
            }
            thumbnailJobs[position] = loadJob!!
            
            // Set click listener for confirmation items
            itemView.setOnClickListener {
                if (item.status == ConversionItemStatus.NEEDS_CONFIRMATION) {
                    onItemClick?.invoke(item)
                    onOverwriteConfirmed?.invoke(item.uri)
                }
            }

            // Update UI based on status
            overwriteBadge.visibility = View.GONE  // Reset badge visibility
            
            when (item.status) {
                ConversionItemStatus.PENDING -> {
                    progressOverlay.visibility = View.VISIBLE
                    progressOverlay.alpha = 0.5f
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.GONE
                }
                ConversionItemStatus.NEEDS_CONFIRMATION -> {
                    progressOverlay.visibility = View.VISIBLE
                    progressOverlay.alpha = 0.6f
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.GONE
                    overwriteBadge.visibility = View.VISIBLE
                }
                ConversionItemStatus.IN_PROGRESS -> {
                    progressOverlay.visibility = View.VISIBLE
                    progressOverlay.alpha = 0.7f
                    progressBar.visibility = View.VISIBLE
                    statusIcon.visibility = View.GONE
                }
                ConversionItemStatus.SUCCESS -> {
                    progressOverlay.visibility = View.VISIBLE
                    progressOverlay.alpha = 0.3f
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(R.drawable.ic_check_circle)
                    statusIcon.setColorFilter(0xFF4CAF50.toInt()) // Green
                }
                ConversionItemStatus.ERROR -> {
                    progressOverlay.visibility = View.VISIBLE
                    progressOverlay.alpha = 0.6f
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(R.drawable.ic_error)
                    statusIcon.setColorFilter(0xFFF44336.toInt()) // Red
                }
            }
        }

        fun cancelLoading() {
            loadJob?.cancel()
            loadJob = null
            if (currentPosition >= 0) {
                thumbnailJobs.remove(currentPosition)
            }
        }

        private suspend fun loadThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val contentResolver = itemView.context.contentResolver
                
                // Try OS thumbnail first
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        contentResolver.loadThumbnail(uri, Size(128, 128), null)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    try {
                        val id = android.content.ContentUris.parseId(uri)
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver,
                            id,
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                // Fallback to ThumbnailCache if OS can't load
                bitmap ?: ThumbnailCache.extractAndCacheThumbnail(itemView.context, uri, 128)
            } catch (e: Exception) {
                null
            }
        }
    }
}
