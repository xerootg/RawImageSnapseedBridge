package com.raw2dng

import android.net.Uri

/**
 * Data class representing a RAW image file in the picker.
 */
data class RawFileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val path: String,
    val isConverted: Boolean,
    var isSelected: Boolean = false
) {
    /**
     * Get the base name without extension.
     */
    val baseName: String
        get() = name.substringBeforeLast('.')

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
