package com.raw2dng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

                var bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, loadOptions)
                }
                
                // Apply EXIF rotation for DNG files
                // BitmapFactory doesn't automatically apply EXIF orientation
                if (bitmap != null) {
                    bitmap = applyExifRotation(uri, bitmap)
                }
                
                bitmap
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
    
    /**
     * Apply EXIF rotation to a bitmap if needed.
     * BitmapFactory.decodeStream() doesn't apply EXIF orientation automatically.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            // Get the file path from the URI
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val filePath = cursor.getString(columnIndex)
                    if (filePath != null && File(filePath).exists()) {
                        val exif = ExifInterface(filePath)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        return rotateBitmap(bitmap, orientation)
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through and return original bitmap
        }
        return bitmap
    }
    
    /**
     * Rotate a bitmap based on EXIF orientation value.
     */
    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // ORIENTATION_NORMAL or undefined
        }
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    fun cancelAllLoads() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
    }
}
