package com.raw2dng

import android.os.Environment
import java.io.File

/**
 * Helper to check which files have already been converted.
 * Uses the existence of .dng files in Pictures/Raw2DNG as the source of truth.
 */
object ConvertedFilesHelper {
    
    private val raw2dngDir: File
        get() {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return File(picturesDir, "Raw2DNG")
        }

    /**
     * Check if a RAW file has already been converted.
     * @param originalFileName The original RAW filename (e.g., "DSC_0001.NEF")
     * @return true if a corresponding .dng file exists in Raw2DNG folder
     */
    fun isConverted(originalFileName: String): Boolean {
        val baseName = originalFileName.substringBeforeLast('.')
        val dngFileName = "$baseName.dng"
        return File(raw2dngDir, dngFileName).exists()
    }

    /**
     * Get the set of base filenames (without extension) that have been converted.
     * Useful for batch checking.
     */
    fun getConvertedBaseNames(): Set<String> {
        if (!raw2dngDir.exists()) return emptySet()
        
        return raw2dngDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "dng" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Get output filename for a given input filename.
     * @param originalFileName The original RAW filename (e.g., "DSC_0001.NEF")
     * @return The DNG filename (e.g., "DSC_0001.dng")
     */
    fun getOutputFileName(originalFileName: String): String {
        val baseName = originalFileName.substringBeforeLast('.')
        return "$baseName.dng"
    }
}
