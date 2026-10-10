// libraw_backend.cpp
// RawBackend implementation on top of LibRaw + Adobe DNG SDK.

#include "libraw_backend.h"
#include "libraw_reader.h"
#include "dng_converter.h"

#include "libraw/libraw.h"

#include <android/log.h>

#define LOG_TAG "LibRawBackend"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace raw2dng {

std::string LibRawBackend::name() const {
    return "LibRaw + Adobe DNG SDK";
}

std::string LibRawBackend::version() const {
    return LibRaw::version();
}

std::vector<std::string> LibRawBackend::supportedExtensions() const {
    // LibRaw has no extension registry; this mirrors the formats the app offers.
    return {
        "3fr", "arw", "cr2", "cr3", "crw", "dcr", "dng", "erf", "fff", "iiq", "k25", "kdc",
        "mef", "mos", "mrw", "nef", "nrw", "orf", "ori", "pef", "raf", "raw", "rw2", "rwl",
        "sr2", "srf", "srw", "x3f",
    };
}

bool LibRawBackend::convertToDNG(const std::string& inputPath,
                                 const std::string& outputPath,
                                 const DngConversionSettings& settings,
                                 std::string& errorMessage) {
    // The LibRaw/DNG SDK path has fixed authoring settings; the DngConversionSettings
    // knobs are honoured by backends that author DNGs themselves.
    (void)settings;
    return DNGConverter::convertToDNG(inputPath, outputPath, errorMessage);
}

bool LibRawBackend::convertToJPEG(const std::string& inputPath,
                                  const std::string& outputPath,
                                  const JpegEncodingSettings& settings,
                                  JpegResult& result,
                                  std::string& errorMessage) {
    return LibRawReader::convertToJPEG(inputPath, outputPath, settings, result, errorMessage);
}

bool LibRawBackend::extractPreview(const std::string& inputPath,
                                   const std::string& outputPath,
                                   int maxDimension,
                                   PreviewResult& result,
                                   std::string& errorMessage) {
    // LibRaw hands out the embedded thumbnail as-is; downscaling is left to the caller.
    (void)maxDimension;
    int flip = 0;
    return LibRawReader::extractThumbnail(inputPath, outputPath, errorMessage, &flip, &result);
}

bool LibRawBackend::extractMetadataJson(const std::string& inputPath,
                                        std::string& jsonOutput,
                                        std::string& errorMessage) {
    return LibRawReader::extractMetadataJson(inputPath, jsonOutput, errorMessage);
}

RawBackend& backend() {
    static LibRawBackend instance;
    return instance;
}

} // namespace raw2dng
