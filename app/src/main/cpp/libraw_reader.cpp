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
    
    // EXIF
    metadata_.iso_speed = data.other.iso_speed;
    metadata_.shutter = data.other.shutter;
    metadata_.aperture = data.other.aperture;
    metadata_.focal_len = data.other.focal_len;
    metadata_.timestamp = data.other.timestamp;
    
    // Lens info
    if (data.lens.LensMake[0]) {
        metadata_.lens_make = data.lens.LensMake;
    }
    if (data.lens.Lens[0]) {
        metadata_.lens_model = data.lens.Lens;
    }
    
    LOGD("Metadata extracted:");
    LOGD("  Camera: %s %s", metadata_.make.c_str(), metadata_.model.c_str());
    LOGD("  Raw size: %dx%d, pitch: %d", metadata_.raw_width, metadata_.raw_height, metadata_.raw_pitch);
    LOGD("  Active area: %dx%d at (%d,%d)", 
         metadata_.width, metadata_.height,
         metadata_.left_margin, metadata_.top_margin);
    LOGD("  Black: %u, Maximum: %u", metadata_.black, metadata_.maximum);
    LOGD("  ISO: %.0f, Shutter: %.4f, Aperture: f/%.1f",
         metadata_.iso_speed, metadata_.shutter, metadata_.aperture);
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
                                    std::string& errorMessage) {
    LibRaw processor;
    
    LOGD("Extracting thumbnail from: %s", inputPath.c_str());
    
    // Open the file
    int ret = processor.open_file(inputPath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        errorMessage = "Failed to open RAW file: " + std::string(libraw_strerror(ret));
        LOGE("%s", errorMessage.c_str());
        return false;
    }
    
    // Try to unpack embedded thumbnail
    ret = processor.unpack_thumb();
    if (ret == LIBRAW_SUCCESS) {
        // Write the embedded thumbnail
        LOGD("Found embedded thumbnail, format: %d, size: %d bytes",
             processor.imgdata.thumbnail.tformat,
             processor.imgdata.thumbnail.tlength);
        
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

} // namespace raw2dng
