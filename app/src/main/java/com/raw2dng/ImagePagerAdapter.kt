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
import android.widget.TextView
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
    private val imageUris: List<Uri>,
    private val fileNames: List<String> = emptyList(),
    private val fileSizes: List<Long> = emptyList()
) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private val loadJobs = mutableMapOf<Int, Job>()
    private val exifJobs = mutableMapOf<Int, Job>()
    private var onZoomChangeListener: ((Float) -> Unit)? = null
    private var currentZoomScale: Float = 1f
    private val boundViewHolders = mutableMapOf<Int, ViewHolder>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.pageImage)
        val progressBar: ProgressBar = view.findViewById(R.id.pageProgress)
        val exifOverlay: TextView = view.findViewById(R.id.pageExifOverlay)
    }

    fun setOnZoomChangeListener(listener: (Float) -> Unit) {
        onZoomChangeListener = listener
    }
    
    /**
     * Update zoom scale and show/hide EXIF overlays accordingly
     */
    fun updateZoomState(scale: Float) {
        currentZoomScale = scale
        val shouldShow = scale <= 1.05f  // Small tolerance for floating point
        boundViewHolders.values.forEach { holder ->
            holder.exifOverlay.visibility = if (shouldShow && holder.exifOverlay.text.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preview_page, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = imageUris[position]
        
        // Track bound view holders
        boundViewHolders[position] = holder

        // Cancel any existing load job for this position
        loadJobs[position]?.cancel()
        exifJobs[position]?.cancel()

        // Reset state
        holder.imageView.resetZoom()
        holder.imageView.setImageBitmap(null)
        holder.progressBar.visibility = View.VISIBLE
        holder.exifOverlay.text = ""
        holder.exifOverlay.visibility = View.GONE

        // Set zoom listener
        holder.imageView.setOnZoomChangeListener { scale ->
            currentZoomScale = scale
            onZoomChangeListener?.invoke(scale)
            // Show overlay only when zoomed out (scale <= 1.0)
            holder.exifOverlay.visibility = if (scale <= 1.05f && holder.exifOverlay.text.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
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
        
        // Load EXIF data asynchronously
        exifJobs[position] = CoroutineScope(Dispatchers.Main).launch {
            val exifText = loadExifOverlayText(uri, position)
            if (exifText.isNotEmpty()) {
                holder.exifOverlay.text = exifText
                // Show overlay only if currently zoomed out
                if (currentZoomScale <= 1.05f) {
                    holder.exifOverlay.visibility = View.VISIBLE
                }
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
            exifJobs[position]?.cancel()
            exifJobs.remove(position)
            boundViewHolders.remove(position)
        }
    }

    override fun getItemCount(): Int = imageUris.size
    
    /**
     * Load and format EXIF data for the overlay display
     * Line 1: Filename
     * Line 2: Camera model
     * Line 3: Lens info, file size, aperture
     * Line 4: Date/time taken
     */
    private suspend fun loadExifOverlayText(uri: Uri, position: Int): String = withContext(Dispatchers.IO) {
        try {
            val fileName = fileNames.getOrElse(position) { getFileNameFromUri(uri) }
            val fileSize = fileSizes.getOrElse(position) { getFileSizeFromUri(uri) }
            
            val exifData = ExifData.extractFromUri(context, uri, fileName, fileSize)
            
            val sb = StringBuilder()
            
            // Line 1: Filename
            sb.append(fileName)
            
            // Line 2: Camera model
            if (exifData.camera.isNotEmpty()) {
                sb.append("\n")
                sb.append(exifData.camera)
            }
            
            // Line 3: Lens, size, aperture (compact format)
            val line3Parts = mutableListOf<String>()
            
            // Lens or focal length
            if (exifData.lens.isNotEmpty()) {
                line3Parts.add(exifData.lens)
            } else if (exifData.focalLengthString.isNotEmpty()) {
                line3Parts.add(exifData.focalLengthString)
            }
            
            // File size
            if (fileSize > 0) {
                line3Parts.add(formatFileSize(fileSize))
            }
            
            // Aperture
            if (exifData.apertureString.isNotEmpty()) {
                line3Parts.add(exifData.apertureString)
            }
            
            if (line3Parts.isNotEmpty()) {
                sb.append("\n")
                sb.append(line3Parts.joinToString(" • "))
            }
            
            // Line 4: Date/time
            if (exifData.dateTime.isNotEmpty()) {
                sb.append("\n")
                sb.append(exifData.dateTime)
            }
            
            sb.toString()
        } catch (e: Exception) {
            // Fallback to just filename
            val fileName = fileNames.getOrElse(position) { getFileNameFromUri(uri) }
            fileName
        }
    }
    
    private fun getFileNameFromUri(uri: Uri): String {
        var name = ""
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                name = cursor.getString(columnIndex) ?: ""
            }
        }
        return name.ifEmpty { uri.lastPathSegment ?: "" }
    }
    
    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        val projection = arrayOf(MediaStore.Images.Media.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                size = cursor.getLong(columnIndex)
            }
        }
        return size
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

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
                // Power-of-two sample size, rounded up so that neither edge exceeds
                // maxDimension. BitmapFactory rounds other values down, and the old
                // float->int truncation allowed bitmaps of up to 4x the intended size.
                val maxDimension = 4096
                var sampleSize = 1
                while (imageWidth / sampleSize > maxDimension || imageHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
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
            android.util.Log.w("ImagePagerAdapter", "BitmapFactory could not decode image, trying native extraction", e)
            fallbackToNativePreview(uri)
        } catch (e: OutOfMemoryError) {
            // A huge image can still exhaust the heap; the native preview is bounded.
            android.util.Log.w("ImagePagerAdapter", "Out of memory decoding image, trying native extraction")
            fallbackToNativePreview(uri)
        }
    }

    private suspend fun fallbackToNativePreview(uri: Uri): Bitmap? {
        return try {
            ThumbnailCache.extractAndCacheThumbnail(context, uri, 2048)
        } catch (e: Exception) {
            android.util.Log.e("ImagePagerAdapter", "Native preview extraction failed", e)
            null
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("ImagePagerAdapter", "Out of memory loading native preview")
            null
        }
    }
    
    /**
     * Apply EXIF rotation to a bitmap if needed.
     * BitmapFactory.decodeStream() doesn't apply EXIF orientation automatically.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = ThumbnailCache.readExifOrientation(context, uri)
        return if (orientation == ExifInterface.ORIENTATION_NORMAL) bitmap else rotateBitmap(bitmap, orientation)
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
