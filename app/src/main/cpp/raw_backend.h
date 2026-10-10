// raw_backend.h
// Backend-neutral interface for everything the app needs from a RAW decoding
// library: DNG authoring, JPEG export, preview extraction and metadata.
//
// The JNI layer talks only to this interface. Concrete backends (LibRaw +
// Adobe DNG SDK, dnglab/rawler, ...) implement it and are selected at build
// time through raw2dng::backend().

#ifndef RAW2DNG_RAW_BACKEND_H
#define RAW2DNG_RAW_BACKEND_H

#include <cstdint>
#include <string>
#include <vector>

namespace raw2dng {

// Chroma subsampling options for JPEG encoding.
// Higher subsampling = smaller file but color fringing artifacts.
enum class ChromaSubsampling : int32_t {
    SUBSAMP_444 = 0,  // No subsampling - best quality, largest file
    SUBSAMP_422 = 1,  // Horizontal subsampling only - medium quality
    SUBSAMP_420 = 2   // H and V subsampling - smallest file, most artifacts
};

// Policy for reusing the JPEG the camera embedded in the RAW file instead of
// demosaicing/rendering the sensor data when exporting to JPEG.
//
// Most RAW formats carry a full-resolution, in-camera processed JPEG (NEF,
// CR2/CR3, RAF, PEF, most ORF ...). Some only carry a reduced preview (ARW,
// RW2 ...). The policy decides what counts as acceptable.
enum class EmbeddedJpegPolicy : int32_t {
    Never = 0,               // Always render from sensor data
    FullResolutionOnly = 1,  // Use the embedded JPEG only when it is (nearly) full sensor resolution
    Always = 2               // Use any embedded JPEG, however small
};

// JPEG encoding settings for fine-grained control.
struct JpegEncodingSettings {
    int quality = 95;                                                // Quality 1-100 (higher = better)
    ChromaSubsampling subsampling = ChromaSubsampling::SUBSAMP_444;  // Chroma subsampling (rendered path only)
    bool optimizeCoding = true;                                      // Huffman table optimization (rendered path only)
    bool progressive = false;                                        // Progressive JPEG (rendered path only)
    EmbeddedJpegPolicy embeddedPolicy = EmbeddedJpegPolicy::FullResolutionOnly;
    // Minimum ratio (embedded / sensor active area) for both width and height
    // that still counts as "full resolution" for EmbeddedJpegPolicy::FullResolutionOnly.
    float fullResolutionThreshold = 0.9f;
    bool embedExif = true;                                           // Write EXIF (incl. GPS) from the RAW into the JPEG
};

// Outcome of a JPEG export.
struct JpegResult {
    bool usedEmbeddedJpeg = false;  // true: camera JPEG was copied, false: rendered from sensor data
    int width = 0;
    int height = 0;
    int orientation = 1;            // EXIF orientation of the pixels in the file (1 = already upright)
    bool exifEmbedded = false;      // true: the backend wrote EXIF (incl. orientation) into the JPEG itself
};

// DNG authoring settings.
struct DngConversionSettings {
    bool embedOriginalRaw = false;  // Embed the original RAW file (DNG OriginalRawFileData)
    bool preview = true;            // Include a JPEG preview
    bool thumbnail = true;          // Include a thumbnail
    bool lossless = true;           // Lossless JPEG-92 compression (false = uncompressed)
    std::string artist;             // Optional Artist tag
    std::string dcpDir;             // Optional directory with DNG camera profiles
    std::string dcpFile;            // Optional explicit DCP file
};

// Outcome of a preview extraction.
struct PreviewResult {
    // EXIF orientation (1..8) that still has to be applied by the caller to display
    // the written file upright. 1 means the pixels are already upright.
    int orientation = 1;
    int width = 0;
    int height = 0;
    bool fromEmbeddedJpeg = false;  // true: taken from the camera JPEG, false: rendered from sensor data
};

class RawBackend {
public:
    virtual ~RawBackend() = default;

    // Human readable backend identification, e.g. "dnglab/rawler" and "0.7.2".
    virtual std::string name() const = 0;
    virtual std::string version() const = 0;

    // Lower-case file extensions (without dot) that this backend can decode.
    virtual std::vector<std::string> supportedExtensions() const = 0;

    // Convert a RAW file into a DNG.
    // Returns true on success, false on failure with errorMessage set.
    virtual bool convertToDNG(const std::string& inputPath,
                              const std::string& outputPath,
                              const DngConversionSettings& settings,
                              std::string& errorMessage) = 0;

    // Export a RAW file to a full-resolution JPEG, either by reusing the
    // camera's embedded JPEG (see EmbeddedJpegPolicy) or by rendering the
    // sensor data.
    virtual bool convertToJPEG(const std::string& inputPath,
                               const std::string& outputPath,
                               const JpegEncodingSettings& settings,
                               JpegResult& result,
                               std::string& errorMessage) = 0;

    // Extract (or render) a preview image for thumbnails and on-screen viewing.
    // The output is always a JPEG. maxDimension limits the longest edge; 0 means
    // "whatever is embedded, unmodified".
    virtual bool extractPreview(const std::string& inputPath,
                                const std::string& outputPath,
                                int maxDimension,
                                PreviewResult& result,
                                std::string& errorMessage) = 0;

    // Extract metadata as a JSON document. The flat top-level keys form the
    // backend-neutral contract consumed by the Kotlin side (ExifData.kt):
    //
    //   make, model, software, body_serial,
    //   lens_make, lens_model, lens_serial, min_focal, max_focal,
    //   focal_length, focal_length_35mm, aperture, shutter ("1/250"), shutter_raw (s), iso,
    //   exposure_program, metering_mode,
    //   timestamp ("YYYY-MM-DD HH:MM:SS"), timestamp_raw (unix seconds),
    //   width, height (default crop), raw_width, raw_height (sensor), orientation (EXIF 1..8),
    //   colors, bayer_pattern,
    //   has_gps, gps_lat_deg, gps_lat_min, gps_lat_sec, gps_lat_ref, gps_lon_deg, gps_lon_min,
    //   gps_lon_sec, gps_lon_ref, gps_altitude, gps_alt_ref, gps_time_hour, gps_time_min, gps_time_sec,
    //   description, artist,
    //   backend (name of the producing backend)
    //
    // Backends may add arbitrary nested objects with additional detail.
    virtual bool extractMetadataJson(const std::string& inputPath,
                                     std::string& jsonOutput,
                                     std::string& errorMessage) = 0;
};

// The process-wide backend instance. Defined by the backend implementation
// that is compiled into the native library.
RawBackend& backend();

// Convert an EXIF orientation value (1..8) to the "flip" convention used by
// dcraw/LibRaw (0 = normal, 3 = 180 deg, 5 = 90 deg CCW, 6 = 90 deg CW) and back.
inline int exifOrientationToFlip(int orientation) {
    switch (orientation) {
        case 3: return 3;
        case 6: return 6;
        case 8: return 5;
        default: return 0;
    }
}

inline int flipToExifOrientation(int flip) {
    switch (flip) {
        case 3: return 3;
        case 5: return 8;
        case 6: return 6;
        default: return 1;
    }
}

} // namespace raw2dng

#endif // RAW2DNG_RAW_BACKEND_H
