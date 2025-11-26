package com.raw2dng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImagePagerAdapter(
    private val context: Context,
    private val imageUris: List<Uri>
) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private val loadJobs = mutableMapOf<Int, Job>()
    private var onZoomChangeListener: ((Float) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.pageImage)
        val progressBar: ProgressBar = view.findViewById(R.id.pageProgress)
    }

    fun setOnZoomChangeListener(listener: (Float) -> Unit) {
        onZoomChangeListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preview_page, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = imageUris[position]

        // Cancel any existing load job for this position
        loadJobs[position]?.cancel()

        // Reset state
        holder.imageView.resetZoom()
        holder.imageView.setImageBitmap(null)
        holder.progressBar.visibility = View.VISIBLE

        // Set zoom listener
        holder.imageView.setOnZoomChangeListener { scale ->
            onZoomChangeListener?.invoke(scale)
        }

        // Load image asynchronously
        loadJobs[position] = CoroutineScope(Dispatchers.Main).launch {
            val bitmap = loadFullResolutionImage(uri)
            holder.progressBar.visibility = View.GONE

            if (bitmap != null) {
                holder.imageView.setImageBitmap(bitmap)
            } else {
                holder.imageView.setImageResource(R.drawable.ic_raw_file)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // Cancel load job when view is recycled
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            loadJobs[position]?.cancel()
            loadJobs.remove(position)
        }
    }

    override fun getItemCount(): Int = imageUris.size

    private suspend fun loadFullResolutionImage(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // First, get the image dimensions without loading the full image
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            val imageWidth = options.outWidth
            val imageHeight = options.outHeight

            // If we got valid dimensions, the OS can read this format
            if (imageWidth > 0 && imageHeight > 0) {
                // Calculate sample size to avoid OutOfMemory for very large images
                val maxDimension = 4096
                var sampleSize = 1

                if (imageWidth > maxDimension || imageHeight > maxDimension) {
                    val widthRatio = imageWidth.toFloat() / maxDimension
                    val heightRatio = imageHeight.toFloat() / maxDimension
                    sampleSize = kotlin.math.max(widthRatio, heightRatio).toInt()
                }

                // Load the image with the calculated sample size
                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, loadOptions)
                }
            } else {
                // OS can't read this format, try native extraction
                // Use larger size for full preview
                ThumbnailCache.extractAndCacheThumbnail(context, uri, 2048)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Try native extraction as fallback
            try {
                ThumbnailCache.extractAndCacheThumbnail(context, uri, 2048)
            } catch (e2: Exception) {
                e2.printStackTrace()
                null
            }
        }
    }

    fun cancelAllLoads() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
    }
}
