// libraw_reader.cpp
// LibRaw wrapper implementation

#include "libraw_reader.h"
#include "libraw/libraw.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <cstdio>

extern "C" {
#include "jpeglib/jpeglib.h"
#include "cJSON.h"
}

#define LOG_TAG "LibRawReader"
// Use ERROR level for all logs so they definitely appear in logcat
#define LOGD(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw2dng {

LibRawReader::LibRawReader() 
    : processor_(std::make_unique<LibRaw>())
    , isOpen_(false) 
{
    memset(&metadata_, 0, sizeof(metadata_));
}

LibRawReader::~LibRawReader() {
    close();
}

bool LibRawReader::open(const std::string& path, std::string& errorMessage) {
    close();
    
    LOGD("Opening RAW file: %s", path.c_str());
    
    // Open the file
    int ret = processor_->open_file(path.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to open RAW file: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        return false;
    }
    
    LOGD("Opened RAW file, camera: %s %s", 
         processor_->imgdata.idata.make,
         processor_->imgdata.idata.model);
    
    // Unpack the raw data
    ret = processor_->unpack();
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to unpack RAW data: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor_->recycle();
        return false;
    }
    
    LOGD("Unpacked RAW data: %dx%d, %d colors",
         processor_->imgdata.sizes.raw_width,
         processor_->imgdata.sizes.raw_height,
         processor_->imgdata.idata.colors);
    
    // Check for supported sensor type
    if (!isBayerSensor()) {
        errorMessage = "Unsupported sensor type. Only Bayer CFA sensors are supported. "
                       "X-Trans, Foveon, and other non-Bayer sensors are not supported.";
        LOGE("%s", errorMessage.c_str());
        processor_->recycle();
        return false;
    }
    
    // Extract metadata
    extractMetadata();
    
    isOpen_ = true;
    LOGD("Successfully opened and unpacked RAW file");
    return true;
}

void LibRawReader::extractMetadata() {
    libraw_data_t& data = processor_->imgdata;
    
    // Camera info
    metadata_.make = data.idata.make;
    metadata_.model = data.idata.model;
    
    // Dimensions
    metadata_.raw_width = data.sizes.raw_width;
    metadata_.raw_height = data.sizes.raw_height;
    metadata_.width = data.sizes.width;
    metadata_.height = data.sizes.height;
    metadata_.left_margin = data.sizes.left_margin;
    metadata_.top_margin = data.sizes.top_margin;
    metadata_.flip = data.sizes.flip;
    
    // Store raw_pitch for proper row stride
    // Some cameras have padding at the end of each row
    metadata_.raw_pitch = data.sizes.raw_pitch;
    
    // Color info
    metadata_.colors = data.idata.colors;
    metadata_.filters = data.idata.filters;
    strncpy(metadata_.cdesc, data.idata.cdesc, 4);
    metadata_.cdesc[4] = '\0';
    
    // Levels
    metadata_.black = data.color.black;
    for (int i = 0; i < 4; i++) {
        metadata_.cblack[i] = data.color.cblack[i];
    }
    metadata_.maximum = data.color.maximum;
    
    // White balance
    for (int i = 0; i < 4; i++) {
        metadata_.cam_mul[i] = data.color.cam_mul[i];
    }
    
    // Color matrix (camera RGB to XYZ)
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 3; j++) {
            metadata_.cam_xyz[i][j] = data.color.cam_xyz[i][j];
        }
    }
    
    // Basic EXIF
    metadata_.iso_speed = data.other.iso_speed;
    metadata_.shutter = data.other.shutter;
    metadata_.aperture = data.other.aperture;
    metadata_.focal_len = data.other.focal_len;
    metadata_.timestamp = data.other.timestamp;
    
    // Description and artist
    if (data.other.desc[0]) {
        metadata_.description = data.other.desc;
    }
    if (data.other.artist[0]) {
        metadata_.artist = data.other.artist;
    }
    
    // Lens info
    if (data.lens.LensMake[0]) {
        metadata_.lens_make = data.lens.LensMake;
    }
    if (data.lens.Lens[0]) {
        metadata_.lens_model = data.lens.Lens;
    }
    if (data.lens.LensSerial[0]) {
        metadata_.lens_serial = data.lens.LensSerial;
    }
    metadata_.min_focal = data.lens.MinFocal;
    metadata_.max_focal = data.lens.MaxFocal;
    metadata_.focal_len_35mm = data.lens.FocalLengthIn35mmFormat;
    
    // Body serial
    if (data.shootinginfo.BodySerial[0]) {
        metadata_.body_serial = data.shootinginfo.BodySerial;
    }
    
    // Shooting info
    metadata_.exposure_program = data.shootinginfo.ExposureProgram;
    metadata_.metering_mode = data.shootinginfo.MeteringMode;
    
    // GPS data - only set has_gps if we have actual valid coordinates
    bool gpsParsed = (data.other.parsed_gps.gpsparsed != 0);
    bool hasValidCoords = gpsParsed &&
        (data.other.parsed_gps.latitude[0] != 0 || data.other.parsed_gps.latitude[1] != 0 || data.other.parsed_gps.latitude[2] != 0) &&
        (data.other.parsed_gps.longitude[0] != 0 || data.other.parsed_gps.longitude[1] != 0 || data.other.parsed_gps.longitude[2] != 0);
    
    metadata_.has_gps = hasValidCoords;
    if (metadata_.has_gps) {
        for (int i = 0; i < 3; i++) {
            metadata_.gps_latitude[i] = data.other.parsed_gps.latitude[i];
            metadata_.gps_longitude[i] = data.other.parsed_gps.longitude[i];
            metadata_.gps_timestamp[i] = data.other.parsed_gps.gpstimestamp[i];
        }
        metadata_.gps_altitude = data.other.parsed_gps.altitude;
        // Sanitize ref chars - default to N/E if invalid
        metadata_.gps_lat_ref = (data.other.parsed_gps.latref == 'N' || data.other.parsed_gps.latref == 'S') 
            ? data.other.parsed_gps.latref : 'N';
        metadata_.gps_lon_ref = (data.other.parsed_gps.longref == 'E' || data.other.parsed_gps.longref == 'W')
            ? data.other.parsed_gps.longref : 'E';
        metadata_.gps_alt_ref = data.other.parsed_gps.altref;
    }
    
    LOGD("Metadata extracted:");
    LOGD("  Camera: %s %s", metadata_.make.c_str(), metadata_.model.c_str());
    LOGD("  Raw size: %dx%d, pitch: %d", metadata_.raw_width, metadata_.raw_height, metadata_.raw_pitch);
    LOGD("  Active area: %dx%d at (%d,%d)", 
         metadata_.width, metadata_.height,
         metadata_.left_margin, metadata_.top_margin);
    LOGD("  Black: %u, Maximum: %u", metadata_.black, metadata_.maximum);
    LOGD("  ISO: %.0f, Shutter: %.4f, Aperture: f/%.1f, Focal: %.1fmm",
         metadata_.iso_speed, metadata_.shutter, metadata_.aperture, metadata_.focal_len);
    LOGD("  Lens: %s %s", metadata_.lens_make.c_str(), metadata_.lens_model.c_str());
    LOGD("  Exposure Program: %d, Metering Mode: %d", 
         metadata_.exposure_program, metadata_.metering_mode);
    if (metadata_.has_gps) {
        LOGD("  GPS: %.4f°%c, %.4f°%c, Alt: %.1fm",
             metadata_.gps_latitude[0] + metadata_.gps_latitude[1]/60.0 + metadata_.gps_latitude[2]/3600.0,
             metadata_.gps_lat_ref,
             metadata_.gps_longitude[0] + metadata_.gps_longitude[1]/60.0 + metadata_.gps_longitude[2]/3600.0,
             metadata_.gps_lon_ref,
             metadata_.gps_altitude);
    }
    LOGD("  Bayer pattern: %s (filters=0x%x)", metadata_.cdesc, metadata_.filters);
    LOGD("  cam_mul: [%.6f, %.6f, %.6f, %.6f]", 
         metadata_.cam_mul[0], metadata_.cam_mul[1], metadata_.cam_mul[2], metadata_.cam_mul[3]);
    LOGD("  cam_xyz[0]: [%.6f, %.6f, %.6f]", 
         metadata_.cam_xyz[0][0], metadata_.cam_xyz[0][1], metadata_.cam_xyz[0][2]);
    LOGD("  cam_xyz[1]: [%.6f, %.6f, %.6f]", 
         metadata_.cam_xyz[1][0], metadata_.cam_xyz[1][1], metadata_.cam_xyz[1][2]);
    LOGD("  cam_xyz[2]: [%.6f, %.6f, %.6f]", 
         metadata_.cam_xyz[2][0], metadata_.cam_xyz[2][1], metadata_.cam_xyz[2][2]);
}

const uint16_t* LibRawReader::getRawData() const {
    if (!isOpen_) return nullptr;
    return processor_->imgdata.rawdata.raw_image;
}

size_t LibRawReader::getRawDataSize() const {
    if (!isOpen_) return 0;
    return (size_t)metadata_.raw_width * (size_t)metadata_.raw_height;
}

bool LibRawReader::isBayerSensor() const {
    if (!processor_) return false;
    
    libraw_data_t& data = processor_->imgdata;
    
    // Check if it's a color filter array camera
    // filters == 0 means non-Bayer (Foveon, etc.)
    // filters == 9 means X-Trans
    if (data.idata.filters == 0) {
        LOGD("Non-CFA sensor detected (Foveon or similar)");
        return false;
    }
    
    if (data.idata.filters == 9) {
        LOGD("X-Trans sensor detected - not supported");
        return false;
    }
    
    // Check for standard Bayer (2x2 pattern)
    // The filters field encodes the pattern
    if (data.idata.colors != 3 && data.idata.colors != 4) {
        LOGD("Unusual color count: %d", data.idata.colors);
        return false;
    }
    
    return true;
}

int LibRawReader::getBayerPhase() const {
    if (!isOpen_) return 0;
    
    // LibRaw's filters field encodes the Bayer pattern as a 32-bit value
    // Each 2-bit pair represents a color: 0=R, 1=G, 2=B, 3=G
    // The pattern repeats for a 2x2 Bayer array
    // 
    // The FC() macro in LibRaw uses: (filters >> ((((row) << 1 & 14) + ((col) & 1)) << 1) & 3)
    // For position (0,0): (filters >> 0) & 3
    // For position (0,1): (filters >> 2) & 3  
    // For position (1,0): (filters >> 4) & 3
    // For position (1,1): (filters >> 6) & 3
    //
    // DNG BayerPhase:
    // 0 = RGGB (R at even row, even col)
    // 1 = GRBG (G at even row, even col; R at even row, odd col)
    // 2 = GBRG (G at even row, even col; B at even row, odd col)  
    // 3 = BGGR (B at even row, even col)
    
    unsigned filters = metadata_.filters;
    
    // Use LibRaw's FC macro equivalent to get color at (row, col)
    auto FC = [filters](int row, int col) -> int {
        return (filters >> ((((row) << 1 & 14) + ((col) & 1)) << 1)) & 3;
    };
    
    int c00 = FC(0, 0);  // Color at top-left
    int c01 = FC(0, 1);  // Color at top-right of 2x2
    int c10 = FC(1, 0);  // Color at bottom-left of 2x2
    int c11 = FC(1, 1);  // Color at bottom-right of 2x2
    
    LOGD("Bayer 2x2 pattern: [%d,%d; %d,%d] (0=R,1=G,2=B,3=G)", c00, c01, c10, c11);
    
    // DNG SDK SetBayerMosaic phase mapping:
    // Phase 0: [G,R; B,G] = GRBG
    // Phase 1: [R,G; G,B] = RGGB  
    // Phase 2: [B,G; G,R] = BGGR
    // Phase 3: [G,B; R,G] = GBRG
    
    // Determine DNG phase from LibRaw's top-left color
    if (c00 == 0) {
        // R at (0,0) -> RGGB pattern -> DNG phase 1
        LOGD("Detected RGGB pattern, DNG phase 1");
        return 1;
    } else if (c00 == 2) {
        // B at (0,0) -> BGGR pattern -> DNG phase 2
        LOGD("Detected BGGR pattern, DNG phase 2");
        return 2;
    } else if (c00 == 1 || c00 == 3) {
        // G at (0,0)
        if (c01 == 0) {
            // G at (0,0), R at (0,1) -> GRBG pattern -> DNG phase 0
            LOGD("Detected GRBG pattern, DNG phase 0");
            return 0;
        } else if (c01 == 2) {
            // G at (0,0), B at (0,1) -> GBRG pattern -> DNG phase 3
            LOGD("Detected GBRG pattern, DNG phase 3");
            return 3;
        }
    }
    
    // Default to RGGB (phase 1) if we can't determine
    LOGD("Could not determine Bayer phase from filters=0x%x, defaulting to RGGB (phase 1)", filters);
    return 1;
}

void LibRawReader::close() {
    if (processor_) {
        processor_->recycle();
    }
    isOpen_ = false;
    memset(&metadata_, 0, sizeof(metadata_));
}

bool LibRawReader::extractThumbnail(const std::string& inputPath,
                                    const std::string& outputPath,
                                    std::string& errorMessage,
                                    int* outFlip) {
    LibRaw processor;
    
    LOGD("Extracting thumbnail from: %s", inputPath.c_str());
    
    // Open the file
    int ret = processor.open_file(inputPath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to open RAW file: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        return false;
    }
    
    // Get the flip value for rotation
    int flip = processor.imgdata.sizes.flip;
    LOGD("Image flip value: %d (0=normal, 3=180, 5=90CCW, 6=90CW)", flip);
    if (outFlip) {
        *outFlip = flip;
    }
    
    // Try to unpack embedded thumbnail
    ret = processor.unpack_thumb();
    if (ret == LIBRAW_SUCCESS) {
        // Write the embedded thumbnail
        LOGD("Found embedded thumbnail, format: %d, size: %d bytes",
             processor.imgdata.thumbnail.tformat,
             processor.imgdata.thumbnail.tlength);
        
        // Note: embedded thumbnails may or may not be pre-rotated by the camera
        // We return the image flip value; Kotlin will apply rotation if needed
        
        ret = processor.dcraw_thumb_writer(outputPath.c_str());
        if (ret == LIBRAW_SUCCESS) {
            LOGD("Successfully wrote thumbnail to: %s", outputPath.c_str());
            processor.recycle();
            return true;
        } else {
            LOGD("dcraw_thumb_writer failed: %s", libraw_strerror(ret));
        }
    } else {
        LOGD("No embedded thumbnail found: %s", libraw_strerror(ret));
    }
    
    // No embedded thumbnail or failed to write it
    // Generate a preview by processing the RAW data at half size
    LOGD("Generating preview from RAW data...");
    
    processor.recycle();
    ret = processor.open_file(inputPath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to reopen RAW file";
        return false;
    }
    
    ret = processor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to unpack RAW data: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    // Configure for fast preview generation
    processor.imgdata.params.half_size = 1;      // Half resolution for speed
    processor.imgdata.params.use_camera_wb = 1;  // Use camera white balance
    processor.imgdata.params.output_bps = 8;     // 8-bit output
    processor.imgdata.params.user_qual = 0;      // Fastest interpolation (linear)
    
    ret = processor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to process RAW data: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    // Write as PPM first, then we'd need to convert to JPEG
    // Actually, dcraw_ppm_tiff_writer can write the processed image
    // But for thumbnails, let's write as PPM and handle it
    // Actually, the easiest is to write to a temp PPM and convert
    // But that's complex. Let's just write the PPM directly for now
    // and handle the format in Kotlin (Android can read PPM files indirectly)
    
    // Actually, let's use libraw_processed_image_t which gives us RGB data
    libraw_processed_image_t* image = processor.dcraw_make_mem_image(&ret);
    if (ret != LIBRAW_SUCCESS || image == nullptr) {
        errorMessage = "Failed to create memory image: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    LOGD("Generated preview: %dx%d, %d bits, %d colors, %d bytes",
         image->width, image->height, image->bits, image->colors, image->data_size);
    
    // Write as PPM file (simple format that Android BitmapFactory can read)
    FILE* fp = fopen(outputPath.c_str(), "wb");
    if (!fp) {
        errorMessage = "Failed to open output file for writing";
        LOGE("%s", errorMessage.c_str());
        processor.dcraw_clear_mem(image);
        processor.recycle();
        return false;
    }
    
    // Write PPM header (P6 = binary RGB)
    fprintf(fp, "P6\n%d %d\n255\n", image->width, image->height);
    fwrite(image->data, 1, image->data_size, fp);
    fclose(fp);
    
    LOGD("Successfully wrote preview to: %s", outputPath.c_str());
    
    processor.dcraw_clear_mem(image);
    processor.recycle();
    return true;
}

bool LibRawReader::convertToJPEG(const std::string& inputPath,
                                  const std::string& outputPath,
                                  const JpegEncodingSettings& settings,
                                  std::string& errorMessage) {
    LibRaw processor;
    
    LOGD("Converting RAW to JPEG: %s -> %s (quality: %d, subsampling: %d, optimize: %d)", 
         inputPath.c_str(), outputPath.c_str(), settings.quality, 
         static_cast<int>(settings.subsampling), settings.optimizeCoding);
    
    // Clamp quality to valid range
    int quality = settings.quality;
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;
    
    // Open the file
    int ret = processor.open_file(inputPath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to open RAW file: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        return false;
    }
    
    LOGD("Camera: %s %s", processor.imgdata.idata.make, processor.imgdata.idata.model);
    
    // Unpack raw data
    ret = processor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to unpack RAW data: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    // Configure for high quality output
    processor.imgdata.params.half_size = 0;         // Full resolution
    processor.imgdata.params.use_camera_wb = 1;     // Use camera white balance
    processor.imgdata.params.use_auto_wb = 0;       // Don't use auto WB
    processor.imgdata.params.output_bps = 8;        // 8-bit output for JPEG
    processor.imgdata.params.user_qual = 3;         // AHD interpolation (high quality)
    processor.imgdata.params.no_auto_bright = 0;    // Allow auto brightness
    processor.imgdata.params.output_color = 1;      // sRGB colorspace
    
    // Process the RAW data
    ret = processor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to process RAW data: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    // Get the processed image
    libraw_processed_image_t* image = processor.dcraw_make_mem_image(&ret);
    if (ret != LIBRAW_SUCCESS || image == nullptr) {
        errorMessage = "Failed to create memory image: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        processor.recycle();
        return false;
    }
    
    LOGD("Processed image: %dx%d, %d bits, %d colors", 
         image->width, image->height, image->bits, image->colors);
    
    // Write JPEG using libjpeg
    FILE* outfile = fopen(outputPath.c_str(), "wb");
    if (!outfile) {
        errorMessage = "Failed to open output file for writing";
        LOGE("%s", errorMessage.c_str());
        processor.dcraw_clear_mem(image);
        processor.recycle();
        return false;
    }
    
    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, outfile);
    
    cinfo.image_width = image->width;
    cinfo.image_height = image->height;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    
    // Set chroma subsampling based on settings
    // After jpeg_set_defaults, modify comp_info for subsampling
    switch (settings.subsampling) {
        case ChromaSubsampling::SUBSAMP_444:
            // No subsampling - all components at full resolution
            cinfo.comp_info[0].h_samp_factor = 1;
            cinfo.comp_info[0].v_samp_factor = 1;
            cinfo.comp_info[1].h_samp_factor = 1;
            cinfo.comp_info[1].v_samp_factor = 1;
            cinfo.comp_info[2].h_samp_factor = 1;
            cinfo.comp_info[2].v_samp_factor = 1;
            LOGD("Using 4:4:4 chroma subsampling (no subsampling)");
            break;
        case ChromaSubsampling::SUBSAMP_422:
            // Horizontal subsampling only
            cinfo.comp_info[0].h_samp_factor = 2;
            cinfo.comp_info[0].v_samp_factor = 1;
            cinfo.comp_info[1].h_samp_factor = 1;
            cinfo.comp_info[1].v_samp_factor = 1;
            cinfo.comp_info[2].h_samp_factor = 1;
            cinfo.comp_info[2].v_samp_factor = 1;
            LOGD("Using 4:2:2 chroma subsampling");
            break;
        case ChromaSubsampling::SUBSAMP_420:
        default:
            // H and V subsampling (default from jpeg_set_defaults)
            cinfo.comp_info[0].h_samp_factor = 2;
            cinfo.comp_info[0].v_samp_factor = 2;
            cinfo.comp_info[1].h_samp_factor = 1;
            cinfo.comp_info[1].v_samp_factor = 1;
            cinfo.comp_info[2].h_samp_factor = 1;
            cinfo.comp_info[2].v_samp_factor = 1;
            LOGD("Using 4:2:0 chroma subsampling");
            break;
    }
    
    // Enable Huffman table optimization if requested
    if (settings.optimizeCoding) {
        cinfo.optimize_coding = TRUE;
        LOGD("Huffman table optimization enabled");
    }
    
    // Enable progressive encoding if requested
    if (settings.progressive) {
        jpeg_simple_progression(&cinfo);
        LOGD("Progressive JPEG enabled");
    }
    
    jpeg_start_compress(&cinfo, TRUE);
    
    JSAMPROW row_pointer[1];
    int row_stride = image->width * 3;
    
    while (cinfo.next_scanline < cinfo.image_height) {
        row_pointer[0] = &image->data[cinfo.next_scanline * row_stride];
        jpeg_write_scanlines(&cinfo, row_pointer, 1);
    }
    
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    fclose(outfile);
    
    LOGD("Successfully wrote JPEG: %s", outputPath.c_str());
    
    processor.dcraw_clear_mem(image);
    processor.recycle();
    return true;
}

// Convenience overload for JNI with individual parameters
bool LibRawReader::convertToJPEG(const std::string& inputPath,
                                  const std::string& outputPath,
                                  int quality,
                                  int chromaSubsampling,
                                  bool optimizeCoding,
                                  std::string& errorMessage) {
    JpegEncodingSettings settings;
    settings.quality = quality;
    settings.subsampling = static_cast<ChromaSubsampling>(chromaSubsampling);
    settings.optimizeCoding = optimizeCoding;
    settings.progressive = false;  // Not exposed to JNI for simplicity
    
    return convertToJPEG(inputPath, outputPath, settings, errorMessage);
}

// Extract metadata from RAW file as JSON
bool LibRawReader::extractMetadataJson(const std::string& inputPath,
                                       std::string& jsonOutput,
                                       std::string& errorMessage) {
    LOGD("Extracting metadata from: %s", inputPath.c_str());
    
    LibRaw processor;
    
    int ret = processor.open_file(inputPath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to open RAW file: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        return false;
    }
    
    // Access metadata
    auto& idata = processor.imgdata.idata;
    auto& other = processor.imgdata.other;
    auto& sizes = processor.imgdata.sizes;
    auto& lens = processor.imgdata.lens;
    auto& shootinginfo = processor.imgdata.shootinginfo;
    auto& color = processor.imgdata.color;
    auto& makernotes = processor.imgdata.makernotes;
    
    // Format shutter speed as a fraction
    char shutterStr[32] = "";
    if (other.shutter > 0 && other.shutter < 1.0) {
        snprintf(shutterStr, sizeof(shutterStr), "1/%.0f", 1.0 / other.shutter);
    } else if (other.shutter >= 1.0) {
        snprintf(shutterStr, sizeof(shutterStr), "%.1f", other.shutter);
    }
    
    // Format timestamp
    char dateStr[64] = "";
    if (other.timestamp > 0) {
        struct tm* tm_info = localtime(&other.timestamp);
        if (tm_info) {
            strftime(dateStr, sizeof(dateStr), "%Y-%m-%d %H:%M:%S", tm_info);
        }
    }
    
    // Build JSON using cJSON
    cJSON* root = cJSON_CreateObject();
    if (!root) {
        errorMessage = "Failed to create JSON object";
        return false;
    }
    
    // Helper lambdas for null-safe JSON additions
    auto addStringIfNotEmpty = [](cJSON* obj, const char* key, const char* val) {
        if (val && val[0] != '\0') cJSON_AddStringToObject(obj, key, val);
    };
    auto addNumberIfNonZero = [](cJSON* obj, const char* key, double val) {
        if (val != 0.0) cJSON_AddNumberToObject(obj, key, val);
    };
    auto addNumberIfPositive = [](cJSON* obj, const char* key, double val) {
        if (val > 0.0) cJSON_AddNumberToObject(obj, key, val);
    };
    auto addNumberIfValid = [](cJSON* obj, const char* key, int val, int invalidVal) {
        if (val != invalidVal) cJSON_AddNumberToObject(obj, key, val);
    };
    
    // === IDATA (libraw_iparams_t) - Image parameters ===
    cJSON* idataObj = cJSON_CreateObject();
    addStringIfNotEmpty(idataObj, "make", idata.make);
    addStringIfNotEmpty(idataObj, "model", idata.model);
    addStringIfNotEmpty(idataObj, "software", idata.software);
    addStringIfNotEmpty(idataObj, "normalized_make", idata.normalized_make);
    addStringIfNotEmpty(idataObj, "normalized_model", idata.normalized_model);
    addNumberIfNonZero(idataObj, "maker_index", idata.maker_index);
    addNumberIfNonZero(idataObj, "raw_count", idata.raw_count);
    addNumberIfNonZero(idataObj, "dng_version", idata.dng_version);
    if (idata.is_foveon) cJSON_AddBoolToObject(idataObj, "is_foveon", true);
    addNumberIfNonZero(idataObj, "colors", idata.colors);
    addNumberIfNonZero(idataObj, "filters", idata.filters);
    addStringIfNotEmpty(idataObj, "cdesc", idata.cdesc);
    addNumberIfNonZero(idataObj, "xmplen", idata.xmplen);
    cJSON_AddItemToObject(root, "idata", idataObj);

    // === SIZES (libraw_image_sizes_t) - Image dimensions ===
    cJSON* sizesObj = cJSON_CreateObject();
    addNumberIfNonZero(sizesObj, "raw_height", sizes.raw_height);
    addNumberIfNonZero(sizesObj, "raw_width", sizes.raw_width);
    addNumberIfNonZero(sizesObj, "height", sizes.height);
    addNumberIfNonZero(sizesObj, "width", sizes.width);
    addNumberIfNonZero(sizesObj, "top_margin", sizes.top_margin);
    addNumberIfNonZero(sizesObj, "left_margin", sizes.left_margin);
    addNumberIfNonZero(sizesObj, "iheight", sizes.iheight);
    addNumberIfNonZero(sizesObj, "iwidth", sizes.iwidth);
    addNumberIfNonZero(sizesObj, "raw_pitch", sizes.raw_pitch);
    addNumberIfNonZero(sizesObj, "pixel_aspect", sizes.pixel_aspect);
    addNumberIfNonZero(sizesObj, "flip", sizes.flip);
    addNumberIfNonZero(sizesObj, "raw_aspect", sizes.raw_aspect);
    // Masks array (8x4) - only add if any mask has non-zero values
    bool hasMasks = false;
    for (int i = 0; i < 8 && !hasMasks; i++) {
        for (int j = 0; j < 4 && !hasMasks; j++) {
            if (sizes.mask[i][j] != 0) hasMasks = true;
        }
    }
    if (hasMasks) {
        cJSON* masksArr = cJSON_CreateArray();
        for (int i = 0; i < 8; i++) {
            cJSON* maskRow = cJSON_CreateIntArray(sizes.mask[i], 4);
            cJSON_AddItemToArray(masksArr, maskRow);
        }
        cJSON_AddItemToObject(sizesObj, "mask", masksArr);
    }
    cJSON_AddItemToObject(root, "sizes", sizesObj);

    // === COLOR (libraw_colordata_t) - Color and calibration data ===
    cJSON* colorObj = cJSON_CreateObject();
    addNumberIfNonZero(colorObj, "black", color.black);
    addNumberIfNonZero(colorObj, "data_maximum", color.data_maximum);
    addNumberIfNonZero(colorObj, "maximum", color.maximum);
    addNumberIfNonZero(colorObj, "fmaximum", color.fmaximum);
    addNumberIfNonZero(colorObj, "fnorm", color.fnorm);
    addNumberIfNonZero(colorObj, "flash_used", color.flash_used);
    addNumberIfNonZero(colorObj, "canon_ev", color.canon_ev);
    addStringIfNotEmpty(colorObj, "model2", color.model2);
    addStringIfNotEmpty(colorObj, "UniqueCameraModel", color.UniqueCameraModel);
    addStringIfNotEmpty(colorObj, "LocalizedCameraModel", color.LocalizedCameraModel);
    addStringIfNotEmpty(colorObj, "ImageUniqueID", color.ImageUniqueID);
    addStringIfNotEmpty(colorObj, "OriginalRawFileName", color.OriginalRawFileName);
    addNumberIfNonZero(colorObj, "profile_length", color.profile_length);
    addNumberIfNonZero(colorObj, "raw_bps", color.raw_bps);
    addNumberIfNonZero(colorObj, "ExifColorSpace", color.ExifColorSpace);
    if (color.as_shot_wb_applied) cJSON_AddBoolToObject(colorObj, "as_shot_wb_applied", true);
    // cblack array - too large to include (up to 4104 values), just indicate if present
    bool hasCblack = false;
    for (int i = 0; i < LIBRAW_CBLACK_SIZE && i < 4104 && !hasCblack; i++) {
        if (color.cblack[i] != 0) hasCblack = true;
    }
    if (hasCblack) {
        cJSON_AddStringToObject(colorObj, "cblack", "[truncated]");
    }
    // linear_max - only if any non-zero
    bool hasLinearMax = false;
    for (int i = 0; i < 4; i++) if (color.linear_max[i] != 0) hasLinearMax = true;
    if (hasLinearMax) {
        cJSON* linearMaxArr = cJSON_CreateArray();
        for (int i = 0; i < 4; i++) cJSON_AddItemToArray(linearMaxArr, cJSON_CreateNumber(color.linear_max[i]));
        cJSON_AddItemToObject(colorObj, "linear_max", linearMaxArr);
    }
    // cam_mul - only if any non-zero
    bool hasCamMul = false;
    for (int i = 0; i < 4; i++) if (color.cam_mul[i] != 0.0f) hasCamMul = true;
    if (hasCamMul) {
        cJSON* camMulArr = cJSON_CreateArray();
        for (int i = 0; i < 4; i++) cJSON_AddItemToArray(camMulArr, cJSON_CreateNumber(color.cam_mul[i]));
        cJSON_AddItemToObject(colorObj, "cam_mul", camMulArr);
    }
    // pre_mul - only if any non-zero
    bool hasPreMul = false;
    for (int i = 0; i < 4; i++) if (color.pre_mul[i] != 0.0f) hasPreMul = true;
    if (hasPreMul) {
        cJSON* preMulArr = cJSON_CreateArray();
        for (int i = 0; i < 4; i++) cJSON_AddItemToArray(preMulArr, cJSON_CreateNumber(color.pre_mul[i]));
        cJSON_AddItemToObject(colorObj, "pre_mul", preMulArr);
    }
    // cam_xyz (4x3 matrix) - only if any non-zero
    bool hasCamXyz = false;
    for (int i = 0; i < 4 && !hasCamXyz; i++) for (int j = 0; j < 3; j++) if (color.cam_xyz[i][j] != 0.0f) { hasCamXyz = true; break; }
    if (hasCamXyz) {
        cJSON* camXyzArr = cJSON_CreateArray();
        for (int i = 0; i < 4; i++) {
            cJSON* row = cJSON_CreateArray();
            for (int j = 0; j < 3; j++) cJSON_AddItemToArray(row, cJSON_CreateNumber(color.cam_xyz[i][j]));
            cJSON_AddItemToArray(camXyzArr, row);
        }
        cJSON_AddItemToObject(colorObj, "cam_xyz", camXyzArr);
    }
    // rgb_cam (3x4 matrix) - only if any non-zero
    bool hasRgbCam = false;
    for (int i = 0; i < 3 && !hasRgbCam; i++) for (int j = 0; j < 4; j++) if (color.rgb_cam[i][j] != 0.0f) { hasRgbCam = true; break; }
    if (hasRgbCam) {
        cJSON* rgbCamArr = cJSON_CreateArray();
        for (int i = 0; i < 3; i++) {
            cJSON* row = cJSON_CreateArray();
            for (int j = 0; j < 4; j++) cJSON_AddItemToArray(row, cJSON_CreateNumber(color.rgb_cam[i][j]));
            cJSON_AddItemToArray(rgbCamArr, row);
        }
        cJSON_AddItemToObject(colorObj, "rgb_cam", rgbCamArr);
    }
    // cmatrix (3x4) - only if any non-zero
    bool hasCmatrix = false;
    for (int i = 0; i < 3 && !hasCmatrix; i++) for (int j = 0; j < 4; j++) if (color.cmatrix[i][j] != 0.0f) { hasCmatrix = true; break; }
    if (hasCmatrix) {
        cJSON* cmatrixArr = cJSON_CreateArray();
        for (int i = 0; i < 3; i++) {
            cJSON* row = cJSON_CreateArray();
            for (int j = 0; j < 4; j++) cJSON_AddItemToArray(row, cJSON_CreateNumber(color.cmatrix[i][j]));
            cJSON_AddItemToArray(cmatrixArr, row);
        }
        cJSON_AddItemToObject(colorObj, "cmatrix", cmatrixArr);
    }
    // ccm (3x4) - only if any non-zero
    bool hasCcm = false;
    for (int i = 0; i < 3 && !hasCcm; i++) for (int j = 0; j < 4; j++) if (color.ccm[i][j] != 0.0f) { hasCcm = true; break; }
    if (hasCcm) {
        cJSON* ccmArr = cJSON_CreateArray();
        for (int i = 0; i < 3; i++) {
            cJSON* row = cJSON_CreateArray();
            for (int j = 0; j < 4; j++) cJSON_AddItemToArray(row, cJSON_CreateNumber(color.ccm[i][j]));
            cJSON_AddItemToArray(ccmArr, row);
        }
        cJSON_AddItemToObject(colorObj, "ccm", ccmArr);
    }
    cJSON_AddItemToObject(root, "color", colorObj);

    // === LENS (libraw_lensinfo_t) - Full lens information ===
    cJSON* lensObj = cJSON_CreateObject();
    addNumberIfPositive(lensObj, "MinFocal", lens.MinFocal);
    addNumberIfPositive(lensObj, "MaxFocal", lens.MaxFocal);
    addNumberIfPositive(lensObj, "MaxAp4MinFocal", lens.MaxAp4MinFocal);
    addNumberIfPositive(lensObj, "MaxAp4MaxFocal", lens.MaxAp4MaxFocal);
    addNumberIfPositive(lensObj, "EXIF_MaxAp", lens.EXIF_MaxAp);
    addStringIfNotEmpty(lensObj, "LensMake", lens.LensMake);
    addStringIfNotEmpty(lensObj, "Lens", lens.Lens);
    addStringIfNotEmpty(lensObj, "LensSerial", lens.LensSerial);
    addStringIfNotEmpty(lensObj, "InternalLensSerial", lens.InternalLensSerial);
    addNumberIfPositive(lensObj, "FocalLengthIn35mmFormat", lens.FocalLengthIn35mmFormat);
    // Nikon lens - only if has data
    if (lens.nikon.EffectiveMaxAp > 0 || lens.nikon.LensIDNumber > 0 || lens.nikon.LensType > 0) {
        cJSON* nikonLensObj = cJSON_CreateObject();
        addNumberIfPositive(nikonLensObj, "EffectiveMaxAp", lens.nikon.EffectiveMaxAp);
        addNumberIfNonZero(nikonLensObj, "LensIDNumber", lens.nikon.LensIDNumber);
        addNumberIfNonZero(nikonLensObj, "LensFStops", lens.nikon.LensFStops);
        addNumberIfNonZero(nikonLensObj, "MCUVersion", lens.nikon.MCUVersion);
        addNumberIfNonZero(nikonLensObj, "LensType", lens.nikon.LensType);
        cJSON_AddItemToObject(lensObj, "nikon", nikonLensObj);
    }
    // DNG lens - only if has data
    if (lens.dng.MinFocal > 0 || lens.dng.MaxFocal > 0) {
        cJSON* dngLensObj = cJSON_CreateObject();
        addNumberIfPositive(dngLensObj, "MinFocal", lens.dng.MinFocal);
        addNumberIfPositive(dngLensObj, "MaxFocal", lens.dng.MaxFocal);
        addNumberIfPositive(dngLensObj, "MaxAp4MinFocal", lens.dng.MaxAp4MinFocal);
        addNumberIfPositive(dngLensObj, "MaxAp4MaxFocal", lens.dng.MaxAp4MaxFocal);
        cJSON_AddItemToObject(lensObj, "dng", dngLensObj);
    }
    // Makernotes lens - only if has data
    if (lens.makernotes.LensID != 0 || lens.makernotes.Lens[0] != '\0' || lens.makernotes.MinFocal > 0) {
        cJSON* mkLensObj = cJSON_CreateObject();
        if (lens.makernotes.LensID != 0) cJSON_AddNumberToObject(mkLensObj, "LensID", (double)lens.makernotes.LensID);
        addStringIfNotEmpty(mkLensObj, "Lens", lens.makernotes.Lens);
        addNumberIfNonZero(mkLensObj, "LensFormat", lens.makernotes.LensFormat);
        addNumberIfNonZero(mkLensObj, "LensMount", lens.makernotes.LensMount);
        if (lens.makernotes.CamID != 0) cJSON_AddNumberToObject(mkLensObj, "CamID", (double)lens.makernotes.CamID);
        addNumberIfNonZero(mkLensObj, "CameraFormat", lens.makernotes.CameraFormat);
        addNumberIfNonZero(mkLensObj, "CameraMount", lens.makernotes.CameraMount);
        addStringIfNotEmpty(mkLensObj, "body", lens.makernotes.body);
        if (lens.makernotes.FocalType != 0) cJSON_AddNumberToObject(mkLensObj, "FocalType", lens.makernotes.FocalType);
        addStringIfNotEmpty(mkLensObj, "LensFeatures_pre", lens.makernotes.LensFeatures_pre);
        addStringIfNotEmpty(mkLensObj, "LensFeatures_suf", lens.makernotes.LensFeatures_suf);
        addNumberIfPositive(mkLensObj, "MinFocal", lens.makernotes.MinFocal);
        addNumberIfPositive(mkLensObj, "MaxFocal", lens.makernotes.MaxFocal);
        addNumberIfPositive(mkLensObj, "MaxAp4MinFocal", lens.makernotes.MaxAp4MinFocal);
        addNumberIfPositive(mkLensObj, "MaxAp4MaxFocal", lens.makernotes.MaxAp4MaxFocal);
        addNumberIfPositive(mkLensObj, "MinAp4MinFocal", lens.makernotes.MinAp4MinFocal);
        addNumberIfPositive(mkLensObj, "MinAp4MaxFocal", lens.makernotes.MinAp4MaxFocal);
        addNumberIfPositive(mkLensObj, "MaxAp", lens.makernotes.MaxAp);
        addNumberIfPositive(mkLensObj, "MinAp", lens.makernotes.MinAp);
        addNumberIfPositive(mkLensObj, "CurFocal", lens.makernotes.CurFocal);
        addNumberIfPositive(mkLensObj, "CurAp", lens.makernotes.CurAp);
        addNumberIfPositive(mkLensObj, "MaxAp4CurFocal", lens.makernotes.MaxAp4CurFocal);
        addNumberIfPositive(mkLensObj, "MinAp4CurFocal", lens.makernotes.MinAp4CurFocal);
        addNumberIfPositive(mkLensObj, "MinFocusDistance", lens.makernotes.MinFocusDistance);
        addNumberIfPositive(mkLensObj, "FocusRangeIndex", lens.makernotes.FocusRangeIndex);
        addNumberIfPositive(mkLensObj, "LensFStops", lens.makernotes.LensFStops);
        if (lens.makernotes.TeleconverterID != 0) cJSON_AddNumberToObject(mkLensObj, "TeleconverterID", (double)lens.makernotes.TeleconverterID);
        addStringIfNotEmpty(mkLensObj, "Teleconverter", lens.makernotes.Teleconverter);
        if (lens.makernotes.AdapterID != 0) cJSON_AddNumberToObject(mkLensObj, "AdapterID", (double)lens.makernotes.AdapterID);
        addStringIfNotEmpty(mkLensObj, "Adapter", lens.makernotes.Adapter);
        if (lens.makernotes.AttachmentID != 0) cJSON_AddNumberToObject(mkLensObj, "AttachmentID", (double)lens.makernotes.AttachmentID);
        addStringIfNotEmpty(mkLensObj, "Attachment", lens.makernotes.Attachment);
        addNumberIfNonZero(mkLensObj, "FocalUnits", lens.makernotes.FocalUnits);
        addNumberIfPositive(mkLensObj, "FocalLengthIn35mmFormat", lens.makernotes.FocalLengthIn35mmFormat);
        cJSON_AddItemToObject(lensObj, "makernotes", mkLensObj);
    }
    cJSON_AddItemToObject(root, "lens", lensObj);

    // === SHOOTINGINFO (libraw_shootinginfo_t) ===
    cJSON* shootObj = cJSON_CreateObject();
    addNumberIfNonZero(shootObj, "DriveMode", shootinginfo.DriveMode);
    addNumberIfNonZero(shootObj, "FocusMode", shootinginfo.FocusMode);
    addNumberIfNonZero(shootObj, "MeteringMode", shootinginfo.MeteringMode);
    addNumberIfNonZero(shootObj, "AFPoint", shootinginfo.AFPoint);
    addNumberIfNonZero(shootObj, "ExposureMode", shootinginfo.ExposureMode);
    addNumberIfNonZero(shootObj, "ExposureProgram", shootinginfo.ExposureProgram);
    addNumberIfNonZero(shootObj, "ImageStabilization", shootinginfo.ImageStabilization);
    addStringIfNotEmpty(shootObj, "BodySerial", shootinginfo.BodySerial);
    addStringIfNotEmpty(shootObj, "InternalBodySerial", shootinginfo.InternalBodySerial);
    cJSON_AddItemToObject(root, "shootinginfo", shootObj);

    // === MAKERNOTES - Common metadata ===
    cJSON* makernotesObj = cJSON_CreateObject();
    auto& common = makernotes.common;
    cJSON* commonObj = cJSON_CreateObject();
    addNumberIfNonZero(commonObj, "FlashEC", common.FlashEC);
    addNumberIfNonZero(commonObj, "FlashGN", common.FlashGN);
    addNumberIfNonZero(commonObj, "CameraTemperature", common.CameraTemperature);
    addNumberIfNonZero(commonObj, "SensorTemperature", common.SensorTemperature);
    addNumberIfNonZero(commonObj, "SensorTemperature2", common.SensorTemperature2);
    addNumberIfNonZero(commonObj, "LensTemperature", common.LensTemperature);
    addNumberIfNonZero(commonObj, "AmbientTemperature", common.AmbientTemperature);
    addNumberIfNonZero(commonObj, "BatteryTemperature", common.BatteryTemperature);
    addNumberIfNonZero(commonObj, "exifAmbientTemperature", common.exifAmbientTemperature);
    addNumberIfNonZero(commonObj, "exifHumidity", common.exifHumidity);
    addNumberIfNonZero(commonObj, "exifPressure", common.exifPressure);
    addNumberIfNonZero(commonObj, "exifWaterDepth", common.exifWaterDepth);
    addNumberIfNonZero(commonObj, "exifAcceleration", common.exifAcceleration);
    addNumberIfNonZero(commonObj, "exifCameraElevationAngle", common.exifCameraElevationAngle);
    addNumberIfPositive(commonObj, "real_ISO", common.real_ISO);
    addNumberIfNonZero(commonObj, "exifExposureIndex", common.exifExposureIndex);
    addNumberIfNonZero(commonObj, "ColorSpace", common.ColorSpace);
    addStringIfNotEmpty(commonObj, "firmware", common.firmware);
    addNumberIfNonZero(commonObj, "ExposureCalibrationShift", common.ExposureCalibrationShift);
    // AF data array (summarized - just count and first few items)
    if (common.afcount > 0) {
        cJSON_AddNumberToObject(commonObj, "afcount", common.afcount);
        cJSON* afdataArr = cJSON_CreateArray();
        for (int i = 0; i < common.afcount && i < LIBRAW_AFDATA_MAXCOUNT; i++) {
            cJSON* afItem = cJSON_CreateObject();
            addNumberIfNonZero(afItem, "AFInfoData_tag", common.afdata[i].AFInfoData_tag);
            addNumberIfNonZero(afItem, "AFInfoData_order", common.afdata[i].AFInfoData_order);
            addNumberIfNonZero(afItem, "AFInfoData_version", common.afdata[i].AFInfoData_version);
            addNumberIfNonZero(afItem, "AFInfoData_length", common.afdata[i].AFInfoData_length);
            cJSON_AddItemToArray(afdataArr, afItem);
        }
        cJSON_AddItemToObject(commonObj, "afdata", afdataArr);
    }
    // Only add common if it has any content
    if (cJSON_GetArraySize(commonObj) > 0) {
        cJSON_AddItemToObject(makernotesObj, "common", commonObj);
    } else {
        cJSON_Delete(commonObj);
    }
    
    // === Canon makernotes - only if it's a Canon camera ===
    auto& canon = makernotes.canon;
    bool hasCanonData = canon.ColorDataVer != 0 || canon.SpecularWhiteLevel != 0 || canon.SensorWidth != 0;
    if (hasCanonData) {
        cJSON* canonObj = cJSON_CreateObject();
        addNumberIfNonZero(canonObj, "ColorDataVer", canon.ColorDataVer);
        addNumberIfNonZero(canonObj, "ColorDataSubVer", canon.ColorDataSubVer);
        addNumberIfNonZero(canonObj, "SpecularWhiteLevel", canon.SpecularWhiteLevel);
        addNumberIfNonZero(canonObj, "NormalWhiteLevel", canon.NormalWhiteLevel);
        // ChannelBlackLevel - only if any non-zero
        bool hasBlack = false;
        for (int i = 0; i < 4; i++) if (canon.ChannelBlackLevel[i] != 0) hasBlack = true;
        if (hasBlack) {
            cJSON* canonBlackArr = cJSON_CreateArray();
            for (int i = 0; i < 4; i++) cJSON_AddItemToArray(canonBlackArr, cJSON_CreateNumber(canon.ChannelBlackLevel[i]));
            cJSON_AddItemToObject(canonObj, "ChannelBlackLevel", canonBlackArr);
        }
        addNumberIfNonZero(canonObj, "AverageBlackLevel", canon.AverageBlackLevel);
        // multishot - only if any non-zero
        bool hasMultishot = false;
        for (int i = 0; i < 4; i++) if (canon.multishot[i] != 0) hasMultishot = true;
        if (hasMultishot) {
            cJSON* multishotArr = cJSON_CreateArray();
            for (int i = 0; i < 4; i++) cJSON_AddItemToArray(multishotArr, cJSON_CreateNumber(canon.multishot[i]));
            cJSON_AddItemToObject(canonObj, "multishot", multishotArr);
        }
        addNumberIfNonZero(canonObj, "MeteringMode", canon.MeteringMode);
        addNumberIfNonZero(canonObj, "SpotMeteringMode", canon.SpotMeteringMode);
        addNumberIfNonZero(canonObj, "FlashMeteringMode", canon.FlashMeteringMode);
        addNumberIfNonZero(canonObj, "FlashExposureLock", canon.FlashExposureLock);
        addNumberIfNonZero(canonObj, "ExposureMode", canon.ExposureMode);
        addNumberIfNonZero(canonObj, "AESetting", canon.AESetting);
        addNumberIfNonZero(canonObj, "ImageStabilization", canon.ImageStabilization);
        addNumberIfNonZero(canonObj, "FlashMode", canon.FlashMode);
        addNumberIfNonZero(canonObj, "FlashActivity", canon.FlashActivity);
        addNumberIfNonZero(canonObj, "FlashBits", canon.FlashBits);
        addNumberIfNonZero(canonObj, "ManualFlashOutput", canon.ManualFlashOutput);
        addNumberIfNonZero(canonObj, "FlashOutput", canon.FlashOutput);
        addNumberIfNonZero(canonObj, "FlashGuideNumber", canon.FlashGuideNumber);
        addNumberIfNonZero(canonObj, "ContinuousDrive", canon.ContinuousDrive);
        addNumberIfNonZero(canonObj, "SensorWidth", canon.SensorWidth);
        addNumberIfNonZero(canonObj, "SensorHeight", canon.SensorHeight);
        addNumberIfNonZero(canonObj, "AFMicroAdjMode", canon.AFMicroAdjMode);
        addNumberIfNonZero(canonObj, "AFMicroAdjValue", canon.AFMicroAdjValue);
        addNumberIfNonZero(canonObj, "MakernotesFlip", canon.MakernotesFlip);
        addNumberIfNonZero(canonObj, "AutoRotateMode", canon.AutoRotateMode);
        addNumberIfNonZero(canonObj, "RecordMode", canon.RecordMode);
        addNumberIfNonZero(canonObj, "SRAWQuality", canon.SRAWQuality);
        addNumberIfNonZero(canonObj, "wbi", canon.wbi);
        addNumberIfNonZero(canonObj, "RF_lensID", canon.RF_lensID);
        addNumberIfNonZero(canonObj, "AutoLightingOptimizer", canon.AutoLightingOptimizer);
        addNumberIfNonZero(canonObj, "HighlightTonePriority", canon.HighlightTonePriority);
        addNumberIfNonZero(canonObj, "Quality", canon.Quality);
        addNumberIfNonZero(canonObj, "CanonLog", canon.CanonLog);
        // ISOgain - only if any non-zero
        bool hasISOgain = false;
        for (int i = 0; i < 2; i++) if (canon.ISOgain[i] != 0) hasISOgain = true;
        if (hasISOgain) {
            cJSON* isoGainArr = cJSON_CreateArray();
            for (int i = 0; i < 2; i++) cJSON_AddItemToArray(isoGainArr, cJSON_CreateNumber(canon.ISOgain[i]));
            cJSON_AddItemToObject(canonObj, "ISOgain", isoGainArr);
        }
        cJSON_AddItemToObject(makernotesObj, "canon", canonObj);
    }

    // === Nikon makernotes - only if it's a Nikon camera ===
    auto& nikon = makernotes.nikon;
    bool hasNikonData = nikon.NEFCompression != 0 || nikon.SensorWidth != 0 || nikon.ExposureBracketValue != 0 || nikon.ShootingMode != 0;
    if (hasNikonData) {
        cJSON* nikonObj = cJSON_CreateObject();
        addNumberIfNonZero(nikonObj, "ExposureBracketValue", nikon.ExposureBracketValue);
        addNumberIfNonZero(nikonObj, "ActiveDLighting", nikon.ActiveDLighting);
        addNumberIfNonZero(nikonObj, "ShootingMode", nikon.ShootingMode);
        addNumberIfNonZero(nikonObj, "VibrationReduction", nikon.VibrationReduction);
        addNumberIfNonZero(nikonObj, "VRMode", nikon.VRMode);
        addStringIfNotEmpty(nikonObj, "FlashSetting", nikon.FlashSetting);
        addStringIfNotEmpty(nikonObj, "FlashType", nikon.FlashType);
        addNumberIfNonZero(nikonObj, "FlashMode", nikon.FlashMode);
        addNumberIfNonZero(nikonObj, "FlashExposureCompensation2", nikon.FlashExposureCompensation2);
        addNumberIfNonZero(nikonObj, "FlashExposureCompensation3", nikon.FlashExposureCompensation3);
        addNumberIfNonZero(nikonObj, "FlashExposureCompensation4", nikon.FlashExposureCompensation4);
        addNumberIfNonZero(nikonObj, "FlashSource", nikon.FlashSource);
        addNumberIfNonZero(nikonObj, "ExternalFlashFlags", nikon.ExternalFlashFlags);
        addNumberIfNonZero(nikonObj, "FlashControlCommanderMode", nikon.FlashControlCommanderMode);
        addNumberIfNonZero(nikonObj, "FlashOutputAndCompensation", nikon.FlashOutputAndCompensation);
        addNumberIfNonZero(nikonObj, "FlashFocalLength", nikon.FlashFocalLength);
        addNumberIfNonZero(nikonObj, "FlashGNDistance", nikon.FlashGNDistance);
        addNumberIfNonZero(nikonObj, "FlashColorFilter", nikon.FlashColorFilter);
        addNumberIfNonZero(nikonObj, "NEFCompression", nikon.NEFCompression);
        addNumberIfNonZero(nikonObj, "ExposureMode", nikon.ExposureMode);
        addNumberIfNonZero(nikonObj, "ExposureProgram", nikon.ExposureProgram);
        addNumberIfNonZero(nikonObj, "nMEshots", nikon.nMEshots);
        addNumberIfNonZero(nikonObj, "MEgainOn", nikon.MEgainOn);
        addNumberIfNonZero(nikonObj, "AFFineTune", nikon.AFFineTune);
        addNumberIfNonZero(nikonObj, "AFFineTuneIndex", nikon.AFFineTuneIndex);
        addNumberIfNonZero(nikonObj, "AFFineTuneAdj", nikon.AFFineTuneAdj);
        addNumberIfNonZero(nikonObj, "LensDataVersion", nikon.LensDataVersion);
        addNumberIfNonZero(nikonObj, "FlashInfoVersion", nikon.FlashInfoVersion);
        addNumberIfNonZero(nikonObj, "ColorBalanceVersion", nikon.ColorBalanceVersion);
        addNumberIfNonZero(nikonObj, "key", nikon.key);
        addNumberIfNonZero(nikonObj, "HighSpeedCropFormat", nikon.HighSpeedCropFormat);
        addNumberIfPositive(nikonObj, "SensorWidth", nikon.SensorWidth);
        addNumberIfPositive(nikonObj, "SensorHeight", nikon.SensorHeight);
        addNumberIfNonZero(nikonObj, "Active_D_Lighting", nikon.Active_D_Lighting);
        addNumberIfNonZero(nikonObj, "PictureControlVersion", nikon.PictureControlVersion);
        addStringIfNotEmpty(nikonObj, "PictureControlName", nikon.PictureControlName);
        addStringIfNotEmpty(nikonObj, "PictureControlBase", nikon.PictureControlBase);
        addNumberIfNonZero(nikonObj, "ShotInfoVersion", nikon.ShotInfoVersion);
        addStringIfNotEmpty(nikonObj, "ShotInfoFirmware", nikon.ShotInfoFirmware);
        addNumberIfNonZero(nikonObj, "MakernotesFlip", nikon.MakernotesFlip);
        addNumberIfNonZero(nikonObj, "RollAngle", nikon.RollAngle);
        addNumberIfNonZero(nikonObj, "PitchAngle", nikon.PitchAngle);
        addNumberIfNonZero(nikonObj, "YawAngle", nikon.YawAngle);
        cJSON_AddItemToObject(makernotesObj, "nikon", nikonObj);
    }

    // === Sony makernotes - only if it's a Sony camera ===
    auto& sony = makernotes.sony;
    bool hasSonyData = sony.CameraType != 0 || sony.MinoltaCamID != 0 || sony.SonyRawFileType != 0;
    if (hasSonyData) {
        cJSON* sonyObj = cJSON_CreateObject();
        addNumberIfNonZero(sonyObj, "CameraType", sony.CameraType);
        addNumberIfNonZero(sonyObj, "Sony0x9400_version", sony.Sony0x9400_version);
        addNumberIfNonZero(sonyObj, "Sony0x9400_ReleaseMode2", sony.Sony0x9400_ReleaseMode2);
        addNumberIfNonZero(sonyObj, "Sony0x9400_SequenceImageNumber", sony.Sony0x9400_SequenceImageNumber);
        addNumberIfNonZero(sonyObj, "Sony0x9400_SequenceLength1", sony.Sony0x9400_SequenceLength1);
        addNumberIfNonZero(sonyObj, "Sony0x9400_SequenceFileNumber", sony.Sony0x9400_SequenceFileNumber);
        addNumberIfNonZero(sonyObj, "Sony0x9400_SequenceLength2", sony.Sony0x9400_SequenceLength2);
        addNumberIfNonZero(sonyObj, "AFAreaModeSetting", sony.AFAreaModeSetting);
        addNumberIfNonZero(sonyObj, "AFAreaMode", sony.AFAreaMode);
        addNumberIfNonZero(sonyObj, "AFPointSelected", sony.AFPointSelected);
        addNumberIfNonZero(sonyObj, "AFPointSelected_0x201e", sony.AFPointSelected_0x201e);
        addNumberIfNonZero(sonyObj, "nAFPointsUsed", sony.nAFPointsUsed);
        addNumberIfNonZero(sonyObj, "AFTracking", sony.AFTracking);
        addNumberIfNonZero(sonyObj, "AFType", sony.AFType);
        addNumberIfNonZero(sonyObj, "FocusPosition", sony.FocusPosition);
        addNumberIfNonZero(sonyObj, "AFMicroAdjValue", sony.AFMicroAdjValue);
        addNumberIfNonZero(sonyObj, "AFMicroAdjOn", sony.AFMicroAdjOn);
        addNumberIfNonZero(sonyObj, "AFMicroAdjRegisteredLenses", sony.AFMicroAdjRegisteredLenses);
        addNumberIfNonZero(sonyObj, "VariableLowPassFilter", sony.VariableLowPassFilter);
        addNumberIfNonZero(sonyObj, "LongExposureNoiseReduction", sony.LongExposureNoiseReduction);
        addNumberIfNonZero(sonyObj, "HighISONoiseReduction", sony.HighISONoiseReduction);
        addNumberIfNonZero(sonyObj, "group2010", sony.group2010);
        addNumberIfNonZero(sonyObj, "group9050", sony.group9050);
        addNumberIfNonZero(sonyObj, "real_iso_offset", sony.real_iso_offset);
        addNumberIfNonZero(sonyObj, "MeteringMode_offset", sony.MeteringMode_offset);
        addNumberIfNonZero(sonyObj, "ExposureProgram_offset", sony.ExposureProgram_offset);
        addNumberIfNonZero(sonyObj, "ReleaseMode2_offset", sony.ReleaseMode2_offset);
        addNumberIfNonZero(sonyObj, "MinoltaCamID", sony.MinoltaCamID);
        addNumberIfNonZero(sonyObj, "firmware", sony.firmware);
        addNumberIfNonZero(sonyObj, "ImageCount3_offset", sony.ImageCount3_offset);
        addNumberIfNonZero(sonyObj, "ImageCount3", sony.ImageCount3);
        addNumberIfNonZero(sonyObj, "ElectronicFrontCurtainShutter", sony.ElectronicFrontCurtainShutter);
        addNumberIfNonZero(sonyObj, "MeteringMode2", sony.MeteringMode2);
        addStringIfNotEmpty(sonyObj, "SonyDateTime", sony.SonyDateTime);
        addNumberIfNonZero(sonyObj, "ShotNumberSincePowerUp", sony.ShotNumberSincePowerUp);
        addNumberIfNonZero(sonyObj, "PixelShiftGroupPrefix", sony.PixelShiftGroupPrefix);
        addNumberIfNonZero(sonyObj, "PixelShiftGroupID", sony.PixelShiftGroupID);
        addNumberIfNonZero(sonyObj, "nShotsInPixelShiftGroup", sony.nShotsInPixelShiftGroup);
        addNumberIfNonZero(sonyObj, "numInPixelShiftGroup", sony.numInPixelShiftGroup);
        addNumberIfPositive(sonyObj, "prd_ImageHeight", sony.prd_ImageHeight);
        addNumberIfPositive(sonyObj, "prd_ImageWidth", sony.prd_ImageWidth);
        addNumberIfNonZero(sonyObj, "prd_Total_bps", sony.prd_Total_bps);
        addNumberIfNonZero(sonyObj, "prd_Active_bps", sony.prd_Active_bps);
        addNumberIfNonZero(sonyObj, "prd_StorageMethod", sony.prd_StorageMethod);
        addNumberIfNonZero(sonyObj, "prd_BayerPattern", sony.prd_BayerPattern);
        addNumberIfNonZero(sonyObj, "SonyRawFileType", sony.SonyRawFileType);
        addNumberIfNonZero(sonyObj, "RAWFileType", sony.RAWFileType);
        addNumberIfNonZero(sonyObj, "RawSizeType", sony.RawSizeType);
        addNumberIfNonZero(sonyObj, "Quality", sony.Quality);
        addNumberIfNonZero(sonyObj, "FileFormat", sony.FileFormat);
        addStringIfNotEmpty(sonyObj, "MetaVersion", sony.MetaVersion);
        addNumberIfNonZero(sonyObj, "AspectRatio", sony.AspectRatio);
        cJSON_AddItemToObject(makernotesObj, "sony", sonyObj);
    }

    // === Fuji makernotes - only if it's a Fuji camera ===
    auto& fuji = makernotes.fuji;
    bool hasFujiData = fuji.FujiModel[0] != '\0' || fuji.FilmMode != 0 || fuji.DynamicRange != 0;
    if (hasFujiData) {
        cJSON* fujiObj = cJSON_CreateObject();
        addNumberIfNonZero(fujiObj, "ExpoMidPointShift", fuji.ExpoMidPointShift);
        addNumberIfNonZero(fujiObj, "DynamicRange", fuji.DynamicRange);
        addNumberIfNonZero(fujiObj, "FilmMode", fuji.FilmMode);
        addNumberIfNonZero(fujiObj, "DynamicRangeSetting", fuji.DynamicRangeSetting);
        addNumberIfNonZero(fujiObj, "DevelopmentDynamicRange", fuji.DevelopmentDynamicRange);
        addNumberIfNonZero(fujiObj, "AutoDynamicRange", fuji.AutoDynamicRange);
        addNumberIfNonZero(fujiObj, "DRangePriority", fuji.DRangePriority);
        addNumberIfNonZero(fujiObj, "DRangePriorityAuto", fuji.DRangePriorityAuto);
        addNumberIfNonZero(fujiObj, "DRangePriorityFixed", fuji.DRangePriorityFixed);
        addStringIfNotEmpty(fujiObj, "FujiModel", fuji.FujiModel);
        addStringIfNotEmpty(fujiObj, "FujiModel2", fuji.FujiModel2);
        addNumberIfNonZero(fujiObj, "BrightnessCompensation", fuji.BrightnessCompensation);
        addNumberIfNonZero(fujiObj, "FocusMode", fuji.FocusMode);
        addNumberIfNonZero(fujiObj, "AFMode", fuji.AFMode);
        // FocusPixel - only if non-zero
        if (fuji.FocusPixel[0] != 0 || fuji.FocusPixel[1] != 0) {
            cJSON_AddNumberToObject(fujiObj, "FocusPixel_x", fuji.FocusPixel[0]);
            cJSON_AddNumberToObject(fujiObj, "FocusPixel_y", fuji.FocusPixel[1]);
        }
        addNumberIfNonZero(fujiObj, "PrioritySettings", fuji.PrioritySettings);
        addNumberIfNonZero(fujiObj, "FocusSettings", fuji.FocusSettings);
        addNumberIfNonZero(fujiObj, "AF_C_Settings", fuji.AF_C_Settings);
        addNumberIfNonZero(fujiObj, "FocusWarning", fuji.FocusWarning);
        addNumberIfNonZero(fujiObj, "FlashMode", fuji.FlashMode);
        addNumberIfNonZero(fujiObj, "WB_Preset", fuji.WB_Preset);
        addNumberIfNonZero(fujiObj, "ShutterType", fuji.ShutterType);
        addNumberIfNonZero(fujiObj, "ExrMode", fuji.ExrMode);
        addNumberIfNonZero(fujiObj, "Macro", fuji.Macro);
        addNumberIfNonZero(fujiObj, "Rating", fuji.Rating);
        addNumberIfNonZero(fujiObj, "CropMode", fuji.CropMode);
        addStringIfNotEmpty(fujiObj, "SerialSignature", fuji.SerialSignature);
        addStringIfNotEmpty(fujiObj, "SensorID", fuji.SensorID);
        addStringIfNotEmpty(fujiObj, "RAFVersion", fuji.RAFVersion);
        addNumberIfNonZero(fujiObj, "RAFDataGeneration", fuji.RAFDataGeneration);
        addNumberIfNonZero(fujiObj, "RAFDataVersion", fuji.RAFDataVersion);
        addNumberIfNonZero(fujiObj, "isTSNERDTS", fuji.isTSNERDTS);
        addNumberIfNonZero(fujiObj, "DriveMode", fuji.DriveMode);
        addNumberIfNonZero(fujiObj, "AutoBracketing", fuji.AutoBracketing);
        addNumberIfNonZero(fujiObj, "SequenceNumber", fuji.SequenceNumber);
        addNumberIfNonZero(fujiObj, "SeriesLength", fuji.SeriesLength);
        // PixelShiftOffset - only if non-zero
        if (fuji.PixelShiftOffset[0] != 0 || fuji.PixelShiftOffset[1] != 0) {
            cJSON_AddNumberToObject(fujiObj, "PixelShiftOffset_x", fuji.PixelShiftOffset[0]);
            cJSON_AddNumberToObject(fujiObj, "PixelShiftOffset_y", fuji.PixelShiftOffset[1]);
        }
        addNumberIfNonZero(fujiObj, "ImageCount", fuji.ImageCount);
        cJSON_AddItemToObject(makernotesObj, "fuji", fujiObj);
    }

    // === Olympus makernotes - only if it's an Olympus camera ===
    auto& olympus = makernotes.olympus;
    bool hasOlympusData = olympus.CameraType2[0] != '\0' || olympus.ValidBits != 0 || olympus.SensorCalibration[0] != 0;
    if (hasOlympusData) {
        cJSON* olympusObj = cJSON_CreateObject();
        addStringIfNotEmpty(olympusObj, "CameraType2", olympus.CameraType2);
        addNumberIfNonZero(olympusObj, "ValidBits", olympus.ValidBits);
        // SensorCalibration - only if non-zero
        if (olympus.SensorCalibration[0] != 0 || olympus.SensorCalibration[1] != 0) {
            cJSON_AddNumberToObject(olympusObj, "SensorCalibration_0", olympus.SensorCalibration[0]);
            cJSON_AddNumberToObject(olympusObj, "SensorCalibration_1", olympus.SensorCalibration[1]);
        }
        addNumberIfNonZero(olympusObj, "ColorSpace", olympus.ColorSpace);
        // FocusMode - only if non-zero
        if (olympus.FocusMode[0] != 0 || olympus.FocusMode[1] != 0) {
            cJSON_AddNumberToObject(olympusObj, "FocusMode_0", olympus.FocusMode[0]);
            cJSON_AddNumberToObject(olympusObj, "FocusMode_1", olympus.FocusMode[1]);
        }
        addNumberIfNonZero(olympusObj, "AutoFocus", olympus.AutoFocus);
        addNumberIfNonZero(olympusObj, "AFPoint", olympus.AFPoint);
        addNumberIfNonZero(olympusObj, "AFResult", olympus.AFResult);
        addNumberIfNonZero(olympusObj, "AFFineTune", olympus.AFFineTune);
        addNumberIfNonZero(olympusObj, "ZoomStepCount", olympus.ZoomStepCount);
        addNumberIfNonZero(olympusObj, "FocusStepCount", olympus.FocusStepCount);
        addNumberIfNonZero(olympusObj, "FocusStepInfinity", olympus.FocusStepInfinity);
        addNumberIfNonZero(olympusObj, "FocusStepNear", olympus.FocusStepNear);
        addNumberIfNonZero(olympusObj, "FocusDistance", olympus.FocusDistance);
        addNumberIfNonZero(olympusObj, "isLiveND", olympus.isLiveND);
        addNumberIfNonZero(olympusObj, "LiveNDfactor", olympus.LiveNDfactor);
        addNumberIfNonZero(olympusObj, "Panorama_mode", olympus.Panorama_mode);
        addNumberIfNonZero(olympusObj, "Panorama_frameNum", olympus.Panorama_frameNum);
        // AspectFrame - only if any non-zero
        bool hasAspectFrame = false;
        for (int i = 0; i < 4; i++) if (olympus.AspectFrame[i] != 0) hasAspectFrame = true;
        if (hasAspectFrame) {
            cJSON* aspectFrameArr = cJSON_CreateArray();
            for (int i = 0; i < 4; i++) cJSON_AddItemToArray(aspectFrameArr, cJSON_CreateNumber(olympus.AspectFrame[i]));
            cJSON_AddItemToObject(olympusObj, "AspectFrame", aspectFrameArr);
        }
        // StackedImage - only if any non-zero
        bool hasStackedImage = false;
        for (int i = 0; i < 2; i++) if (olympus.StackedImage[i] != 0) hasStackedImage = true;
        if (hasStackedImage) {
            cJSON* stackedImageArr = cJSON_CreateArray();
            for (int i = 0; i < 2; i++) cJSON_AddItemToArray(stackedImageArr, cJSON_CreateNumber(olympus.StackedImage[i]));
            cJSON_AddItemToObject(olympusObj, "StackedImage", stackedImageArr);
        }
        cJSON_AddItemToObject(makernotesObj, "olympus", olympusObj);
    }

    // === Hasselblad makernotes - only if it's a Hasselblad camera ===
    auto& hasselblad = makernotes.hasselblad;
    bool hasHasselbladData = hasselblad.Sensor[0] != '\0' || hasselblad.BaseISO != 0 || hasselblad.SensorCode != 0;
    if (hasHasselbladData) {
        cJSON* hasselbladObj = cJSON_CreateObject();
        addNumberIfNonZero(hasselbladObj, "BaseISO", hasselblad.BaseISO);
        addNumberIfNonZero(hasselbladObj, "Gain", hasselblad.Gain);
        addStringIfNotEmpty(hasselbladObj, "Sensor", hasselblad.Sensor);
        addStringIfNotEmpty(hasselbladObj, "SensorUnit", hasselblad.SensorUnit);
        addStringIfNotEmpty(hasselbladObj, "HostBody", hasselblad.HostBody);
        addNumberIfNonZero(hasselbladObj, "SensorCode", hasselblad.SensorCode);
        addNumberIfNonZero(hasselbladObj, "SensorSubCode", hasselblad.SensorSubCode);
        addNumberIfNonZero(hasselbladObj, "CoatingCode", hasselblad.CoatingCode);
        addNumberIfNonZero(hasselbladObj, "uncropped", hasselblad.uncropped);
        addStringIfNotEmpty(hasselbladObj, "CaptureSequenceInitiator", hasselblad.CaptureSequenceInitiator);
        addStringIfNotEmpty(hasselbladObj, "SensorUnitConnector", hasselblad.SensorUnitConnector);
        addNumberIfNonZero(hasselbladObj, "format", hasselblad.format);
        // nIFD_CM - only if non-zero
        if (hasselblad.nIFD_CM[0] != 0 || hasselblad.nIFD_CM[1] != 0) {
            cJSON_AddNumberToObject(hasselbladObj, "nIFD_CM_0", hasselblad.nIFD_CM[0]);
            cJSON_AddNumberToObject(hasselbladObj, "nIFD_CM_1", hasselblad.nIFD_CM[1]);
        }
        // RecommendedCrop - only if non-zero
        if (hasselblad.RecommendedCrop[0] != 0 || hasselblad.RecommendedCrop[1] != 0) {
            cJSON_AddNumberToObject(hasselbladObj, "RecommendedCrop_0", hasselblad.RecommendedCrop[0]);
            cJSON_AddNumberToObject(hasselbladObj, "RecommendedCrop_1", hasselblad.RecommendedCrop[1]);
        }
        cJSON_AddItemToObject(makernotesObj, "hasselblad", hasselbladObj);
    }

    // === Panasonic makernotes - only if it's a Panasonic camera ===
    auto& panasonic = makernotes.panasonic;
    bool hasPanasonicData = panasonic.Compression != 0 || panasonic.BlackLevelDim != 0 || panasonic.gamma != 0;
    if (hasPanasonicData) {
        cJSON* panasonicObj = cJSON_CreateObject();
        addNumberIfNonZero(panasonicObj, "Compression", panasonic.Compression);
        addNumberIfNonZero(panasonicObj, "BlackLevelDim", panasonic.BlackLevelDim);
        // BlackLevel - only if any non-zero
        bool hasBlackLevel = false;
        for (int i = 0; i < 8; i++) if (panasonic.BlackLevel[i] != 0) hasBlackLevel = true;
        if (hasBlackLevel) {
            cJSON* panBlackArr = cJSON_CreateArray();
            for (int i = 0; i < 8; i++) cJSON_AddItemToArray(panBlackArr, cJSON_CreateNumber(panasonic.BlackLevel[i]));
            cJSON_AddItemToObject(panasonicObj, "BlackLevel", panBlackArr);
        }
        addNumberIfNonZero(panasonicObj, "Multishot", panasonic.Multishot);
        addNumberIfNonZero(panasonicObj, "gamma", panasonic.gamma);
        // HighISOMultiplier - only if any non-zero
        bool hasHighISO = false;
        for (int i = 0; i < 3; i++) if (panasonic.HighISOMultiplier[i] != 0) hasHighISO = true;
        if (hasHighISO) {
            cJSON* highISOArr = cJSON_CreateArray();
            for (int i = 0; i < 3; i++) cJSON_AddItemToArray(highISOArr, cJSON_CreateNumber(panasonic.HighISOMultiplier[i]));
            cJSON_AddItemToObject(panasonicObj, "HighISOMultiplier", highISOArr);
        }
        addNumberIfNonZero(panasonicObj, "FocusStepNear", panasonic.FocusStepNear);
        addNumberIfNonZero(panasonicObj, "FocusStepCount", panasonic.FocusStepCount);
        addNumberIfNonZero(panasonicObj, "ZoomPosition", panasonic.ZoomPosition);
        addNumberIfNonZero(panasonicObj, "LensManufacturer", panasonic.LensManufacturer);
        cJSON_AddItemToObject(makernotesObj, "panasonic", panasonicObj);
    }

    // === Pentax makernotes - only if it's a Pentax camera ===
    auto& pentax = makernotes.pentax;
    bool hasPentaxData = pentax.FocusMode[0] != 0 || pentax.AFPointsInFocus != 0 || pentax.FocusPosition != 0;
    if (hasPentaxData) {
        cJSON* pentaxObj = cJSON_CreateObject();
        // FocusMode - only if non-zero
        if (pentax.FocusMode[0] != 0 || pentax.FocusMode[1] != 0) {
            cJSON_AddNumberToObject(pentaxObj, "FocusMode_0", pentax.FocusMode[0]);
            cJSON_AddNumberToObject(pentaxObj, "FocusMode_1", pentax.FocusMode[1]);
        }
        // AFPointSelected - only if non-zero
        if (pentax.AFPointSelected[0] != 0 || pentax.AFPointSelected[1] != 0) {
            cJSON_AddNumberToObject(pentaxObj, "AFPointSelected_0", pentax.AFPointSelected[0]);
            cJSON_AddNumberToObject(pentaxObj, "AFPointSelected_1", pentax.AFPointSelected[1]);
        }
        addNumberIfNonZero(pentaxObj, "AFPointSelected_Area", pentax.AFPointSelected_Area);
        addNumberIfNonZero(pentaxObj, "AFPointsInFocus_version", pentax.AFPointsInFocus_version);
        addNumberIfNonZero(pentaxObj, "AFPointsInFocus", pentax.AFPointsInFocus);
        addNumberIfNonZero(pentaxObj, "FocusPosition", pentax.FocusPosition);
        addNumberIfNonZero(pentaxObj, "AFAdjustment", pentax.AFAdjustment);
        addNumberIfNonZero(pentaxObj, "AFPointMode", pentax.AFPointMode);
        addNumberIfNonZero(pentaxObj, "MultiExposure", pentax.MultiExposure);
        addNumberIfNonZero(pentaxObj, "Quality", pentax.Quality);
        cJSON_AddItemToObject(makernotesObj, "pentax", pentaxObj);
    }

    // === Phase One makernotes - only if it's a Phase One camera ===
    auto& phaseone = makernotes.phaseone;
    bool hasPhaseOneData = phaseone.Software[0] != '\0' || phaseone.SystemType[0] != '\0' || phaseone.SystemModel[0] != '\0';
    if (hasPhaseOneData) {
        cJSON* phaseoneObj = cJSON_CreateObject();
        addStringIfNotEmpty(phaseoneObj, "Software", phaseone.Software);
        addStringIfNotEmpty(phaseoneObj, "SystemType", phaseone.SystemType);
        addStringIfNotEmpty(phaseoneObj, "FirmwareString", phaseone.FirmwareString);
        addStringIfNotEmpty(phaseoneObj, "SystemModel", phaseone.SystemModel);
        cJSON_AddItemToObject(makernotesObj, "phaseone", phaseoneObj);
    }

    // === Ricoh makernotes - only if it's a Ricoh camera ===
    auto& ricoh = makernotes.ricoh;
    bool hasRicohData = ricoh.SensorWidth != 0 || ricoh.AFStatus != 0 || ricoh.CropMode != 0;
    if (hasRicohData) {
        cJSON* ricohObj = cJSON_CreateObject();
        addNumberIfNonZero(ricohObj, "AFStatus", ricoh.AFStatus);
        // AFAreaXPosition - only if non-zero
        if (ricoh.AFAreaXPosition[0] != 0 || ricoh.AFAreaXPosition[1] != 0) {
            cJSON_AddNumberToObject(ricohObj, "AFAreaXPosition_0", ricoh.AFAreaXPosition[0]);
            cJSON_AddNumberToObject(ricohObj, "AFAreaXPosition_1", ricoh.AFAreaXPosition[1]);
        }
        // AFAreaYPosition - only if non-zero
        if (ricoh.AFAreaYPosition[0] != 0 || ricoh.AFAreaYPosition[1] != 0) {
            cJSON_AddNumberToObject(ricohObj, "AFAreaYPosition_0", ricoh.AFAreaYPosition[0]);
            cJSON_AddNumberToObject(ricohObj, "AFAreaYPosition_1", ricoh.AFAreaYPosition[1]);
        }
        addNumberIfNonZero(ricohObj, "AFAreaMode", ricoh.AFAreaMode);
        addNumberIfPositive(ricohObj, "SensorWidth", ricoh.SensorWidth);
        addNumberIfPositive(ricohObj, "SensorHeight", ricoh.SensorHeight);
        addNumberIfPositive(ricohObj, "CroppedImageWidth", ricoh.CroppedImageWidth);
        addNumberIfPositive(ricohObj, "CroppedImageHeight", ricoh.CroppedImageHeight);
        addNumberIfNonZero(ricohObj, "WideAdapter", ricoh.WideAdapter);
        addNumberIfNonZero(ricohObj, "CropMode", ricoh.CropMode);
        addNumberIfNonZero(ricohObj, "NDFilter", ricoh.NDFilter);
        addNumberIfNonZero(ricohObj, "AutoBracketing", ricoh.AutoBracketing);
        addNumberIfNonZero(ricohObj, "MacroMode", ricoh.MacroMode);
        addNumberIfNonZero(ricohObj, "FlashMode", ricoh.FlashMode);
        addNumberIfNonZero(ricohObj, "FlashExposureComp", ricoh.FlashExposureComp);
        addNumberIfNonZero(ricohObj, "ManualFlashOutput", ricoh.ManualFlashOutput);
        cJSON_AddItemToObject(makernotesObj, "ricoh", ricohObj);
    }

    // === Samsung makernotes - only if it's a Samsung camera ===
    auto& samsung = makernotes.samsung;
    bool hasSamsungData = samsung.DeviceType != 0 || samsung.LensFirmware[0] != '\0' || samsung.DigitalGain != 0;
    if (hasSamsungData) {
        cJSON* samsungObj = cJSON_CreateObject();
        // ImageSizeFull - only if any non-zero
        bool hasImageSizeFull = false;
        for (int i = 0; i < 4; i++) if (samsung.ImageSizeFull[i] != 0) hasImageSizeFull = true;
        if (hasImageSizeFull) {
            cJSON* samsungFullArr = cJSON_CreateArray();
            for (int i = 0; i < 4; i++) cJSON_AddItemToArray(samsungFullArr, cJSON_CreateNumber(samsung.ImageSizeFull[i]));
            cJSON_AddItemToObject(samsungObj, "ImageSizeFull", samsungFullArr);
        }
        // ImageSizeCrop - only if any non-zero
        bool hasImageSizeCrop = false;
        for (int i = 0; i < 4; i++) if (samsung.ImageSizeCrop[i] != 0) hasImageSizeCrop = true;
        if (hasImageSizeCrop) {
            cJSON* samsungCropArr = cJSON_CreateArray();
            for (int i = 0; i < 4; i++) cJSON_AddItemToArray(samsungCropArr, cJSON_CreateNumber(samsung.ImageSizeCrop[i]));
            cJSON_AddItemToObject(samsungObj, "ImageSizeCrop", samsungCropArr);
        }
        // ColorSpace - only if non-zero
        if (samsung.ColorSpace[0] != 0 || samsung.ColorSpace[1] != 0) {
            cJSON_AddNumberToObject(samsungObj, "ColorSpace_0", samsung.ColorSpace[0]);
            cJSON_AddNumberToObject(samsungObj, "ColorSpace_1", samsung.ColorSpace[1]);
        }
        addNumberIfNonZero(samsungObj, "DigitalGain", samsung.DigitalGain);
        addNumberIfNonZero(samsungObj, "DeviceType", samsung.DeviceType);
        addStringIfNotEmpty(samsungObj, "LensFirmware", samsung.LensFirmware);
        cJSON_AddItemToObject(makernotesObj, "samsung", samsungObj);
    }

    // === Kodak makernotes - only if it's a Kodak camera ===
    auto& kodak = makernotes.kodak;
    bool hasKodakData = kodak.BlackLevelTop != 0 || kodak.clipBlack != 0 || kodak.AnalogISO != 0;
    if (hasKodakData) {
        cJSON* kodakObj = cJSON_CreateObject();
        addNumberIfNonZero(kodakObj, "BlackLevelTop", kodak.BlackLevelTop);
        addNumberIfNonZero(kodakObj, "BlackLevelBottom", kodak.BlackLevelBottom);
        addNumberIfNonZero(kodakObj, "offset_left", kodak.offset_left);
        addNumberIfNonZero(kodakObj, "offset_top", kodak.offset_top);
        addNumberIfNonZero(kodakObj, "clipBlack", kodak.clipBlack);
        addNumberIfNonZero(kodakObj, "clipWhite", kodak.clipWhite);
        addNumberIfNonZero(kodakObj, "val018percent", kodak.val018percent);
        addNumberIfNonZero(kodakObj, "val100percent", kodak.val100percent);
        addNumberIfNonZero(kodakObj, "val170percent", kodak.val170percent);
        addNumberIfNonZero(kodakObj, "MakerNoteKodak8a", kodak.MakerNoteKodak8a);
        addNumberIfNonZero(kodakObj, "ISOCalibrationGain", kodak.ISOCalibrationGain);
        addNumberIfNonZero(kodakObj, "AnalogISO", kodak.AnalogISO);
        cJSON_AddItemToObject(makernotesObj, "kodak", kodakObj);
    }

    // Only add makernotes if it has any content
    if (cJSON_GetArraySize(makernotesObj) > 0) {
        cJSON_AddItemToObject(root, "makernotes", makernotesObj);
    } else {
        cJSON_Delete(makernotesObj);
    }
    
    // Camera info (legacy flat fields for compatibility)
    addStringIfNotEmpty(root, "make", idata.make);
    addStringIfNotEmpty(root, "model", idata.model);
    addStringIfNotEmpty(root, "software", idata.software);
    
    // Lens info
    addStringIfNotEmpty(root, "lens_make", lens.LensMake);
    addStringIfNotEmpty(root, "lens_model", lens.Lens);
    addStringIfNotEmpty(root, "lens_serial", lens.LensSerial);
    
    // Focal lengths
    addNumberIfPositive(root, "focal_length", other.focal_len);
    addNumberIfPositive(root, "min_focal", lens.MinFocal);
    addNumberIfPositive(root, "max_focal", lens.MaxFocal);
    addNumberIfPositive(root, "focal_length_35mm", lens.FocalLengthIn35mmFormat);
    
    // Exposure info
    addNumberIfPositive(root, "aperture", other.aperture);
    if (shutterStr[0] != '\0') {
        cJSON_AddStringToObject(root, "shutter", shutterStr);
    }
    addNumberIfPositive(root, "shutter_raw", other.shutter);
    addNumberIfPositive(root, "iso", other.iso_speed);
    
    // Shooting info
    addNumberIfNonZero(root, "exposure_program", shootinginfo.ExposureProgram);
    addNumberIfNonZero(root, "metering_mode", shootinginfo.MeteringMode);
    
    // Description and artist
    addStringIfNotEmpty(root, "description", other.desc);
    addStringIfNotEmpty(root, "artist", other.artist);
    
    // Body serial
    addStringIfNotEmpty(root, "body_serial", shootinginfo.BodySerial);
    
    // Timestamp
    if (dateStr[0] != '\0') {
        cJSON_AddStringToObject(root, "timestamp", dateStr);
    }
    addNumberIfNonZero(root, "timestamp_raw", (double)other.timestamp);
    
    // Dimensions (always include these as they're essential)
    cJSON_AddNumberToObject(root, "width", sizes.width);
    cJSON_AddNumberToObject(root, "height", sizes.height);
    addNumberIfPositive(root, "raw_width", sizes.raw_width);
    addNumberIfPositive(root, "raw_height", sizes.raw_height);
    addNumberIfNonZero(root, "orientation", sizes.flip);
    
    // Color info
    addNumberIfPositive(root, "colors", idata.colors);
    addStringIfNotEmpty(root, "bayer_pattern", idata.cdesc);
    
    // GPS data - only include if we have actual valid GPS coordinates
    bool hasGps = (other.parsed_gps.gpsparsed != 0);
    bool hasValidGps = hasGps && 
        (other.parsed_gps.latitude[0] != 0 || other.parsed_gps.latitude[1] != 0 || other.parsed_gps.latitude[2] != 0) &&
        (other.parsed_gps.longitude[0] != 0 || other.parsed_gps.longitude[1] != 0 || other.parsed_gps.longitude[2] != 0);
    
    cJSON_AddBoolToObject(root, "has_gps", hasValidGps);
    
    if (hasValidGps) {
        // Latitude
        cJSON_AddNumberToObject(root, "gps_lat_deg", other.parsed_gps.latitude[0]);
        cJSON_AddNumberToObject(root, "gps_lat_min", other.parsed_gps.latitude[1]);
        cJSON_AddNumberToObject(root, "gps_lat_sec", other.parsed_gps.latitude[2]);
        
        // Handle null or empty ref chars safely
        char latRefStr[2] = {other.parsed_gps.latref, '\0'};
        if (latRefStr[0] == 0 || (latRefStr[0] != 'N' && latRefStr[0] != 'S')) latRefStr[0] = 'N';
        cJSON_AddStringToObject(root, "gps_lat_ref", latRefStr);
        
        // Longitude
        cJSON_AddNumberToObject(root, "gps_lon_deg", other.parsed_gps.longitude[0]);
        cJSON_AddNumberToObject(root, "gps_lon_min", other.parsed_gps.longitude[1]);
        cJSON_AddNumberToObject(root, "gps_lon_sec", other.parsed_gps.longitude[2]);
        
        char lonRefStr[2] = {other.parsed_gps.longref, '\0'};
        if (lonRefStr[0] == 0 || (lonRefStr[0] != 'E' && lonRefStr[0] != 'W')) lonRefStr[0] = 'E';
        cJSON_AddStringToObject(root, "gps_lon_ref", lonRefStr);
        
        // Altitude
        cJSON_AddNumberToObject(root, "gps_altitude", other.parsed_gps.altitude);
        cJSON_AddNumberToObject(root, "gps_alt_ref", other.parsed_gps.altref);
        
        // GPS timestamp
        cJSON_AddNumberToObject(root, "gps_time_hour", other.parsed_gps.gpstimestamp[0]);
        cJSON_AddNumberToObject(root, "gps_time_min", other.parsed_gps.gpstimestamp[1]);
        cJSON_AddNumberToObject(root, "gps_time_sec", other.parsed_gps.gpstimestamp[2]);
    }
    
    // Generate JSON string
    char* jsonStr = cJSON_PrintUnformatted(root);
    if (jsonStr) {
        jsonOutput = jsonStr;
        cJSON_free(jsonStr);
    } else {
        cJSON_Delete(root);
        errorMessage = "Failed to generate JSON string";
        return false;
    }
    
    cJSON_Delete(root);
    processor.recycle();
    
    LOGD("Extracted metadata JSON: %s", jsonOutput.c_str());
    return true;
}

} // namespace raw2dng
