package com.raw2dng

import android.net.Uri
import com.raw2dng.source.RawEntry

/**
 * Data class representing a RAW image file in the picker.
 *
 * [uri] is how the bytes are read (MediaStore, SAF document, or the app's own
 * source provider); [entry] is the source entry it came from and [fingerprint]
 * its identity in the conversion ledger.
 */
data class RawFileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val path: String,
    var isConvertedToDng: Boolean,
    var isConvertedToJpeg: Boolean,
    var isSelected: Boolean = false,
    var isDimmed: Boolean = false,  // True when item is shown grayed out (already converted in current filter mode)
    val entry: RawEntry? = null,
    val fingerprint: String? = null
) {
    /**
     * Get the base name without extension.
     */
    val baseName: String
        get() = name.substringBeforeLast('.')
    
    /**
     * Check if converted to any format (for backwards compatibility)
     */
    val isConverted: Boolean
        get() = isConvertedToDng || isConvertedToJpeg

    /**
     * Get a human-readable file size.
     */
    val formattedSize: String
        get() {
            val kb = size / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$size B"
            }
        }
}
