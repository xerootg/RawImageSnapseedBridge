package com.raw2dng

import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Data class for thumbnail strip items with conversion and selection status
 */
data class ThumbnailStripItem(
    val uri: Uri,
    val isConvertedToDng: Boolean = false,
    val isConvertedToJpeg: Boolean = false,
    val isSelected: Boolean = false,
    val fileType: String? = null,  // "DNG", "JPEG", etc. for gallery mode
    val fileSize: Long = 0  // File size in bytes for display
)

class ThumbnailStripAdapter(
    private val onThumbnailClick: (Int) -> Unit,
    private val onSelectionToggle: ((Int) -> Unit)? = null
) : ListAdapter<ThumbnailStripItem, ThumbnailStripAdapter.ViewHolder>(ThumbnailStripDiffCallback()) {

    private var selectedPosition: Int = 0
    private val thumbnailJobs = mutableMapOf<Int, Job>()
    private var showSelectionCheckmarks: Boolean = false
    private var showFileTypeBadge: Boolean = false

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        val selectionBorder: View = itemView.findViewById(R.id.selectionBorder)
        val container: View = itemView.findViewById(R.id.thumbnailContainer)
        val conversionBadge: TextView = itemView.findViewById(R.id.conversionBadge)
        val selectionCheckmark: ImageView = itemView.findViewById(R.id.selectionCheckmark)
        val fileTypeBadge: TextView = itemView.findViewById(R.id.fileTypeBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumbnail_strip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isCurrentPosition = position == selectedPosition

        // Cancel any existing loading job for this position
        thumbnailJobs[position]?.cancel()

        // Reset thumbnail
        holder.thumbnail.setImageResource(R.drawable.ic_raw_file)

        // Show/hide selection border (current viewing position)
        holder.selectionBorder.visibility = if (isCurrentPosition) View.VISIBLE else View.GONE

        // Show selection checkmark (for gallery mode selection)
        holder.selectionCheckmark.visibility = if (showSelectionCheckmarks && item.isSelected) View.VISIBLE else View.GONE

        // Show file type badge (for gallery mode - DNG/JPEG)
        if (showFileTypeBadge && item.fileType != null) {
            holder.fileTypeBadge.text = item.fileType
            holder.fileTypeBadge.visibility = View.VISIBLE
        } else {
            holder.fileTypeBadge.visibility = View.GONE
        }

        // Show conversion badge (for RAW mode conversion status)
        if (!showSelectionCheckmarks) {
            when {
                item.isConvertedToDng && item.isConvertedToJpeg -> {
                    holder.conversionBadge.text = "D/J"
                    holder.conversionBadge.visibility = View.VISIBLE
                }
                item.isConvertedToDng -> {
                    holder.conversionBadge.text = "D"
                    holder.conversionBadge.visibility = View.VISIBLE
                }
                item.isConvertedToJpeg -> {
                    holder.conversionBadge.text = "J"
                    holder.conversionBadge.visibility = View.VISIBLE
                }
                else -> {
                    holder.conversionBadge.visibility = View.GONE
                }
            }
        } else {
            holder.conversionBadge.visibility = View.GONE
        }

        // Load thumbnail asynchronously
        val job = CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                // First try OS thumbnail loading
                val osBitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        holder.itemView.context.contentResolver.loadThumbnail(
                            item.uri,
                            Size(120, 120),
                            null
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
                
                // If OS couldn't load thumbnail, try native extraction
                osBitmap ?: ThumbnailCache.extractAndCacheThumbnail(holder.itemView.context, item.uri, 120)
            }

            bitmap?.let {
                holder.thumbnail.setImageBitmap(it)
            }
        }
        thumbnailJobs[position] = job

        // Click listener
        holder.container.setOnClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION && clickedPosition != selectedPosition) {
                val oldPosition = selectedPosition
                selectedPosition = clickedPosition
                notifyItemChanged(oldPosition)
                notifyItemChanged(selectedPosition)
                onThumbnailClick(clickedPosition)
            }
        }

        // Long-press listener for selection toggle (gallery mode)
        holder.container.setOnLongClickListener {
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition != RecyclerView.NO_POSITION && onSelectionToggle != null) {
                onSelectionToggle.invoke(clickedPosition)
                true
            } else {
                false
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            thumbnailJobs[position]?.cancel()
            thumbnailJobs.remove(position)
        }
    }

    fun setSelectedPosition(position: Int) {
        if (position in 0 until itemCount && position != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
        }
    }

    fun getSelectedPosition(): Int = selectedPosition

    /**
     * Enable/disable showing selection checkmarks on items.
     * When enabled, shows green checkmarks on items where isSelected=true.
     * When disabled, shows conversion badges (D/J) instead.
     */
    fun setShowSelectionCheckmarks(show: Boolean) {
        if (showSelectionCheckmarks != show) {
            showSelectionCheckmarks = show
            notifyDataSetChanged()
        }
    }

    /**
     * Enable/disable showing file type badges (DNG/JPEG) on items.
     * Used in gallery mode to indicate file type.
     */
    fun setShowFileTypeBadge(show: Boolean) {
        if (showFileTypeBadge != show) {
            showFileTypeBadge = show
            notifyDataSetChanged()
        }
    }

    /**
     * Update the selection state of an item at the given position.
     */
    fun updateItemSelection(position: Int, isSelected: Boolean) {
        if (position in 0 until itemCount) {
            val item = getItem(position)
            if (item.isSelected != isSelected) {
                val newItem = item.copy(isSelected = isSelected)
                val newList = currentList.toMutableList()
                newList[position] = newItem
                submitList(newList)
            }
        }
    }

    class ThumbnailStripDiffCallback : DiffUtil.ItemCallback<ThumbnailStripItem>() {
        override fun areItemsTheSame(oldItem: ThumbnailStripItem, newItem: ThumbnailStripItem): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: ThumbnailStripItem, newItem: ThumbnailStripItem): Boolean {
            return oldItem == newItem
        }
    }
}
