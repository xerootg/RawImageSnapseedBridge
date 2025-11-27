// libraw_reader.h
// Wrapper around LibRaw for reading proprietary RAW formats

#ifndef LIBRAW_READER_H
#define LIBRAW_READER_H

#include <string>
#include <cstdint>
#include <memory>

// Forward declare LibRaw to avoid header pollution
class LibRaw;

namespace raw2dng {

// Chroma subsampling options for JPEG encoding
// Higher subsampling = smaller file but color fringing artifacts
enum class ChromaSubsampling {
    SUBSAMP_444 = 0,  // No subsampling - best quality, largest file
    SUBSAMP_422 = 1,  // Horizontal subsampling only - medium quality
    SUBSAMP_420 = 2   // H and V subsampling - smallest file, most artifacts
};

// JPEG encoding settings for fine-grained control
struct JpegEncodingSettings {
    int quality = 95;                                              // Quality 1-100 (higher = better)
    ChromaSubsampling subsampling = ChromaSubsampling::SUBSAMP_444; // Chroma subsampling
    bool optimizeCoding = true;                                    // Huffman table optimization
    bool progressive = false;                                       // Progressive JPEG (for web)
};

// Structure to hold extracted RAW metadata
struct RawMetadata {
    // Camera info
    std::string make;
    std::string model;
    
    // Sensor dimensions
    int raw_width;
    int raw_height;
    int raw_pitch;      // Row stride in bytes (may include padding)
    int width;          // Active area width
    int height;         // Active area height
    int left_margin;
    int top_margin;
    
    // Orientation (EXIF rotation)
    int flip;
    
    // Color info
    int colors;         // Number of colors (usually 3)
    unsigned filters;   // Bayer pattern encoding
    char cdesc[5];      // Color description "RGBG" etc.
    
    // Levels
    unsigned black;           // Global black level
    unsigned cblack[4];       // Per-channel black levels
    unsigned maximum;         // White level (saturation)
    
    // White balance multipliers (as-shot)
    float cam_mul[4];
    
    // Camera to XYZ color matrix
    float cam_xyz[4][3];
    
    // EXIF data
    float iso_speed;
    float shutter;      // Exposure time in seconds
    float aperture;     // F-number
    float focal_len;
    time_t timestamp;
    
    // Lens info
    std::string lens_make;
    std::string lens_model;
};

class LibRawReader {
public:
    LibRawReader();
    ~LibRawReader();
    
    // Open and unpack a RAW file
    bool open(const std::string& path, std::string& errorMessage);
    
    // Get metadata after opening
    const RawMetadata& getMetadata() const { return metadata_; }
    
    // Get raw Bayer data pointer (after unpack)
    // Returns null if not unpacked or if format not supported
    const uint16_t* getRawData() const;
    
    // Get raw data dimensions (raw_width * raw_height)
    size_t getRawDataSize() const;
    
    // Check if this is a supported Bayer sensor (not X-Trans, Foveon, etc.)
    bool isBayerSensor() const;
    
    // Get Bayer pattern phase (0-3 for RGGB variants)
    int getBayerPhase() const;
    
    // Clean up and release resources
    void close();
    
    // Static method to extract embedded thumbnail from a RAW file
    // Writes a JPEG file to outputPath
    // Returns true on success, false on failure (sets errorMessage)
    static bool extractThumbnail(const std::string& inputPath, 
                                 const std::string& outputPath,
                                 std::string& errorMessage);
    
    // Static method to convert RAW to JPEG
    // Processes the RAW file and writes a full-resolution JPEG
    // Returns true on success, false on failure (sets errorMessage)
    static bool convertToJPEG(const std::string& inputPath,
                              const std::string& outputPath,
                              const JpegEncodingSettings& settings,
                              std::string& errorMessage);
    
    // Convenience overload with individual parameters for JNI
    static bool convertToJPEG(const std::string& inputPath,
                              const std::string& outputPath,
                              int quality,
                              int chromaSubsampling,
                              bool optimizeCoding,
                              std::string& errorMessage);

private:
    std::unique_ptr<LibRaw> processor_;
    RawMetadata metadata_;
    bool isOpen_;
    
    void extractMetadata();
};

} // namespace raw2dng

#endif // LIBRAW_READER_H
