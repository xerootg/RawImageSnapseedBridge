package com.raw2dng

/**
 * JNI wrapper for DNG conversion using Adobe DNG SDK
 */
class DNGConverter {

    companion object {
        init {
            System.loadLibrary("raw2dng")
        }
        
        // Singleton instance for thumbnail extraction
        val instance = DNGConverter()
    }

    /**
     * Convert a RAW file to DNG format
     * @param inputPath Path to input RAW file
     * @param outputPath Path for output DNG file
     * @return Empty string if successful, error message otherwise
     */
    external fun convertToDNG(inputPath: String, outputPath: String): String

    /**
     * Extract or generate a thumbnail/preview from a RAW file
     * @param inputPath Path to input RAW file  
     * @param outputPath Path for output JPEG/PPM file
     * @return Empty string if successful, error message otherwise
     */
    external fun extractThumbnail(inputPath: String, outputPath: String): String

    /**
     * Check if Adobe DNG SDK is available
     * @return true if SDK is compiled in, false otherwise
     */
    external fun isSDKAvailable(): Boolean

    /**
     * Get SDK version information
     * @return SDK version string
     */
    external fun getSDKVersion(): String
}
