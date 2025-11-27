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
        
        // Chroma subsampling options for JPEG encoding
        // Higher subsampling = smaller file but color fringing artifacts
        const val CHROMA_SUBSAMPLING_444 = 0  // No subsampling - best quality, largest file
        const val CHROMA_SUBSAMPLING_422 = 1  // Horizontal subsampling only - medium quality
        const val CHROMA_SUBSAMPLING_420 = 2  // H and V subsampling - smallest file, most artifacts
        
        // High quality defaults for export
        const val DEFAULT_QUALITY = 95
        const val DEFAULT_SUBSAMPLING = CHROMA_SUBSAMPLING_444
        const val DEFAULT_OPTIMIZE = true
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
     * @param quality JPEG quality (1-100, higher = better quality, larger file)
     * @param chromaSubsampling Chroma subsampling mode (CHROMA_SUBSAMPLING_444/422/420)
     * @param optimizeCoding Enable Huffman table optimization (smaller file, same quality)
     * @return Empty string if successful, error message otherwise
     */
    external fun convertToJPEG(
        inputPath: String, 
        outputPath: String, 
        quality: Int = DEFAULT_QUALITY,
        chromaSubsampling: Int = DEFAULT_SUBSAMPLING,
        optimizeCoding: Boolean = DEFAULT_OPTIMIZE
    ): String

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
