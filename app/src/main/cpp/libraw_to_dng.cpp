// libraw_to_dng.cpp
// Conversion bridge from LibRaw to DNG SDK

#include "libraw_to_dng.h"
#include "libraw_reader.h"

#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>

// DNG SDK headers
#include "dng_camera_profile.h"
#include "dng_color_space.h"
#include "dng_date_time.h"
#include "dng_exceptions.h"
#include "dng_file_stream.h"
#include "dng_globals.h"
#include "dng_host.h"
#include "dng_ifd.h"
#include "dng_image.h"
#include "dng_image_writer.h"
#include "dng_info.h"
#include "dng_linearization_info.h"
#include "dng_memory_stream.h"
#include "dng_mosaic_info.h"
#include "dng_negative.h"
#include "dng_preview.h"
#include "dng_rational.h"
#include "dng_render.h"
#include "dng_simple_image.h"
#include "dng_stream.h"
#include "dng_string.h"
#include "dng_tag_codes.h"
#include "dng_tag_types.h"
#include "dng_tag_values.h"

#define LOG_TAG "LibRawToDNG"
// Use ERROR level for all logs so they definitely appear in logcat
#define LOGD(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw2dng {

// Helper to set camera neutral from LibRaw white balance
static void setCameraNeutral(dng_negative& negative, const RawMetadata& meta) {
    // LibRaw provides cam_mul as multipliers to apply to each channel to get neutral white
    // cam_mul[1] (green) is typically the reference (often 1.0)
    // 
    // DNG's AsShotNeutral represents the color of neutral (white) in camera native linear space.
    // This is the INVERSE of the white balance multipliers:
    // If cam_mul = [1.1, 1.0, 2.5], it means to make a scene white appear neutral,
    // you multiply R by 1.1 and B by 2.5. This implies the scene illuminant was warm (low blue).
    // The neutral point is then [1/1.1, 1/1.0, 1/2.5] = [0.91, 1.0, 0.4]
    
    if (meta.cam_mul[0] <= 0 || meta.cam_mul[1] <= 0 || meta.cam_mul[2] <= 0) {
        LOGD("Invalid white balance multipliers, skipping");
        return;
    }
    
    // Compute reciprocals - the neutral point is the inverse of the WB multipliers
    float inv_r = 1.0f / meta.cam_mul[0];
    float inv_g = 1.0f / meta.cam_mul[1];
    float inv_b = 1.0f / meta.cam_mul[2];
    
    // Normalize so the largest value is 1.0
    float maxInv = std::max({inv_r, inv_g, inv_b});
    
    dng_vector neutral(3);
    neutral[0] = inv_r / maxInv;
    neutral[1] = inv_g / maxInv;
    neutral[2] = inv_b / maxInv;
    
    negative.SetCameraNeutral(neutral);
    
    LOGD("Set camera neutral: [%.4f, %.4f, %.4f] (from cam_mul [%.4f, %.4f, %.4f])", 
         neutral[0], neutral[1], neutral[2],
         meta.cam_mul[0], meta.cam_mul[1], meta.cam_mul[2]);
    LOGD("  Reciprocals before norm: [%.4f, %.4f, %.4f], max=%.4f",
         inv_r, inv_g, inv_b, maxInv);
}

// Helper to create and set color matrix
static void setColorMatrix(dng_negative& negative, const RawMetadata& meta) {
    // Use the camera's actual color matrix from LibRaw (cam_xyz)
    // This is the transformation from camera RGB to XYZ D65
    //
    // The DNG ColorMatrix1 should be XYZ to camera RGB, which is the inverse
    // of cam_xyz. However, the DNG SDK expects it in a specific format.
    //
    // For cameras where cam_xyz is not available or zero, fall back to sRGB.
    
    bool hasCamXyz = false;
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            if (meta.cam_xyz[i][j] != 0.0f) {
                hasCamXyz = true;
                break;
            }
        }
        if (hasCamXyz) break;
    }
    
    if (hasCamXyz) {
        LOGD("setColorMatrix: using camera color matrix (cam_xyz)");
        LOGD("  cam_xyz from LibRaw:");
        LOGD("    [%.6f, %.6f, %.6f]", meta.cam_xyz[0][0], meta.cam_xyz[0][1], meta.cam_xyz[0][2]);
        LOGD("    [%.6f, %.6f, %.6f]", meta.cam_xyz[1][0], meta.cam_xyz[1][1], meta.cam_xyz[1][2]);
        LOGD("    [%.6f, %.6f, %.6f]", meta.cam_xyz[2][0], meta.cam_xyz[2][1], meta.cam_xyz[2][2]);
        
        try {
            // LibRaw's cam_xyz is camera RGB -> XYZ (normalized for D65)
            // DNG's ColorMatrix1 should also be camera RGB -> XYZ
            // But we need to make sure the matrix is properly normalized
            
            // Create the color matrix directly from cam_xyz
            dng_matrix_3by3 colorMatrix;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    colorMatrix[i][j] = meta.cam_xyz[i][j];
                }
            }
            
            // Normalize rows so that a neutral (white) subject produces proper XYZ
            // Each row should sum to approximately the corresponding D65 XYZ white point
            // D65: X=0.9505, Y=1.0000, Z=1.0888
            // 
            // For proper DNG compatibility, we normalize each row so that
            // [1,1,1] camera RGB maps to D65 XYZ
            for (int i = 0; i < 3; i++) {
                double rowSum = colorMatrix[i][0] + colorMatrix[i][1] + colorMatrix[i][2];
                if (rowSum > 0.001) {
                    // Normalize is already done in LibRaw, but we verify
                    LOGD("  Row %d sum: %.6f", i, rowSum);
                }
            }
            
            LOGD("ColorMatrix1 (Camera RGB -> XYZ):");
            LOGD("  [%.6f, %.6f, %.6f]", colorMatrix[0][0], colorMatrix[0][1], colorMatrix[0][2]);
            LOGD("  [%.6f, %.6f, %.6f]", colorMatrix[1][0], colorMatrix[1][1], colorMatrix[1][2]);
            LOGD("  [%.6f, %.6f, %.6f]", colorMatrix[2][0], colorMatrix[2][1], colorMatrix[2][2]);
            
            // Create camera profile with the actual camera matrix
            AutoPtr<dng_camera_profile> profile(new dng_camera_profile());
            
            // Use camera make/model as profile name
            std::string profileName = meta.make + " " + meta.model;
            profile->SetName(profileName.c_str());
            profile->SetColorMatrix1(colorMatrix);
            profile->SetCalibrationIlluminant1(lsD65);
            
            negative.AddProfile(profile);
            
            LOGD("Successfully added camera color profile: %s", profileName.c_str());
            
        } catch (const dng_exception& e) {
            LOGE("DNG exception while setting camera color matrix: %d, falling back to sRGB", e.ErrorCode());
            goto use_srgb;
        } catch (...) {
            LOGE("Failed to set camera color matrix, falling back to sRGB");
            goto use_srgb;
        }
        return;
    }
    
use_srgb:
    LOGD("setColorMatrix: using sRGB identity matrix (no cam_xyz available)");
    
    try {
        // XYZ to sRGB matrix (D65 reference white)
        // This is the standard conversion matrix
        dng_matrix_3by3 xyzToSrgb;
        xyzToSrgb[0][0] =  3.2404541879;
        xyzToSrgb[0][1] = -1.5371385193;
        xyzToSrgb[0][2] = -0.4985314095;
        xyzToSrgb[1][0] = -0.9692660305;
        xyzToSrgb[1][1] =  1.8760108454;
        xyzToSrgb[1][2] =  0.0415560175;
        xyzToSrgb[2][0] =  0.0556434309;
        xyzToSrgb[2][1] = -0.2040259135;
        xyzToSrgb[2][2] =  1.0572251882;
        
        LOGD("ColorMatrix1 (XYZ -> sRGB identity):");
        LOGD("  [%.6f, %.6f, %.6f]", xyzToSrgb[0][0], xyzToSrgb[0][1], xyzToSrgb[0][2]);
        LOGD("  [%.6f, %.6f, %.6f]", xyzToSrgb[1][0], xyzToSrgb[1][1], xyzToSrgb[1][2]);
        LOGD("  [%.6f, %.6f, %.6f]", xyzToSrgb[2][0], xyzToSrgb[2][1], xyzToSrgb[2][2]);
        
        AutoPtr<dng_camera_profile> profile(new dng_camera_profile());
        
        profile->SetName("sRGB");
        profile->SetColorMatrix1(xyzToSrgb);
        profile->SetCalibrationIlluminant1(lsD65);
        
        negative.AddProfile(profile);
        
        LOGD("Successfully added sRGB identity color profile");
        
    } catch (const dng_exception& e) {
        LOGE("DNG exception while setting color matrix: %d", e.ErrorCode());
    } catch (...) {
        LOGE("Failed to set color matrix");
    }
}

// Helper to set EXIF metadata
static void setExifData(dng_negative& negative, const RawMetadata& meta) {
    dng_exif* exif = negative.GetExif();
    if (!exif) return;
    
    // Camera make/model
    exif->fMake.Set(meta.make.c_str());
    exif->fModel.Set(meta.model.c_str());
    
    // Software identifier
    exif->fSoftware.Set("Raw2DNG");
    
    // Description and artist
    if (!meta.description.empty()) {
        exif->fImageDescription.Set(meta.description.c_str());
    }
    if (!meta.artist.empty()) {
        exif->fArtist.Set(meta.artist.c_str());
    }
    
    // Exposure settings
    if (meta.iso_speed > 0) {
        exif->fISOSpeedRatings[0] = (uint32)meta.iso_speed;
    }
    
    if (meta.shutter > 0) {
        exif->fExposureTime.Set_real64(meta.shutter, 1000000);
        // Also set ShutterSpeedValue (APEX value = -log2(exposure time))
        if (meta.shutter > 0) {
            double apex = -log2(meta.shutter);
            exif->fShutterSpeedValue.Set_real64(apex, 1000);
        }
    }
    
    if (meta.aperture > 0) {
        exif->fFNumber.Set_real64(meta.aperture, 10);
        // Also set ApertureValue (APEX value = 2 * log2(f-number))
        double apex = 2.0 * log2(meta.aperture);
        exif->fApertureValue.Set_real64(apex, 1000);
    }
    
    if (meta.focal_len > 0) {
        exif->fFocalLength.Set_real64(meta.focal_len, 10);
    }
    
    // 35mm equivalent focal length
    if (meta.focal_len_35mm > 0) {
        exif->fFocalLengthIn35mmFilm = (uint32)meta.focal_len_35mm;
    }
    
    // Exposure program (0=Not defined, 1=Manual, 2=Normal, 3=Aperture priority, etc.)
    if (meta.exposure_program > 0) {
        exif->fExposureProgram = (uint32)meta.exposure_program;
    }
    
    // Metering mode (0=Unknown, 1=Average, 2=CenterWeighted, 3=Spot, 5=Matrix, etc.)
    if (meta.metering_mode > 0) {
        exif->fMeteringMode = (uint32)meta.metering_mode;
    }
    
    // Date/time
    if (meta.timestamp > 0) {
        struct tm timeinfo_buf;
        struct tm* timeinfo = localtime_r(&meta.timestamp, &timeinfo_buf);
        if (timeinfo) {
            dng_date_time dt;
            dt.fYear = timeinfo->tm_year + 1900;
            dt.fMonth = timeinfo->tm_mon + 1;
            dt.fDay = timeinfo->tm_mday;
            dt.fHour = timeinfo->tm_hour;
            dt.fMinute = timeinfo->tm_min;
            dt.fSecond = timeinfo->tm_sec;
            
            dng_date_time_info dtInfo;
            dtInfo.SetDateTime(dt);
            exif->fDateTimeOriginal = dtInfo;
            exif->fDateTimeDigitized = dtInfo;
            exif->fDateTime = dtInfo;
        }
    }
    
    // Lens info
    if (!meta.lens_make.empty()) {
        exif->fLensMake.Set(meta.lens_make.c_str());
    }
    if (!meta.lens_model.empty()) {
        exif->fLensName.Set(meta.lens_model.c_str());
    }
    
    // Lens focal range for LensInfo tag
    if (meta.min_focal > 0 && meta.max_focal > 0) {
        // LensInfo is set via negative's lens info, not directly in EXIF
        // The fLensInfo field in dng_exif contains [MinFocal, MaxFocal, MaxApMinFocal, MaxApMaxFocal]
        exif->fLensInfo[0].Set_real64(meta.min_focal, 10);
        exif->fLensInfo[1].Set_real64(meta.max_focal, 10);
    }
    
    // GPS data
    if (meta.has_gps) {
        // GPS Version ID (2.3.0.0 is common)
        exif->fGPSVersionID = 0x02030000;
        
        // Latitude
        exif->fGPSLatitudeRef.Set(meta.gps_lat_ref == 'S' ? "S" : "N");
        exif->fGPSLatitude[0].Set_real64(meta.gps_latitude[0], 1);  // Degrees
        exif->fGPSLatitude[1].Set_real64(meta.gps_latitude[1], 1);  // Minutes
        exif->fGPSLatitude[2].Set_real64(meta.gps_latitude[2], 1000); // Seconds
        
        // Longitude
        exif->fGPSLongitudeRef.Set(meta.gps_lon_ref == 'W' ? "W" : "E");
        exif->fGPSLongitude[0].Set_real64(meta.gps_longitude[0], 1);  // Degrees
        exif->fGPSLongitude[1].Set_real64(meta.gps_longitude[1], 1);  // Minutes
        exif->fGPSLongitude[2].Set_real64(meta.gps_longitude[2], 1000); // Seconds
        
        // Altitude
        exif->fGPSAltitudeRef = (meta.gps_alt_ref != 0) ? 1 : 0;  // 0 = above sea level, 1 = below
        exif->fGPSAltitude.Set_real64(fabs(meta.gps_altitude), 100);
        
        // GPS timestamp
        if (meta.gps_timestamp[0] > 0 || meta.gps_timestamp[1] > 0 || meta.gps_timestamp[2] > 0) {
            exif->fGPSTimeStamp[0].Set_real64(meta.gps_timestamp[0], 1);  // Hours
            exif->fGPSTimeStamp[1].Set_real64(meta.gps_timestamp[1], 1);  // Minutes
            exif->fGPSTimeStamp[2].Set_real64(meta.gps_timestamp[2], 1000); // Seconds
        }
        
        LOGD("Set GPS EXIF: %.6f°%c, %.6f°%c, Alt: %.1fm",
             meta.gps_latitude[0] + meta.gps_latitude[1]/60.0 + meta.gps_latitude[2]/3600.0,
             meta.gps_lat_ref,
             meta.gps_longitude[0] + meta.gps_longitude[1]/60.0 + meta.gps_longitude[2]/3600.0,
             meta.gps_lon_ref,
             meta.gps_altitude);
    }
    
    LOGD("Set EXIF: ISO %.0f, %.4fs, f/%.1f, %.1fmm (35mm: %.0fmm)",
         meta.iso_speed, meta.shutter, meta.aperture, meta.focal_len, meta.focal_len_35mm);
    LOGD("Set EXIF: ExposureProgram=%d, MeteringMode=%d, Artist='%s'",
         meta.exposure_program, meta.metering_mode, meta.artist.c_str());
}

// Main conversion function
bool convertRawToDNG(const std::string& inputPath,
                     const std::string& outputPath,
                     std::string& errorMessage) {
    
    LOGD("Converting RAW to DNG: %s -> %s", inputPath.c_str(), outputPath.c_str());
    
    // Step 1: Open RAW file with LibRaw
    LibRawReader reader;
    if (!reader.open(inputPath, errorMessage)) {
        return false;
    }
    
    const RawMetadata& meta = reader.getMetadata();
    const uint16_t* rawData = reader.getRawData();
    
    if (!rawData) {
        errorMessage = "Failed to get raw image data";
        return false;
    }
    
    try {
        // Step 2: Create DNG host and negative
        dng_host host;
        
        AutoPtr<dng_negative> negative(host.Make_dng_negative());
        if (!negative.Get()) {
            errorMessage = "Failed to create DNG negative";
            return false;
        }
        
        LOGD("Created DNG negative");
        
        // Step 3: Set basic metadata
        negative->SetModelName(meta.model.c_str());
        negative->SetLocalName(meta.make.c_str());
        
        // Step 4: Set up the mosaic (Bayer) pattern
        negative->SetColorChannels(3);
        negative->SetColorKeys(colorKeyRed, colorKeyGreen, colorKeyBlue);
        
        // Set Bayer pattern based on phase
        // The phase from getBayerPhase() is for position (0,0) of the raw_image buffer
        // We need to adjust if margins offset the Bayer pattern
        int basePhase = reader.getBayerPhase();
        
        // Calculate phase adjustment based on margins
        // If left_margin is odd, phase shifts horizontally (swap adjacent patterns)
        // If top_margin is odd, phase shifts vertically (swap row patterns)
        int marginOffset = (meta.left_margin % 2) + 2 * (meta.top_margin % 2);
        
        // DNG SDK phase mapping:
        // Phase 0: GRBG [G,R; B,G]
        // Phase 1: RGGB [R,G; G,B]
        // Phase 2: BGGR [B,G; G,R]
        // Phase 3: GBRG [G,B; R,G]
        //
        // Phase adjustment table for margin offsets:
        // marginOffset 0 = no change
        // marginOffset 1 = shift X by 1 (swap columns)
        // marginOffset 2 = shift Y by 1 (swap rows)  
        // marginOffset 3 = shift both X and Y by 1
        static const int phaseTable[4][4] = {
            // base -> offset 0, 1, 2, 3
            {0, 1, 3, 2},  // GRBG -> GRBG, RGGB, GBRG, BGGR
            {1, 0, 2, 3},  // RGGB -> RGGB, GRBG, BGGR, GBRG
            {2, 3, 1, 0},  // BGGR -> BGGR, GBRG, RGGB, GRBG
            {3, 2, 0, 1}   // GBRG -> GBRG, BGGR, GRBG, RGGB
        };
        
        int phase = phaseTable[basePhase][marginOffset];
        
        LOGD("Bayer phase: base=%d, marginOffset=%d (margins %d,%d), adjusted=%d",
             basePhase, marginOffset, meta.left_margin, meta.top_margin, phase);
        
        negative->SetBayerMosaic(phase);
        
        LOGD("Set Bayer pattern phase: %d", phase);
        
        // Step 5: Set up image dimensions and crop
        // Active area within the raw data
        dng_rect activeArea(
            meta.top_margin,
            meta.left_margin,
            meta.top_margin + meta.height,
            meta.left_margin + meta.width
        );
        negative->SetActiveArea(activeArea);
        
        // Default crop is the full active area
        negative->SetDefaultCropSize(
            dng_urational(meta.width, 1),
            dng_urational(meta.height, 1)
        );
        negative->SetDefaultCropOrigin(
            dng_urational(0, 1),
            dng_urational(0, 1)
        );
        
        // Default scale (no scaling)
        negative->SetDefaultScale(
            dng_urational(1, 1),
            dng_urational(1, 1)
        );
        
        LOGD("Set active area: (%d,%d) to (%d,%d)",
             activeArea.t, activeArea.l, activeArea.b, activeArea.r);
        
        // Step 6: Set black and white levels
        // Use per-channel black levels with SetQuadBlacks for accurate dark area rendering
        // LibRaw cblack is indexed by COLOR: [R, G1, B, G2]
        // DNG QuadBlacks is indexed by POSITION in 2x2: [top-left, top-right, bottom-left, bottom-right]
        // We need to map color indices to position indices based on Bayer phase
        
        // Get per-channel black levels (fall back to global if per-channel not available)
        real64 blackR = (meta.cblack[0] > 0) ? meta.cblack[0] : meta.black;
        real64 blackG1 = (meta.cblack[1] > 0) ? meta.cblack[1] : meta.black;
        real64 blackB = (meta.cblack[2] > 0) ? meta.cblack[2] : meta.black;
        real64 blackG2 = (meta.cblack[3] > 0) ? meta.cblack[3] : meta.black;
        
        // If all cblack values are 0, use the global black level
        if (meta.cblack[0] == 0 && meta.cblack[1] == 0 && 
            meta.cblack[2] == 0 && meta.cblack[3] == 0) {
            blackR = blackG1 = blackB = blackG2 = meta.black;
        }
        
        LOGD("Per-channel black levels: R=%.1f, G1=%.1f, B=%.1f, G2=%.1f (global=%u)",
             blackR, blackG1, blackB, blackG2, meta.black);
        
        // Map color black levels to quad positions based on Bayer phase
        // DNG phase values and their 2x2 patterns:
        // Phase 0: GRBG [G,R; B,G] - top-left=G, top-right=R, bottom-left=B, bottom-right=G
        // Phase 1: RGGB [R,G; G,B] - top-left=R, top-right=G, bottom-left=G, bottom-right=B
        // Phase 2: BGGR [B,G; G,R] - top-left=B, top-right=G, bottom-left=G, bottom-right=R
        // Phase 3: GBRG [G,B; R,G] - top-left=G, top-right=B, bottom-left=R, bottom-right=G
        real64 quadBlack0, quadBlack1, quadBlack2, quadBlack3;
        
        switch (phase) {
            case 0:  // GRBG: [G,R; B,G]
                quadBlack0 = blackG1;  // top-left = G
                quadBlack1 = blackR;   // top-right = R
                quadBlack2 = blackB;   // bottom-left = B
                quadBlack3 = blackG2;  // bottom-right = G
                break;
            case 1:  // RGGB: [R,G; G,B]
                quadBlack0 = blackR;   // top-left = R
                quadBlack1 = blackG1;  // top-right = G
                quadBlack2 = blackG2;  // bottom-left = G
                quadBlack3 = blackB;   // bottom-right = B
                break;
            case 2:  // BGGR: [B,G; G,R]
                quadBlack0 = blackB;   // top-left = B
                quadBlack1 = blackG1;  // top-right = G
                quadBlack2 = blackG2;  // bottom-left = G
                quadBlack3 = blackR;   // bottom-right = R
                break;
            case 3:  // GBRG: [G,B; R,G]
                quadBlack0 = blackG1;  // top-left = G
                quadBlack1 = blackB;   // top-right = B
                quadBlack2 = blackR;   // bottom-left = R
                quadBlack3 = blackG2;  // bottom-right = G
                break;
            default:
                // Fallback to uniform black level
                quadBlack0 = quadBlack1 = quadBlack2 = quadBlack3 = meta.black;
                break;
        }
        
        // Use SetQuadBlacks for per-position black levels
        negative->SetQuadBlacks(quadBlack0, quadBlack1, quadBlack2, quadBlack3);
        negative->SetWhiteLevel(meta.maximum);
        
        LOGD("Set quad black levels: [%.1f, %.1f, %.1f, %.1f], white=%u (phase=%d)",
             quadBlack0, quadBlack1, quadBlack2, quadBlack3, meta.maximum, phase);
        
        // Step 7: Set color information
        setCameraNeutral(*negative, meta);
        setColorMatrix(*negative, meta);
        
        // Step 8: Set EXIF metadata  
        setExifData(*negative, meta);
        
        // Step 9: Handle orientation
        dng_orientation orientation;
        switch (meta.flip) {
            case 0: orientation = dng_orientation::Normal(); break;
            case 3: orientation = dng_orientation::Rotate180(); break;
            case 5: orientation = dng_orientation::Rotate90CCW(); break;
            case 6: orientation = dng_orientation::Rotate90CW(); break;
            default: orientation = dng_orientation::Normal(); break;
        }
        negative->SetBaseOrientation(orientation);
        
        // Step 10: Create the raw image and copy data
        LOGD("Creating raw image: %dx%d (pitch=%d bytes)", 
             meta.raw_width, meta.raw_height, meta.raw_pitch);
        
        dng_rect imageBounds(meta.raw_height, meta.raw_width);
        AutoPtr<dng_image> rawImage(
            host.Make_dng_image(imageBounds, 1, ttShort)
        );
        
        // Copy raw data row by row to handle potential row padding
        // LibRaw's raw_image is stored as a flat array with possible row padding
        // The stride (pitch) may be larger than raw_width * sizeof(uint16)
        
        int srcRowStride = meta.raw_width;  // LibRaw raw_image is uint16_t*, stride is in pixels
        if (meta.raw_pitch > 0 && meta.raw_pitch != meta.raw_width * 2) {
            // raw_pitch is in bytes, convert to pixel stride
            srcRowStride = meta.raw_pitch / 2;
            LOGD("Using source row stride: %d pixels (from pitch %d bytes)", 
                 srcRowStride, meta.raw_pitch);
        }
        
        // Allocate contiguous buffer for DNG
        size_t dngBufferSize = (size_t)meta.raw_width * meta.raw_height;
        std::vector<uint16_t> imageData(dngBufferSize);
        
        // Copy row by row
        for (int row = 0; row < meta.raw_height; row++) {
            const uint16_t* srcRow = rawData + (row * srcRowStride);
            uint16_t* dstRow = imageData.data() + (row * meta.raw_width);
            memcpy(dstRow, srcRow, meta.raw_width * sizeof(uint16_t));
        }
        
        // Debug: Log some sample pixel values to verify data integrity
        LOGD("Sample raw values at (0,0)-(3,3):");
        for (int r = 0; r < 4 && r < meta.raw_height; r++) {
            LOGD("  Row %d: %u %u %u %u", r,
                 imageData[r * meta.raw_width + 0],
                 imageData[r * meta.raw_width + 1],
                 imageData[r * meta.raw_width + 2],
                 imageData[r * meta.raw_width + 3]);
        }
        // Also check center of image
        int centerRow = meta.raw_height / 2;
        int centerCol = meta.raw_width / 2;
        LOGD("Sample raw values at center (%d,%d):", centerRow, centerCol);
        for (int r = 0; r < 4; r++) {
            int row = centerRow + r;
            LOGD("  Row %d: %u %u %u %u", row,
                 imageData[row * meta.raw_width + centerCol + 0],
                 imageData[row * meta.raw_width + centerCol + 1],
                 imageData[row * meta.raw_width + centerCol + 2],
                 imageData[row * meta.raw_width + centerCol + 3]);
        }
        
        LOGD("Copied %zu pixels of raw data", dngBufferSize);
        
        // Set up pixel buffer for DNG
        dng_pixel_buffer buffer;
        buffer.fArea = imageBounds;
        buffer.fPlane = 0;
        buffer.fPlanes = 1;
        buffer.fRowStep = meta.raw_width;  // DNG buffer row stride
        buffer.fColStep = 1;
        buffer.fPlaneStep = dngBufferSize;
        buffer.fPixelType = ttShort;
        buffer.fPixelSize = 2;
        buffer.fData = imageData.data();
        
        rawImage->Put(buffer);
        
        // Step 11: Set the stage 1 image
        negative->SetStage1Image(rawImage);
        
        // Step 12: Configure host to save raw DNG and build stage images
        // Setting SaveDNGVersion tells the SDK to preserve the raw mosaic data
        host.SetSaveDNGVersion(dngVersion_SaveDefault);
        host.SetSaveLinearDNG(false);  // We want raw mosaic, not linear
        host.SetKeepOriginalFile(false);
        
        negative->BuildStage2Image(host);
        negative->BuildStage3Image(host);
        
        LOGD("Built stage 2/3 images");
        
        // Step 13: Synchronize metadata
        negative->SynchronizeMetadata();
        
        // Step 14: Write the DNG file
        LOGD("Writing DNG to: %s", outputPath.c_str());
        
        dng_file_stream outputStream(outputPath.c_str(), true);
        
        dng_image_writer writer;
        writer.WriteDNG(host, outputStream, *negative.Get());
        
        LOGD("DNG conversion successful!");
        return true;
        
    } catch (const dng_exception& e) {
        errorMessage = "DNG SDK error: " + std::to_string(e.ErrorCode());
        LOGE("DNG exception: %d", e.ErrorCode());
        return false;
    } catch (const std::exception& e) {
        errorMessage = std::string("Error: ") + e.what();
        LOGE("Exception: %s", e.what());
        return false;
    } catch (...) {
        errorMessage = "Unknown error during DNG conversion";
        LOGE("Unknown exception");
        return false;
    }
}

} // namespace raw2dng
