package com.raw2dng

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Helper for the output folder layout (Pictures/Raw2DNG and Raw2DNG/JPEG).
 *
 * Historically "is this RAW converted?" was answered purely by whether a
 * `<base>.dng` / `<base>.jpg` exists here. That is now only the *legacy
 * fallback*: the conversion ledger (`com.raw2dng.ledger.ConversionLedger`)
 * is the source of truth, keyed by file identity rather than name, and it
 * adopts pre-ledger outputs through [findOutputUri] the first time it sees
 * them.
 */
object ConvertedFilesHelper {

    val DNG_RELATIVE_PATH: String = "${Environment.DIRECTORY_PICTURES}/Raw2DNG"
    val JPEG_RELATIVE_PATH: String = "${Environment.DIRECTORY_PICTURES}/Raw2DNG/JPEG"

    fun relativePathFor(format: OutputFormat): String = when (format) {
        OutputFormat.DNG -> DNG_RELATIVE_PATH
        OutputFormat.JPEG -> JPEG_RELATIVE_PATH
    }

    fun mimeTypeFor(format: OutputFormat): String = when (format) {
        OutputFormat.DNG -> "image/x-adobe-dng"
        OutputFormat.JPEG -> "image/jpeg"
    }

    /**
     * The URI of an existing output named like the conversion of [originalFileName],
     * or null. Used to adopt outputs produced before the ledger existed.
     */
    fun findOutputUri(context: Context, originalFileName: String, format: OutputFormat): Uri? {
        val outputName = getOutputFileName(originalFileName, format)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Images.Media._ID), selection,
                arrayOf(outputName, relativePathFor(format) + "/"), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    return Uri.withAppendedPath(collection, c.getLong(0).toString())
                }
            }
            return null
        }
        val dir = if (format == OutputFormat.DNG) raw2dngDir else jpegDir
        val f = File(dir, outputName)
        return if (f.exists()) Uri.fromFile(f) else null
    }
    
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
