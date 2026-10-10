package com.raw2dng

import android.util.Log

/**
 * Kotlin facade over the native RAW backend (see app/src/main/cpp/raw_backend.h).
 *
 * The native library exposes a backend-neutral interface; which RAW library
 * implements it is a build-time decision of the native code. All results are
 * returned as small data classes so callers never have to parse strings.
 */
class DNGConverter {

    /** Outcome of a JPEG export. */
    data class JpegResult(
        val success: Boolean,
        val errorMessage: String = "",
        /** true when the camera's embedded JPEG was reused instead of rendering the sensor data. */
        val usedEmbeddedJpeg: Boolean = false,
        /** EXIF orientation (1..8) recorded in the output file; 1 = pixels already upright. */
        val orientation: Int = 1,
        val width: Int = 0,
        val height: Int = 0,
        /** true when the backend already wrote EXIF metadata (including orientation) into the JPEG. */
        val exifEmbedded: Boolean = false
    )

    /** Outcome of a preview extraction. */
    data class PreviewResult(
        val success: Boolean,
        val errorMessage: String = "",
        /** EXIF orientation (1..8) the caller still has to apply; 1 = already upright. */
        val orientation: Int = 1,
        val fromEmbeddedJpeg: Boolean = false,
        val width: Int = 0,
        val height: Int = 0
    )

    companion object {
        private const val TAG = "DNGConverter"

        init {
            System.loadLibrary("raw2dng")
        }

        // Singleton instance for thumbnail extraction and JPEG conversion
        val instance = DNGConverter()

        // Chroma subsampling options for JPEG encoding (mirrors raw2dng::ChromaSubsampling)
        const val CHROMA_SUBSAMPLING_444 = 0  // No subsampling - best quality, largest file
        const val CHROMA_SUBSAMPLING_422 = 1  // Horizontal subsampling only - medium quality
        const val CHROMA_SUBSAMPLING_420 = 2  // H and V subsampling - smallest file, most artifacts

        // Embedded JPEG policy for JPEG export (mirrors raw2dng::EmbeddedJpegPolicy)
        const val EMBEDDED_JPEG_NEVER = 0               // Always render from sensor data
        const val EMBEDDED_JPEG_FULL_RESOLUTION_ONLY = 1 // Reuse the camera JPEG only if it is full resolution
        const val EMBEDDED_JPEG_ALWAYS = 2              // Reuse any embedded JPEG

        // High quality defaults for export
        const val DEFAULT_QUALITY = 95
        const val DEFAULT_SUBSAMPLING = CHROMA_SUBSAMPLING_444
        const val DEFAULT_OPTIMIZE = true
        const val DEFAULT_EMBEDDED_POLICY = EMBEDDED_JPEG_FULL_RESOLUTION_ONLY

        /** Longest edge used for cached previews/thumbnails. */
        const val DEFAULT_PREVIEW_MAX_DIMENSION = 2048

        /**
         * Lower-case extensions (without dot) the native backend can decode.
         */
        val supportedExtensions: Set<String> by lazy {
            instance.nativeSupportedExtensions()
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        /** Human readable name of the native backend, e.g. "dnglab/rawler". */
        val backendName: String by lazy { instance.nativeBackendName() }

        /** Version string of the native backend. */
        val backendVersion: String by lazy { instance.nativeBackendVersion() }

        private fun parseDimensions(dims: String): Pair<Int, Int> {
            val parts = dims.split('x')
            if (parts.size != 2) return 0 to 0
            return (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
        }
    }

    // --- native entry points (see raw2dng_jni.cpp) ---
    private external fun nativeConvertToDNG(
        inputPath: String, outputPath: String,
        embedOriginal: Boolean, preview: Boolean, thumbnail: Boolean, lossless: Boolean
    ): String

    private external fun nativeConvertToJPEG(
        inputPath: String, outputPath: String,
        quality: Int, chromaSubsampling: Int, optimizeCoding: Boolean,
        embeddedPolicy: Int, embedExif: Boolean
    ): String

    private external fun nativeExtractPreview(inputPath: String, outputPath: String, maxDimension: Int): String
    private external fun nativeExtractMetadata(inputPath: String): String
    private external fun nativeBackendName(): String
    private external fun nativeBackendVersion(): String
    private external fun nativeSupportedExtensions(): String

    /**
     * Convert a RAW file to DNG format.
     * @return Empty string if successful, error message otherwise
     */
    fun convertToDNG(
        inputPath: String,
        outputPath: String,
        embedOriginal: Boolean = false,
        preview: Boolean = true,
        thumbnail: Boolean = true,
        lossless: Boolean = true
    ): String {
        val result = nativeConvertToDNG(inputPath, outputPath, embedOriginal, preview, thumbnail, lossless)
        return if (result == "ok") "" else result
    }

    /**
     * Export a RAW file to JPEG.
     *
     * Depending on [embeddedPolicy] the camera's embedded full-resolution JPEG is
     * copied (fast, in-camera look) or the sensor data is rendered and encoded
     * with the given quality/subsampling/Huffman settings.
     */
    fun convertToJPEG(
        inputPath: String,
        outputPath: String,
        quality: Int = DEFAULT_QUALITY,
        chromaSubsampling: Int = DEFAULT_SUBSAMPLING,
        optimizeCoding: Boolean = DEFAULT_OPTIMIZE,
        embeddedPolicy: Int = DEFAULT_EMBEDDED_POLICY,
        embedExif: Boolean = true
    ): JpegResult {
        val raw = nativeConvertToJPEG(
            inputPath, outputPath, quality, chromaSubsampling, optimizeCoding, embeddedPolicy, embedExif
        )
        if (!raw.startsWith("ok:")) {
            return JpegResult(success = false, errorMessage = raw)
        }
        // ok:<usedEmbedded>:<orientation>:<w>x<h>:<exifEmbedded>
        val parts = raw.split(':')
        val (w, h) = parseDimensions(parts.getOrNull(3) ?: "")
        return JpegResult(
            success = true,
            usedEmbeddedJpeg = parts.getOrNull(1) == "1",
            orientation = parts.getOrNull(2)?.toIntOrNull() ?: 1,
            width = w,
            height = h,
            exifEmbedded = parts.getOrNull(4) == "1"
        )
    }

    /**
     * Extract (or render) a JPEG preview of a RAW file.
     * @param maxDimension longest edge limit, 0 = embedded image as-is
     */
    fun extractPreview(inputPath: String, outputPath: String, maxDimension: Int = DEFAULT_PREVIEW_MAX_DIMENSION): PreviewResult {
        val raw = nativeExtractPreview(inputPath, outputPath, maxDimension)
        if (!raw.startsWith("ok:")) {
            Log.w(TAG, "extractPreview failed for $inputPath: $raw")
            return PreviewResult(success = false, errorMessage = raw)
        }
        // ok:<orientation>:<fromEmbedded>:<w>x<h>
        val parts = raw.split(':')
        val (w, h) = parseDimensions(parts.getOrNull(3) ?: "")
        return PreviewResult(
            success = true,
            orientation = parts.getOrNull(1)?.toIntOrNull() ?: 1,
            fromEmbeddedJpeg = parts.getOrNull(2) == "1",
            width = w,
            height = h
        )
    }

    /**
     * Extract metadata from a RAW file as a JSON string.
     * @return JSON string with metadata, or JSON with "error" field on failure
     */
    fun extractMetadata(inputPath: String): String = nativeExtractMetadata(inputPath)
}
