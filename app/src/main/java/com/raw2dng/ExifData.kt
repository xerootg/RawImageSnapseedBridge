package com.raw2dng

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Data class representing complete EXIF/metadata for an image file.
 * All metadata is extracted via LibRaw JNI for consistency across RAW, DNG, and JPEG.
 */
data class ExifData(
    // Camera info
    val make: String = "",
    val model: String = "",
    val bodySerial: String = "",
    val software: String = "",
    
    // Lens info
    val lensMake: String = "",
    val lensModel: String = "",
    val lensSerial: String = "",
    val minFocal: Float = 0f,
    val maxFocal: Float = 0f,
    
    // Focal length
    val focalLength: Float = 0f,
    val focalLength35mm: Int = 0,
    
    // Exposure
    val aperture: Float = 0f,
    val shutterSpeed: String = "",
    val shutterRaw: Float = 0f,
    val iso: Int = 0,
    val exposureProgram: Int = -1,
    val meteringMode: Int = -1,
    
    // Date/time
    val dateTime: String = "",
    val timestampRaw: Long = 0,
    
    // Image dimensions
    val width: Int = 0,
    val height: Int = 0,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val orientation: Int = 0,
    
    // Color info
    val colors: Int = 0,
    val bayerPattern: String = "",
    
    // GPS
    val hasGps: Boolean = false,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
    val gpsAltitude: Float = 0f,
    val gpsLatRef: String = "",
    val gpsLonRef: String = "",
    
    // Other
    val description: String = "",
    val artist: String = "",
    
    // File info (set by caller)
    val fileSize: Long = 0,
    val fileName: String = "",
    val fileType: String = "",
    
    // Error info
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
     * Get formatted 35mm equivalent focal length string
     */
    val focalLength35mmString: String
        get() = if (focalLength35mm > 0) "${focalLength35mm}mm (35mm equiv)" else ""
    
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
    
    /**
     * Get exposure program name
     */
    val exposureProgramString: String
        get() = when (exposureProgram) {
            0 -> "Not defined"
            1 -> "Manual"
            2 -> "Program"
            3 -> "Aperture priority"
            4 -> "Shutter priority"
            5 -> "Creative"
            6 -> "Action"
            7 -> "Portrait"
            8 -> "Landscape"
            else -> ""
        }
    
    /**
     * Get metering mode name
     */
    val meteringModeString: String
        get() = when (meteringMode) {
            0 -> "Unknown"
            1 -> "Average"
            2 -> "Center-weighted"
            3 -> "Spot"
            4 -> "Multi-spot"
            5 -> "Matrix"
            6 -> "Partial"
            else -> ""
        }
    
    /**
     * Get lens focal range string
     */
    val lensRangeString: String
        get() = when {
            minFocal > 0 && maxFocal > 0 && minFocal != maxFocal -> 
                "${minFocal.toInt()}-${maxFocal.toInt()}mm"
            minFocal > 0 -> "${minFocal.toInt()}mm"
            maxFocal > 0 -> "${maxFocal.toInt()}mm"
            else -> ""
        }
    
    /**
     * Get GPS coordinates string
     */
    val gpsString: String
        get() = if (hasGps) {
            String.format("%.6f°%s, %.6f°%s", 
                kotlin.math.abs(gpsLatitude), gpsLatRef,
                kotlin.math.abs(gpsLongitude), gpsLonRef)
        } else ""
    
    /**
     * Get GPS altitude string
     */
    val gpsAltitudeString: String
        get() = if (hasGps && gpsAltitude != 0f) {
            String.format("%.1fm", gpsAltitude)
        } else ""
    
    companion object {
        private const val TAG = "ExifData"
        
        // RAW file extensions that LibRaw can handle (includes DNG)
        private val RAW_EXTENSIONS = setOf(
            "3fr", "arw", "cr2", "cr3", "dcr", "dng", "erf", "iiq", "k25", "kdc",
            "mef", "mos", "nef", "nrw", "orf", "pef", "raf", "rw2", "sr2", "srf"
        )
        
        /**
         * Check if the file extension indicates a RAW file that LibRaw can read.
         */
        private fun isRawFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return RAW_EXTENSIONS.contains(ext)
        }
        
        /**
         * Extract metadata from a RAW file using LibRaw JNI.
         * 
         * @param filePath Path to the RAW file
         * @param fileName Display name for the file
         * @param fileSize File size in bytes
         * @return ExifData with extracted metadata
         */
        private fun extractFromRaw(filePath: String, fileName: String = "", fileSize: Long = 0): ExifData {
            val converter = DNGConverter.instance
            val jsonStr = converter.extractMetadata(filePath)
            
            return try {
                parseMetadataJson(jsonStr, fileName, fileSize, File(filePath).extension.uppercase())
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to parse RAW metadata: ${e.message}", e)
                ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    fileType = File(filePath).extension.uppercase(),
                    error = "Failed to parse metadata: ${e.message}"
                )
            }
        }
        
        /**
         * Extract metadata from a non-RAW file (JPEG) using ExifInterface.
         * 
         * @param filePath Path to the file
         * @param fileName Display name for the file
         * @param fileSize File size in bytes
         * @return ExifData with extracted metadata
         */
        private fun extractFromNonRaw(filePath: String, fileName: String = "", fileSize: Long = 0): ExifData {
            return try {
                val exif = ExifInterface(filePath)
                val fileType = File(filePath).extension.uppercase()
                
                // Parse exposure time
                val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                val shutterSpeed = formatShutterSpeed(exposureTime)
                
                // Parse GPS
                val latLong = FloatArray(2)
                val hasGps = exif.getLatLong(latLong)
                
                ExifData(
                    // Camera
                    make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "",
                    model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "",
                    software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "",
                    
                    // Lens
                    lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE) ?: "",
                    lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL) ?: "",
                    
                    // Focal length
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat(),
                    focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0),
                    
                    // Exposure
                    aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).toFloat(),
                    shutterSpeed = shutterSpeed,
                    shutterRaw = exposureTime.toFloat(),
                    iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
                    exposureProgram = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1),
                    meteringMode = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1),
                    
                    // Date/time
                    dateTime = formatExifDateTime(
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: ""
                    ),
                    
                    // Dimensions
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
                    
                    // GPS
                    hasGps = hasGps,
                    gpsLatitude = if (hasGps) latLong[0].toDouble() else 0.0,
                    gpsLongitude = if (hasGps) latLong[1].toDouble() else 0.0,
                    gpsAltitude = exif.getAltitude(0.0).toFloat(),
                    gpsLatRef = if (hasGps && latLong[0] >= 0) "N" else "S",
                    gpsLonRef = if (hasGps && latLong[1] >= 0) "E" else "W",
                    
                    // Other
                    artist = exif.getAttribute(ExifInterface.TAG_ARTIST) ?: "",
                    description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: "",
                    
                    // File info
                    fileSize = fileSize,
                    fileName = fileName,
                    fileType = fileType
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to read EXIF: ${e.message}", e)
                ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    fileType = File(filePath).extension.uppercase(),
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
                // EXIF format is "YYYY:MM:DD HH:MM:SS", convert to "YYYY-MM-DD HH:MM:SS"
                exifDateTime.replaceFirst(":", "-").replaceFirst(":", "-")
            } catch (e: Exception) {
                exifDateTime
            }
        }
        
        /**
         * Extract metadata from a content URI.
         * Uses LibRaw for RAW/DNG files and ExifInterface for JPEG.
         * 
         * @param context Android context for content resolver
         * @param uri Content URI of the file
         * @param fileName Display name for the file
         * @param fileSize File size in bytes
         * @return ExifData with extracted metadata
         */
        fun extractFromUri(context: Context, uri: Uri, fileName: String = "", fileSize: Long = 0): ExifData {
            val isRaw = isRawFile(fileName)
            
            return try {
                if (isRaw) {
                    // RAW/DNG file - copy to temp and use LibRaw
                    val extension = fileName.substringAfterLast('.', "raw").lowercase()
                    val tempFile = File(context.cacheDir, "exif_temp_${System.currentTimeMillis()}.$extension")
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return ExifData(
                        fileName = fileName,
                        fileSize = fileSize,
                        error = "Cannot open file"
                    )
                    
                    val result = extractFromRaw(tempFile.absolutePath, fileName, fileSize)
                    tempFile.delete()
                    result
                } else {
                    // JPEG or other non-RAW - copy to temp and use ExifInterface
                    val extension = fileName.substringAfterLast('.', "jpg").lowercase()
                    val tempFile = File(context.cacheDir, "exif_temp_${System.currentTimeMillis()}.$extension")
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return ExifData(
                        fileName = fileName,
                        fileSize = fileSize,
                        error = "Cannot open file"
                    )
                    
                    val result = extractFromNonRaw(tempFile.absolutePath, fileName, fileSize)
                    tempFile.delete()
                    result
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to extract from URI: ${e.message}", e)
                ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    error = "Failed to read file: ${e.message}"
                )
            }
        }
        
        /**
         * Extract raw JSON metadata from a content URI.
         * For RAW/DNG files, returns the LibRaw JSON directly.
         * For JPEG files, builds a JSON from ExifInterface data.
         * 
         * @param context Android context for content resolver
         * @param uri Content URI of the file
         * @param fileName Display name for the file
         * @return Pretty-printed JSON string
         */
        fun extractRawJsonFromUri(context: Context, uri: Uri, fileName: String = ""): String {
            val isRaw = isRawFile(fileName)
            
            return try {
                val extension = fileName.substringAfterLast('.', if (isRaw) "raw" else "jpg").lowercase()
                val tempFile = File(context.cacheDir, "exif_temp_${System.currentTimeMillis()}.$extension")
                
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return "{\"error\": \"Cannot open file\"}"
                
                val jsonStr = if (isRaw) {
                    // RAW/DNG - use LibRaw
                    val converter = DNGConverter.instance
                    converter.extractMetadata(tempFile.absolutePath)
                } else {
                    // JPEG - build JSON from ExifInterface
                    buildJsonFromExifInterface(tempFile.absolutePath)
                }
                
                tempFile.delete()
                
                // Pretty print the JSON
                try {
                    val json = JSONObject(jsonStr)
                    json.toString(2) // indent with 2 spaces
                } catch (e: Exception) {
                    jsonStr
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to extract JSON from URI: ${e.message}", e)
                "{\"error\": \"${e.message?.replace("\"", "\\\"") ?: "Unknown error"}\"}"
            }
        }
        
        /**
         * Build a JSON string from ExifInterface data for non-RAW files.
         */
        private fun buildJsonFromExifInterface(filePath: String): String {
            val exif = ExifInterface(filePath)
            val json = JSONObject()
            
            // Camera
            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { json.put("make", it) }
            exif.getAttribute(ExifInterface.TAG_MODEL)?.let { json.put("model", it) }
            exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.let { json.put("software", it) }
            
            // Lens
            exif.getAttribute(ExifInterface.TAG_LENS_MAKE)?.let { json.put("lens_make", it) }
            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { json.put("lens_model", it) }
            
            // Focal length
            val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (focalLength > 0) json.put("focal_length", focalLength)
            val focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            if (focalLength35mm > 0) json.put("focal_length_35mm", focalLength35mm)
            
            // Exposure
            val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (aperture > 0) json.put("aperture", aperture)
            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (exposureTime > 0) {
                json.put("shutter_raw", exposureTime)
                json.put("shutter", formatShutterSpeed(exposureTime))
            }
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            if (iso > 0) json.put("iso", iso)
            val exposureProgram = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1)
            if (exposureProgram >= 0) json.put("exposure_program", exposureProgram)
            val meteringMode = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)
            if (meteringMode >= 0) json.put("metering_mode", meteringMode)
            
            // Date/time
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { json.put("timestamp", it) }
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)?.let { json.put("timestamp", it) }
            
            // Dimensions
            val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            if (width > 0) json.put("width", width)
            val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (height > 0) json.put("height", height)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            if (orientation > 0) json.put("orientation", orientation)
            
            // GPS
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                json.put("has_gps", true)
                json.put("gps_latitude", latLong[0].toDouble())
                json.put("gps_longitude", latLong[1].toDouble())
                json.put("gps_altitude", exif.getAltitude(0.0))
            } else {
                json.put("has_gps", false)
            }
            
            // Other
            exif.getAttribute(ExifInterface.TAG_ARTIST)?.let { json.put("artist", it) }
            exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { json.put("description", it) }
            
            return json.toString()
        }
        
        /**
         * Parse the JSON metadata string from LibRaw into an ExifData object.
         */
        private fun parseMetadataJson(
            jsonStr: String, 
            fileName: String, 
            fileSize: Long,
            fileType: String
        ): ExifData {
            val json = JSONObject(jsonStr)
            
            // Check for error
            if (json.has("error")) {
                return ExifData(
                    fileName = fileName,
                    fileSize = fileSize,
                    fileType = fileType,
                    error = json.getString("error")
                )
            }
            
            // Parse GPS coordinates into decimal degrees
            val hasGps = json.optBoolean("has_gps", false)
            var gpsLat = 0.0
            var gpsLon = 0.0
            var gpsLatRef = ""
            var gpsLonRef = ""
            
            if (hasGps) {
                val latDeg = json.optDouble("gps_lat_deg", 0.0)
                val latMin = json.optDouble("gps_lat_min", 0.0)
                val latSec = json.optDouble("gps_lat_sec", 0.0)
                gpsLatRef = json.optString("gps_lat_ref", "N")
                gpsLat = latDeg + latMin / 60.0 + latSec / 3600.0
                if (gpsLatRef == "S") gpsLat = -gpsLat
                
                val lonDeg = json.optDouble("gps_lon_deg", 0.0)
                val lonMin = json.optDouble("gps_lon_min", 0.0)
                val lonSec = json.optDouble("gps_lon_sec", 0.0)
                gpsLonRef = json.optString("gps_lon_ref", "E")
                gpsLon = lonDeg + lonMin / 60.0 + lonSec / 3600.0
                if (gpsLonRef == "W") gpsLon = -gpsLon
            }
            
            return ExifData(
                // Camera
                make = json.optString("make", ""),
                model = json.optString("model", ""),
                bodySerial = json.optString("body_serial", ""),
                software = json.optString("software", ""),
                
                // Lens
                lensMake = json.optString("lens_make", ""),
                lensModel = json.optString("lens_model", ""),
                lensSerial = json.optString("lens_serial", ""),
                minFocal = json.optDouble("min_focal", 0.0).toFloat(),
                maxFocal = json.optDouble("max_focal", 0.0).toFloat(),
                
                // Focal length
                focalLength = json.optDouble("focal_length", 0.0).toFloat(),
                focalLength35mm = json.optInt("focal_length_35mm", 0),
                
                // Exposure
                aperture = json.optDouble("aperture", 0.0).toFloat(),
                shutterSpeed = json.optString("shutter", ""),
                shutterRaw = json.optDouble("shutter_raw", 0.0).toFloat(),
                iso = json.optDouble("iso", 0.0).toInt(),
                exposureProgram = json.optInt("exposure_program", -1),
                meteringMode = json.optInt("metering_mode", -1),
                
                // Date/time
                dateTime = json.optString("timestamp", ""),
                timestampRaw = json.optLong("timestamp_raw", 0),
                
                // Dimensions
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                rawWidth = json.optInt("raw_width", 0),
                rawHeight = json.optInt("raw_height", 0),
                orientation = json.optInt("orientation", 0),
                
                // Color
                colors = json.optInt("colors", 0),
                bayerPattern = json.optString("bayer_pattern", ""),
                
                // GPS
                hasGps = hasGps,
                gpsLatitude = gpsLat,
                gpsLongitude = gpsLon,
                gpsAltitude = json.optDouble("gps_altitude", 0.0).toFloat(),
                gpsLatRef = gpsLatRef,
                gpsLonRef = gpsLonRef,
                
                // Other
                description = json.optString("description", ""),
                artist = json.optString("artist", ""),
                
                // File info
                fileSize = fileSize,
                fileName = fileName,
                fileType = fileType
            )
        }
        
        /**
         * Write EXIF metadata from a RAW file to a JPEG file.
         * Extracts metadata from the source RAW and writes it to the destination JPEG.
         * 
         * @param rawFilePath Path to the source RAW file
         * @param jpegFilePath Path to the destination JPEG file
         * @return true on success, false on failure
         */
        fun writeExifToJpeg(rawFilePath: String, jpegFilePath: String): Boolean {
            return try {
                // Extract metadata from RAW file
                val converter = DNGConverter.instance
                val jsonStr = converter.extractMetadata(rawFilePath)
                val json = JSONObject(jsonStr)
                
                if (json.has("error")) {
                    android.util.Log.e("ExifData", "Failed to extract metadata: ${json.getString("error")}")
                    return false
                }
                
                // Open JPEG file for EXIF writing
                val exif = ExifInterface(jpegFilePath)
                
                // Camera info
                val make = json.optString("make", "")
                val model = json.optString("model", "")
                if (make.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_MAKE, make)
                if (model.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_MODEL, model)
                
                // Software
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Raw2DNG")
                
                // Lens info
                val lensMake = json.optString("lens_make", "")
                val lensModel = json.optString("lens_model", "")
                if (lensMake.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_LENS_MAKE, lensMake)
                if (lensModel.isNotEmpty()) exif.setAttribute(ExifInterface.TAG_LENS_MODEL, lensModel)
                
                // Focal length
                val focalLength = json.optDouble("focal_length", 0.0)
                if (focalLength > 0) {
                    // ExifInterface expects focal length as a rational (numerator/denominator)
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(focalLength * 10).toInt()}/10")
                }
                
                // 35mm equivalent focal length
                val focalLength35mm = json.optDouble("focal_length_35mm", 0.0)
                if (focalLength35mm > 0) {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focalLength35mm.toInt().toString())
                }
                
                // Aperture (F-number)
                val aperture = json.optDouble("aperture", 0.0)
                if (aperture > 0) {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, "${(aperture * 10).toInt()}/10")
                }
                
                // Exposure time (shutter speed)
                val shutterRaw = json.optDouble("shutter_raw", 0.0)
                if (shutterRaw > 0) {
                    // Write as rational - for small values use 1/x format
                    if (shutterRaw < 1.0) {
                        val denominator = (1.0 / shutterRaw).toInt()
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/$denominator")
                    } else {
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "${(shutterRaw * 1000).toInt()}/1000")
                    }
                }
                
                // ISO
                val iso = json.optDouble("iso", 0.0).toInt()
                if (iso > 0) {
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                }
                
                // Exposure program
                val exposureProgram = json.optInt("exposure_program", 0)
                if (exposureProgram > 0) {
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, exposureProgram.toString())
                }
                
                // Metering mode
                val meteringMode = json.optInt("metering_mode", 0)
                if (meteringMode > 0) {
                    exif.setAttribute(ExifInterface.TAG_METERING_MODE, meteringMode.toString())
                }
                
                // Date/time
                val timestamp = json.optString("timestamp", "")
                if (timestamp.isNotEmpty()) {
                    // Convert from "YYYY-MM-DD HH:MM:SS" to EXIF format "YYYY:MM:DD HH:MM:SS"
                    val exifTimestamp = timestamp.replace("-", ":")
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifTimestamp)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTimestamp)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifTimestamp)
                }
                
                // Artist
                val artist = json.optString("artist", "")
                if (artist.isNotEmpty()) {
                    exif.setAttribute(ExifInterface.TAG_ARTIST, artist)
                }
                
                // Description
                val description = json.optString("description", "")
                if (description.isNotEmpty()) {
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                }
                
                // GPS data
                val hasGps = json.optBoolean("has_gps", false)
                if (hasGps) {
                    val latDeg = json.optDouble("gps_lat_deg", 0.0)
                    val latMin = json.optDouble("gps_lat_min", 0.0)
                    val latSec = json.optDouble("gps_lat_sec", 0.0)
                    val latRef = json.optString("gps_lat_ref", "N")
                    
                    val lonDeg = json.optDouble("gps_lon_deg", 0.0)
                    val lonMin = json.optDouble("gps_lon_min", 0.0)
                    val lonSec = json.optDouble("gps_lon_sec", 0.0)
                    val lonRef = json.optString("gps_lon_ref", "E")
                    
                    // Format latitude as "deg/1,min/1,sec*1000/1000"
                    val latStr = "${latDeg.toInt()}/1,${latMin.toInt()}/1,${(latSec * 1000).toInt()}/1000"
                    val lonStr = "${lonDeg.toInt()}/1,${lonMin.toInt()}/1,${(lonSec * 1000).toInt()}/1000"
                    
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latStr)
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, lonStr)
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)
                    
                    // Altitude
                    val altitude = json.optDouble("gps_altitude", 0.0)
                    val altRef = json.optInt("gps_alt_ref", 0)
                    if (altitude != 0.0) {
                        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${(kotlin.math.abs(altitude) * 100).toInt()}/100")
                        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altRef.toString())
                    }
                }
                
                // Save the EXIF data
                exif.saveAttributes()
                
                android.util.Log.d("ExifData", "Successfully wrote EXIF to $jpegFilePath")
                true
            } catch (e: Exception) {
                android.util.Log.e("ExifData", "Failed to write EXIF: ${e.message}", e)
                false
            }
        }
    }
}
