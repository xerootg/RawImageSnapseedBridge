package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateModified: Long
)

class GalleryAdapter(
    private val onItemClick: (GalleryItem) -> Unit
) : ListAdapter<GalleryItem, GalleryAdapter.ViewHolder>(DiffCallback()) {

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

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private var loadJob: Job? = null

        fun bind(item: GalleryItem) {
            fileName.text = item.name
            
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

            itemView.setOnClickListener {
                onItemClick(item)
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
