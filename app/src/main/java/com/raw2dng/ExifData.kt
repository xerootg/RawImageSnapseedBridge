package com.raw2dng

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing EXIF/metadata for an image file.
 */
data class ExifData(
    val make: String = "",
    val model: String = "",
    val lensMake: String = "",
    val lensModel: String = "",
    val focalLength: Float = 0f,
    val aperture: Float = 0f,
    val shutterSpeed: String = "",
    val shutterRaw: Float = 0f,
    val iso: Int = 0,
    val dateTime: String = "",
    val timestampRaw: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val orientation: Int = 0,
    val fileSize: Long = 0,
    val fileName: String = "",
    val fileType: String = "",
    val error: String? = null
) {
    /**
     * Get camera string (make + model)
     */
    val camera: String
        get() = if (make.isNotEmpty() && model.isNotEmpty()) {
            if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
        } else model.ifEmpty { make }
    
    /**
     * Get lens string (lens make + model)
     */
    val lens: String
        get() = if (lensMake.isNotEmpty() && lensModel.isNotEmpty()) {
            if (lensModel.startsWith(lensMake, ignoreCase = true)) lensModel else "$lensMake $lensModel"
        } else lensModel.ifEmpty { lensMake }
    
    /**
     * Get formatted aperture string
     */
    val apertureString: String
        get() = if (aperture > 0) String.format("f/%.1f", aperture) else ""
    
    /**
     * Get formatted focal length string
     */
    val focalLengthString: String
        get() = if (focalLength > 0) String.format("%.0fmm", focalLength) else ""
    
    /**
     * Get formatted ISO string
     */
    val isoString: String
        get() = if (iso > 0) "ISO $iso" else ""
    
    /**
     * Get formatted dimensions string
     */
    val dimensionsString: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else ""
    
    /**
     * Get formatted raw dimensions string (sensor size)
     */
    val rawDimensionsString: String
        get() = if (rawWidth > 0 && rawHeight > 0 && (rawWidth != width || rawHeight != height)) {
            "${rawWidth}×${rawHeight}"
        } else ""
    
    companion object {
        /**
         * Extract metadata from a RAW file using LibRaw JNI
         */
        fun extractFromRaw(filePath: String, fileName: String = "", fileSize: Long = 0): ExifData {
            val converter = DNGConverter.instance
            val jsonStr = converter.extractMetadata(filePath)
            
            return try {
                val json = JSONObject(jsonStr)
                
                // Check for error
                if (json.has("error")) {
                    return ExifData(
                        fileName = fileName,
                        fileSize = fileSize,
                        error = json.getString("error")
                    )
                }
                
                ExifData(
                    make = json.optString("make", ""),
                    model = json.optString("model", ""),
                    lensMake = json.optString("lens_make", ""),
                    lensModel = json.optString("lens_model", ""),
                    focalLength = json.optDouble("focal_length", 0.0).toFloat(),
                    aperture = json.optDouble("aperture", 0.0).toFloat(),
                    shutterSpeed = json.optString("shutter", ""),
                    shutterRaw = json.optDouble("shutter_raw", 0.0).toFloat(),
                    iso = json.optDouble("iso", 0.0).toInt(),
                    dateTime = json.optString("timestamp", ""),
                    timestampRaw = json.optLong("timestamp_raw", 0),
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0),
                    rawWidth = json.optInt("raw_width", 0),
                    rawHeight = json.optInt("raw_height", 0),
                    orientation = json.optInt("orientation", 0),
                    fileSize = fileSize,
                    fileName = fileName,
                    fileType = File(filePath).extension.uppercase()
                )
            } catch (e: Exception) {
                ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    error = "Failed to parse metadata: ${e.message}"
                )
            }
        }
        
        /**
         * Extract metadata from a DNG/JPEG file using Android's ExifInterface
         */
        fun extractFromFile(context: Context, uri: Uri, fileName: String = "", fileSize: Long = 0): ExifData {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return ExifData(fileName = fileName, fileSize = fileSize, error = "Cannot open file")
                
                val exif = ExifInterface(inputStream)
                inputStream.close()
                
                // Get dimensions from EXIF or from attributes
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                
                // Parse focal length (can be rational like "50/1")
                val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat()
                
                // Parse aperture (F-number)
                val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).toFloat()
                
                // Parse exposure time
                val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                val shutterSpeed = formatShutterSpeed(exposureTime)
                
                // Parse ISO
                val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                
                // Parse date/time
                val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: ""
                
                // Parse orientation
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                
                // Get file extension for type
                val fileType = fileName.substringAfterLast('.', "").uppercase()
                
                ExifData(
                    make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "",
                    model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "",
                    lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE) ?: "",
                    lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL) ?: "",
                    focalLength = focalLength,
                    aperture = aperture,
                    shutterSpeed = shutterSpeed,
                    shutterRaw = exposureTime.toFloat(),
                    iso = iso,
                    dateTime = formatExifDateTime(dateTimeOriginal),
                    width = width,
                    height = height,
                    orientation = orientation,
                    fileSize = fileSize,
                    fileName = fileName,
                    fileType = fileType
                )
            } catch (e: Exception) {
                ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    error = "Failed to read EXIF: ${e.message}"
                )
            }
        }
        
        /**
         * Format shutter speed as a fraction or decimal
         */
        private fun formatShutterSpeed(seconds: Double): String {
            return when {
                seconds <= 0 -> ""
                seconds < 1.0 -> "1/${(1.0 / seconds).toInt()}"
                else -> String.format("%.1fs", seconds)
            }
        }
        
        /**
         * Format EXIF date/time string to a more readable format
         */
        private fun formatExifDateTime(exifDateTime: String): String {
            if (exifDateTime.isEmpty()) return ""
            return try {
                val inputFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val date = inputFormat.parse(exifDateTime)
                if (date != null) outputFormat.format(date) else exifDateTime
            } catch (e: Exception) {
                exifDateTime
            }
        }
    }
}
