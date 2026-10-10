// dnglab_backend.cpp
// RawBackend implementation on top of dnglab/rawler.
//
// The heavy lifting (decoding, demosaicing, DNG authoring, JPEG encoding,
// EXIF) lives in Rust (dnglab-ffi, see app/src/main/cpp/dnglab/dnglab-ffi).
// This file only adapts the C ABI to the RawBackend interface.

#include "dnglab_backend.h"

#include "dnglab_ffi.h"

#include <android/log.h>
#include <sstream>

#define LOG_TAG "DnglabBackend"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw2dng {

namespace {

// Takes ownership of a dnglab error string, converts it and frees it.
std::string takeError(char* err, const char* fallback) {
    if (!err) return fallback;
    std::string s(err);
    dnglab_string_free(err);
    return s.empty() ? std::string(fallback) : s;
}

const char* optionalCStr(const std::string& s) {
    return s.empty() ? nullptr : s.c_str();
}

constexpr const char* kSoftware = "Raw2DNG (dnglab)";

// Basename for logs: full paths describe the user's photo library.
std::string baseName(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

} // namespace

DnglabBackend::DnglabBackend() {
#ifdef NDEBUG
    dnglab_init_logging(DNGLAB_LOG_INFO);
#else
    dnglab_init_logging(DNGLAB_LOG_DEBUG);
#endif
    LOGD("dnglab backend %s initialised", dnglab_version());
}

std::string DnglabBackend::name() const {
    return "dnglab/rawler";
}

std::string DnglabBackend::version() const {
    const char* v = dnglab_version();
    return v ? v : "";
}

std::vector<std::string> DnglabBackend::supportedExtensions() const {
    std::vector<std::string> out;
    char* list = dnglab_supported_extensions();
    if (!list) return out;
    std::stringstream ss(list);
    std::string item;
    while (std::getline(ss, item, ',')) {
        if (!item.empty()) out.push_back(item);
    }
    dnglab_string_free(list);
    return out;
}

bool DnglabBackend::convertToDNG(const std::string& inputPath,
                                 const std::string& outputPath,
                                 const DngConversionSettings& settings,
                                 std::string& errorMessage) {
    dnglab_dng_options opts{};
    opts.embed_original = settings.embedOriginalRaw ? 1 : 0;
    opts.preview = settings.preview ? 1 : 0;
    opts.thumbnail = settings.thumbnail ? 1 : 0;
    opts.lossless = settings.lossless ? 1 : 0;
    opts.index = 0;
    opts.artist = optionalCStr(settings.artist);
    opts.dcp_dir = optionalCStr(settings.dcpDir);
    opts.dcp_file = optionalCStr(settings.dcpFile);
    opts.software = kSoftware;

    char* err = nullptr;
    int32_t rc = dnglab_convert_to_dng(inputPath.c_str(), outputPath.c_str(), &opts, &err);
    if (rc != DNGLAB_OK) {
        errorMessage = takeError(err, "DNG conversion failed");
        LOGE("convertToDNG(%s) failed: %s", baseName(inputPath).c_str(), errorMessage.c_str());
        return false;
    }
    return true;
}

bool DnglabBackend::convertToJPEG(const std::string& inputPath,
                                  const std::string& outputPath,
                                  const JpegEncodingSettings& settings,
                                  JpegResult& result,
                                  std::string& errorMessage) {
    dnglab_jpeg_options opts{};
    opts.quality = settings.quality;
    opts.chroma_subsampling = static_cast<int32_t>(settings.subsampling);
    opts.optimize_huffman = settings.optimizeCoding ? 1 : 0;
    opts.progressive = settings.progressive ? 1 : 0;
    opts.embedded_policy = static_cast<int32_t>(settings.embeddedPolicy);
    opts.full_resolution_threshold = settings.fullResolutionThreshold;
    opts.embed_exif = settings.embedExif ? 1 : 0;
    opts.auto_bright = 1;
    opts.software = kSoftware;

    dnglab_jpeg_result res{};
    char* err = nullptr;
    int32_t rc = dnglab_convert_to_jpeg(inputPath.c_str(), outputPath.c_str(), &opts, &res, &err);
    if (rc != DNGLAB_OK) {
        errorMessage = takeError(err, "JPEG conversion failed");
        LOGE("convertToJPEG(%s) failed: %s", baseName(inputPath).c_str(), errorMessage.c_str());
        return false;
    }
    result.usedEmbeddedJpeg = res.used_embedded != 0;
    result.width = static_cast<int>(res.width);
    result.height = static_cast<int>(res.height);
    result.orientation = res.orientation;
    result.exifEmbedded = res.exif_embedded != 0;
    LOGD("convertToJPEG(%s): %s %dx%d orientation=%d", baseName(inputPath).c_str(),
         result.usedEmbeddedJpeg ? "embedded camera JPEG" : "rendered", result.width, result.height,
         result.orientation);
    return true;
}

bool DnglabBackend::extractPreview(const std::string& inputPath,
                                   const std::string& outputPath,
                                   int maxDimension,
                                   PreviewResult& result,
                                   std::string& errorMessage) {
    dnglab_preview_result res{};
    char* err = nullptr;
    if (maxDimension < 0) {
        errorMessage = "maxDimension must not be negative";
        return false;
    }
    int32_t rc = dnglab_extract_preview(inputPath.c_str(), outputPath.c_str(),
                                        static_cast<uint32_t>(maxDimension), &res, &err);
    if (rc != DNGLAB_OK) {
        errorMessage = takeError(err, "Preview extraction failed");
        LOGE("extractPreview(%s) failed: %s", baseName(inputPath).c_str(), errorMessage.c_str());
        return false;
    }
    result.orientation = res.orientation;
    result.fromEmbeddedJpeg = res.from_embedded != 0;
    result.width = static_cast<int>(res.width);
    result.height = static_cast<int>(res.height);
    return true;
}

bool DnglabBackend::extractMetadataJson(const std::string& inputPath,
                                        std::string& jsonOutput,
                                        std::string& errorMessage) {
    char* err = nullptr;
    char* json = dnglab_metadata_json(inputPath.c_str(), &err);
    if (!json) {
        errorMessage = takeError(err, "Metadata extraction failed");
        LOGE("extractMetadataJson(%s) failed: %s", baseName(inputPath).c_str(), errorMessage.c_str());
        return false;
    }
    jsonOutput = json;
    dnglab_string_free(json);
    return true;
}

RawBackend& backend() {
    static DnglabBackend instance;
    return instance;
}

} // namespace raw2dng
