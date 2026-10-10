package com.raw2dng

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper class for caching thumbnails extracted from RAW files
 * that the OS cannot natively render (e.g., ORF files).
 * 
 * Implements LRU (Least Recently Used) eviction to prevent unbounded cache growth.
 */
object ThumbnailCache {
    private const val TAG = "ThumbnailCache"
    private const val CACHE_DIR = "thumbnail_cache"
    
    // Maximum cache size: 100MB
    private const val MAX_CACHE_SIZE_BYTES = 100L * 1024L * 1024L
    
    // Eviction target: When cache exceeds max, reduce to 80% to avoid constant eviction
    private const val EVICTION_TARGET_RATIO = 0.8
    
    // Mutex to prevent concurrent eviction
    private val evictionMutex = Mutex()

    // One mutex per cache key so that concurrent requests for the same RAW file
    // (grid + preview strip + pager) extract it once instead of racing on the
    // same cache file.
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    
    private fun getCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * Generate a cache key from a URI
     */
    private fun getCacheKey(uri: Uri): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(uri.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get the cache file for a URI
     */
    private fun getCacheFile(context: Context, uri: Uri): File {
        return File(getCacheDir(context), getCacheKey(uri) + ".jpg")
    }
    
    /**
     * Update the last access time of a cache file (for LRU tracking)
     */
    private fun touchCacheFile(file: File) {
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
        }
    }
    
    /**
     * Check if a cached thumbnail exists for the given URI
     */
    fun hasCachedThumbnail(context: Context, uri: Uri): Boolean {
        return getCacheFile(context, uri).exists()
    }
    
    /**
     * Evict oldest cache files if cache size exceeds the limit.
     * Uses LRU (Least Recently Used) strategy based on file modification time.
     */
    private suspend fun evictIfNeeded(context: Context) = evictionMutex.withLock {
        val cacheDir = getCacheDir(context)
        val files = cacheDir.listFiles() ?: return@withLock
        
        var totalSize = files.sumOf { it.length() }
        
        if (totalSize <= MAX_CACHE_SIZE_BYTES) {
            return@withLock
        }
        
        Log.d(TAG, "Cache size ${totalSize / 1024 / 1024}MB exceeds limit, evicting...")
        
        // Sort by last modified time (oldest first)
        val sortedFiles = files.sortedBy { it.lastModified() }
        
        val targetSize = (MAX_CACHE_SIZE_BYTES * EVICTION_TARGET_RATIO).toLong()
        var evictedCount = 0
        var evictedSize = 0L
        
        for (file in sortedFiles) {
            if (totalSize <= targetSize) {
                break
            }
            
            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
                evictedCount++
                evictedSize += fileSize
            }
        }
        
        Log.d(TAG, "Evicted $evictedCount files (${evictedSize / 1024}KB), cache now ${totalSize / 1024 / 1024}MB")
    }
    
    /**
     * Load a cached thumbnail if it exists
     */
    suspend fun loadCachedThumbnail(context: Context, uri: Uri, maxSize: Int = 256): Bitmap? = 
        withContext(Dispatchers.IO) {
            val cacheFile = getCacheFile(context, uri)
            if (!cacheFile.exists()) {
                return@withContext null
            }
            
            // Update access time for LRU tracking
            touchCacheFile(cacheFile)
            
            try {
                // First, decode bounds to calculate sample size
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                
                // Calculate sample size
                val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxSize)
                
                // Decode with sample size
                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeFile(cacheFile.absolutePath, loadOptions)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached thumbnail", e)
                // Delete corrupt cache file
                cacheFile.delete()
                null
            }
        }
    
    /**
     * Extract and cache a thumbnail from a RAW file using the native RAW backend
     * Returns the bitmap if successful, null otherwise
     */
    suspend fun extractAndCacheThumbnail(
        context: Context,
        uri: Uri,
        maxSize: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = getCacheKey(uri)
        inFlight.getOrPut(key) { Mutex() }.withLock {
            extractAndCacheThumbnailLocked(context, uri, maxSize)
        }
    }

    private suspend fun extractAndCacheThumbnailLocked(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val cacheFile = getCacheFile(context, uri)

        // If already cached, just load it (already rotated)
        if (cacheFile.exists()) {
            return loadCachedThumbnail(context, uri, maxSize)
        }

        // The native backend needs a real path. Prefer the file behind the URI;
        // fall back to a private copy when the provider does not expose one.
        var tempCopy: File? = null
        val filePath = getFilePathFromUri(context, uri) ?: run {
            tempCopy = copyToTempFile(context, uri)
            tempCopy?.absolutePath
        }
        if (filePath == null) {
            Log.e(TAG, "Could not access the file behind URI: $uri")
            return null
        }

        // Extract the preview using the native backend. The backend prefers the
        // camera's embedded JPEG and limits the longest edge so the cache stays small.
        val result = try {
            DNGConverter.instance.extractPreview(
                filePath, cacheFile.absolutePath, DNGConverter.DEFAULT_PREVIEW_MAX_DIMENSION
            )
        } finally {
            tempCopy?.delete()
        }

        if (result.success) {
            Log.d(TAG, "Extracted preview for $filePath (orientation=${result.orientation}, " +
                "embedded=${result.fromEmbeddedJpeg}, ${result.width}x${result.height})")
            
            // Check if we need to evict old entries
            evictIfNeeded(context)
            
            // If the backend left the pixels in sensor orientation, bake the rotation
            // into the cached file so every consumer gets an upright image.
            if (result.orientation != 1 && result.orientation != 0) {
                val fullBitmap = loadBitmapFullSize(cacheFile)
                if (fullBitmap != null) {
                    val rotated = applyExifOrientation(fullBitmap, result.orientation)
                    // Save rotated bitmap back to cache
                    saveBitmapToCache(rotated, cacheFile)
                    // Now load with proper sampling
                    return loadCachedThumbnail(context, uri, maxSize)
                }
            }

            return loadCachedThumbnail(context, uri, maxSize)
        } else {
            Log.e(TAG, "Failed to extract thumbnail: ${result.errorMessage}")
            return null
        }
    }

    /**
     * Copy the content behind [uri] into a private temp file for the native
     * backend. Returns null when the URI cannot be opened.
     */
    private fun copyToTempFile(context: Context, uri: Uri): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val temp = File.createTempFile("preview_src_", ".raw", context.cacheDir)
            try {
                input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
            temp
        } catch (e: Exception) {
            Log.e(TAG, "Could not copy $uri for preview extraction", e)
            null
        }
    }

    /**
     * EXIF orientation (1..8) of the image behind [uri], read through the content
     * resolver so that it works for providers that expose no file path.
     */
    fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Decode the image behind [uri] down-sampled so that neither edge exceeds
     * roughly 2 x [maxSize]. Returns null when it cannot be decoded.
     */
    fun decodeSampled(resolver: ContentResolver, uri: Uri, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory decoding $uri")
            null
        }
    }

    /**
     * Load a bitmap at full size (for rotation)
     */
    private fun loadBitmapFullSize(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap for rotation", e)
            null
        }
    }
    
    /**
     * Save a bitmap to the cache file
     */
    private fun saveBitmapToCache(bitmap: Bitmap, file: File) {
        // Write to a sibling and rename so that a reader never sees a half-written file.
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            if (!temp.renameTo(file)) {
                throw java.io.IOException("rename ${temp.name} -> ${file.name} failed")
            }
            Log.d(TAG, "Saved rotated thumbnail to cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving rotated bitmap", e)
            temp.delete()
        }
    }
    
    /**
     * Apply an EXIF orientation (1..8) to a bitmap so that it displays upright.
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)                              // mirror horizontal
            3 -> matrix.postRotate(180f)                                // rotate 180
            4 -> matrix.postScale(1f, -1f)                              // mirror vertical
            5 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }  // transpose
            6 -> matrix.postRotate(90f)                                 // rotate 90 CW
            7 -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) } // transverse
            8 -> matrix.postRotate(270f)                                // rotate 90 CCW
            else -> return bitmap  // No rotation needed
        }
        
        Log.d(TAG, "Applying EXIF orientation $orientation")
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Error applying rotation", e)
            bitmap
        }
    }
    
    /**
     * Get file path from content URI
     */
    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        // Try to get the file path using different methods
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // The DATA column is best effort: it is deprecated, may be
                    // absent, and may name a path this app cannot read.
                    val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                        if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
                    }?.takeIf { File(it).canRead() }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file path from URI", e)
            null
        }
    }
    
    /**
     * Calculate sample size for downscaling
     */
    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSize * 2 || height / sampleSize > maxSize * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }
    
    /**
     * Clear all cached thumbnails
     */
    fun clearCache(context: Context) {
        val cacheDir = getCacheDir(context)
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }
    
    /**
     * Get cache size in bytes
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = getCacheDir(context)
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * Get number of cached thumbnails
     */
    fun getCacheCount(context: Context): Int {
        val cacheDir = getCacheDir(context)
        return cacheDir.listFiles()?.size ?: 0
    }
    
    /**
     * Get formatted cache size string (e.g., "45.2 MB")
     */
    fun getFormattedCacheSize(context: Context): String {
        val bytes = getCacheSize(context)
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
