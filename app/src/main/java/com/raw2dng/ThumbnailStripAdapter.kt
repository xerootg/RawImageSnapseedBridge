package com.raw2dng

import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThumbnailStripAdapter(
    private val onThumbnailClick: (Int) -> Unit
) : ListAdapter<Uri, ThumbnailStripAdapter.ViewHolder>(UriDiffCallback()) {

    private var selectedPosition: Int = 0
    private val thumbnailJobs = mutableMapOf<Int, Job>()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        val selectionBorder: View = itemView.findViewById(R.id.selectionBorder)
        val container: View = itemView.findViewById(R.id.thumbnailContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumbnail_strip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = getItem(position)
        val isSelected = position == selectedPosition

        // Cancel any existing loading job for this position
        thumbnailJobs[position]?.cancel()

        // Reset thumbnail
        holder.thumbnail.setImageResource(R.drawable.ic_raw_file)

        // Show/hide selection border
        holder.selectionBorder.visibility = if (isSelected) View.VISIBLE else View.GONE

        // Load thumbnail asynchronously
        val job = CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        holder.itemView.context.contentResolver.loadThumbnail(
                            uri,
                            Size(120, 120),
                            null
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
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

    class UriDiffCallback : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }
    }
}
