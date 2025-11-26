package com.raw2dng

import android.os.Environment
import java.io.File

/**
 * Helper to check which files have already been converted.
 * Uses the existence of .dng/.jpg files in Pictures/Raw2DNG as the source of truth.
 */
object ConvertedFilesHelper {
    
    private val raw2dngDir: File
        get() {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return File(picturesDir, "Raw2DNG")
        }
    
    private val jpegDir: File
        get() = File(raw2dngDir, "JPEG")

    /**
     * Check if a RAW file has already been converted to DNG.
     * @param originalFileName The original RAW filename (e.g., "DSC_0001.NEF")
     * @return true if a corresponding .dng file exists in Raw2DNG folder
     */
    fun isConvertedToDng(originalFileName: String): Boolean {
        val baseName = originalFileName.substringBeforeLast('.')
        val dngFileName = "$baseName.dng"
        return File(raw2dngDir, dngFileName).exists()
    }
    
    /**
     * Check if a RAW file has already been converted to JPEG.
     * @param originalFileName The original RAW filename (e.g., "DSC_0001.NEF")
     * @return true if a corresponding .jpg file exists in Raw2DNG/JPEG folder
     */
    fun isConvertedToJpeg(originalFileName: String): Boolean {
        val baseName = originalFileName.substringBeforeLast('.')
        val jpgFileName = "$baseName.jpg"
        return File(jpegDir, jpgFileName).exists()
    }
    
    /**
     * Legacy method - check if converted to either format
     */
    fun isConverted(originalFileName: String): Boolean {
        return isConvertedToDng(originalFileName) || isConvertedToJpeg(originalFileName)
    }

    /**
     * Get the set of base filenames (without extension) that have been converted to DNG.
     */
    fun getDngConvertedBaseNames(): Set<String> {
        if (!raw2dngDir.exists()) return emptySet()
        
        return raw2dngDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "dng" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }
    
    /**
     * Get the set of base filenames (without extension) that have been converted to JPEG.
     */
    fun getJpegConvertedBaseNames(): Set<String> {
        if (!jpegDir.exists()) return emptySet()
        
        return jpegDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Get output filename for a given input filename.
     * @param originalFileName The original RAW filename (e.g., "DSC_0001.NEF")
     * @param format The output format
     * @return The output filename (e.g., "DSC_0001.dng" or "DSC_0001.jpg")
     */
    fun getOutputFileName(originalFileName: String, format: OutputFormat): String {
        val baseName = originalFileName.substringBeforeLast('.')
        return when (format) {
            OutputFormat.DNG -> "$baseName.dng"
            OutputFormat.JPEG -> "$baseName.jpg"
        }
    }
}
