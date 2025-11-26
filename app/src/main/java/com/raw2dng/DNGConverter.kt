package com.raw2dng

/**
 * JNI wrapper for DNG conversion using Adobe DNG SDK
 */
class DNGConverter {

    companion object {
        init {
            System.loadLibrary("raw2dng")
        }
        
        // Singleton instance for thumbnail extraction and JPEG conversion
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
     * Convert a RAW file to JPEG format
     * @param inputPath Path to input RAW file  
     * @param outputPath Path for output JPEG file
     * @param quality JPEG quality (1-100, default 90)
     * @return Empty string if successful, error message otherwise
     */
    external fun convertToJPEG(inputPath: String, outputPath: String, quality: Int = 90): String

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
