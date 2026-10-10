// raw2dng_jni.cpp
// JNI bridge between com.raw2dng.DNGConverter and the RawBackend interface.
// This file knows nothing about the concrete RAW library in use.

#include <jni.h>
#include <string>
#include <android/log.h>

#include "raw_backend.h"

#define LOG_TAG "Raw2DNG_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// RAII wrapper for jstring -> std::string
class JString {
public:
    JString(JNIEnv* env, jstring str) : env_(env), str_(str), chars_(nullptr) {
        if (str_) chars_ = env_->GetStringUTFChars(str_, nullptr);
    }
    ~JString() {
        if (chars_) env_->ReleaseStringUTFChars(str_, chars_);
    }
    std::string value() const { return chars_ ? std::string(chars_) : std::string(); }
    const char* c_str() const { return chars_ ? chars_ : ""; }
private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;
};

jstring toJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

// Success results are prefixed with "ok" so the Kotlin side can distinguish them
// from error messages without any ambiguity.
std::string errorResult(const std::string& message) {
    if (message.rfind("ok:", 0) == 0 || message == "ok") {
        return "error: " + message;
    }
    return message.empty() ? std::string("Unknown error") : message;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeConvertToDNG(JNIEnv* env, jobject /* this */,
                                                 jstring inputPath, jstring outputPath,
                                                 jboolean embedOriginal, jboolean preview,
                                                 jboolean thumbnail, jboolean lossless) {
    JString in(env, inputPath), out(env, outputPath);
    LOGD("convertToDNG %s -> %s", in.c_str(), out.c_str());

    raw2dng::DngConversionSettings settings;
    settings.embedOriginalRaw = embedOriginal == JNI_TRUE;
    settings.preview = preview == JNI_TRUE;
    settings.thumbnail = thumbnail == JNI_TRUE;
    settings.lossless = lossless == JNI_TRUE;

    std::string errorMessage;
    bool success = raw2dng::backend().convertToDNG(in.value(), out.value(), settings, errorMessage);
    if (success) {
        return toJString(env, "ok");
    }
    LOGE("convertToDNG failed: %s", errorMessage.c_str());
    return toJString(env, errorResult(errorMessage));
}

// Returns "ok:<usedEmbedded 0|1>:<exifOrientation>:<width>x<height>:<exifEmbedded 0|1>" or an error message.
JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeConvertToJPEG(JNIEnv* env, jobject /* this */,
                                                  jstring inputPath, jstring outputPath,
                                                  jint quality, jint chromaSubsampling,
                                                  jboolean optimizeCoding, jint embeddedPolicy,
                                                  jboolean embedExif) {
    JString in(env, inputPath), out(env, outputPath);
    LOGD("convertToJPEG %s -> %s (quality=%d, subsampling=%d, optimize=%d, embeddedPolicy=%d)",
         in.c_str(), out.c_str(), quality, chromaSubsampling, optimizeCoding, embeddedPolicy);

    raw2dng::JpegEncodingSettings settings;
    settings.quality = quality;
    settings.subsampling = static_cast<raw2dng::ChromaSubsampling>(chromaSubsampling);
    settings.optimizeCoding = optimizeCoding == JNI_TRUE;
    settings.embeddedPolicy = static_cast<raw2dng::EmbeddedJpegPolicy>(embeddedPolicy);
    settings.embedExif = embedExif == JNI_TRUE;

    raw2dng::JpegResult result;
    std::string errorMessage;
    bool success = raw2dng::backend().convertToJPEG(in.value(), out.value(), settings, result, errorMessage);
    if (success) {
        char buf[96];
        snprintf(buf, sizeof(buf), "ok:%d:%d:%dx%d:%d", result.usedEmbeddedJpeg ? 1 : 0,
                 result.orientation, result.width, result.height, result.exifEmbedded ? 1 : 0);
        return toJString(env, buf);
    }
    LOGE("convertToJPEG failed: %s", errorMessage.c_str());
    return toJString(env, errorResult(errorMessage));
}

// Returns "ok:<exifOrientation>:<fromEmbedded 0|1>:<width>x<height>" or an error message.
JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeExtractPreview(JNIEnv* env, jobject /* this */,
                                                   jstring inputPath, jstring outputPath,
                                                   jint maxDimension) {
    JString in(env, inputPath), out(env, outputPath);
    LOGD("extractPreview %s -> %s (maxDimension=%d)", in.c_str(), out.c_str(), maxDimension);

    raw2dng::PreviewResult result;
    std::string errorMessage;
    bool success = raw2dng::backend().extractPreview(in.value(), out.value(), maxDimension, result, errorMessage);
    if (success) {
        char buf[96];
        snprintf(buf, sizeof(buf), "ok:%d:%d:%dx%d", result.orientation,
                 result.fromEmbeddedJpeg ? 1 : 0, result.width, result.height);
        return toJString(env, buf);
    }
    LOGE("extractPreview failed: %s", errorMessage.c_str());
    return toJString(env, errorResult(errorMessage));
}

// Returns a JSON document, or {"error": "..."} on failure.
JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeExtractMetadata(JNIEnv* env, jobject /* this */, jstring inputPath) {
    JString in(env, inputPath);
    LOGD("extractMetadata %s", in.c_str());

    std::string json, errorMessage;
    bool success = raw2dng::backend().extractMetadataJson(in.value(), json, errorMessage);
    if (success) {
        return toJString(env, json);
    }
    LOGE("extractMetadata failed: %s", errorMessage.c_str());
    // Minimal JSON escaping for the error text
    std::string escaped;
    for (char c : errorMessage) {
        switch (c) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': break;
            case '\t': escaped += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) break;
                escaped += c;
        }
    }
    return toJString(env, "{\"error\":\"" + escaped + "\"}");
}

JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeBackendName(JNIEnv* env, jobject /* this */) {
    return toJString(env, raw2dng::backend().name());
}

JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeBackendVersion(JNIEnv* env, jobject /* this */) {
    return toJString(env, raw2dng::backend().version());
}

// Comma separated, lower-case extensions without dot.
JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_nativeSupportedExtensions(JNIEnv* env, jobject /* this */) {
    std::string joined;
    for (const auto& ext : raw2dng::backend().supportedExtensions()) {
        if (!joined.empty()) joined += ',';
        joined += ext;
    }
    return toJString(env, joined);
}

} // extern "C"
