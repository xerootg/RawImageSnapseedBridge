package com.raw2dng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
     * Extract and cache a thumbnail from a RAW file using LibRaw
     * Returns the bitmap if successful, null otherwise
     */
    suspend fun extractAndCacheThumbnail(
        context: Context, 
        uri: Uri, 
        maxSize: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile(context, uri)
        
        // If already cached, just load it
        if (cacheFile.exists()) {
            return@withContext loadCachedThumbnail(context, uri, maxSize)
        }
        
        // Get the file path from the URI
        val filePath = getFilePathFromUri(context, uri)
        if (filePath == null) {
            Log.e(TAG, "Could not get file path from URI: $uri")
            return@withContext null
        }
        
        // Extract thumbnail using native code
        val result = DNGConverter.instance.extractThumbnail(filePath, cacheFile.absolutePath)
        
        if (result.isEmpty()) {
            // Success - check if we need to evict old entries
            evictIfNeeded(context)
            
            Log.d(TAG, "Successfully extracted thumbnail for: $filePath")
            loadCachedThumbnail(context, uri, maxSize)
        } else {
            Log.e(TAG, "Failed to extract thumbnail: $result")
            null
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
                    // Query for DATA column
                    val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                            cursor.getString(columnIndex)
                        } else null
                    }
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
