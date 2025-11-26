package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
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
    private val onThumbnailClick: (RawFileItem) -> Unit
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
            selectedUris.add(item.uri)
        }
        notifyDataSetChanged()
    }

    fun selectAll(items: List<RawFileItem>) {
        selectedUris.clear()
        items.forEach { selectedUris.add(it.uri) }
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

            // Show converted badge
            if (item.isConverted) {
                convertedBadge.visibility = View.VISIBLE
                itemView.alpha = 0.6f
            } else {
                convertedBadge.visibility = View.GONE
                itemView.alpha = 1.0f
            }

            // Checkbox state
            val isSelected = selectedUris.contains(item.uri)
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

            // Thumbnail click opens preview
            thumbnail.setOnClickListener {
                onThumbnailClick(item)
            }

            // Row click toggles selection
            itemView.setOnClickListener {
                onSelectionToggle(item)
            }

            // Checkbox click toggles selection
            checkbox.setOnClickListener {
                onSelectionToggle(item)
            }
        }

        fun cancelLoading() {
            loadJob?.cancel()
            loadJob = null
        }

        private suspend fun loadThumbnail(item: RawFileItem): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val contentResolver = itemView.context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use loadThumbnail for Android 10+
                    contentResolver.loadThumbnail(item.uri, Size(128, 128), null)
                } else {
                    // For older Android, extract ID from URI and use deprecated method
                    val id = android.content.ContentUris.parseId(item.uri)
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        contentResolver,
                        id,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
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
