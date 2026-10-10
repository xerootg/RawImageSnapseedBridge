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

// Convert a jstring to real UTF-8. GetStringUTFChars() yields *modified* UTF-8
// (CESU-8 surrogate pairs for supplementary characters, C0 80 for NUL), which
// does not match the bytes of file names on disk and is rejected by the Rust
// side; so decode the UTF-16 code units ourselves.
std::string jstringToUtf8(JNIEnv* env, jstring str) {
    std::string out;
    if (!str) return out;
    const jsize len = env->GetStringLength(str);
    const jchar* chars = env->GetStringChars(str, nullptr);
    if (!chars) {
        // OOM: an exception is pending; the caller checks for it.
        return out;
    }
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;  // lone surrogate
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(str, chars);
    return out;
}

// Build a Java String from UTF-8. NewStringUTF expects modified UTF-8, so go
// through the byte[] + String(byte[], "UTF-8") constructor instead; returns
// nullptr with a pending exception on OOM.
jstring toJString(JNIEnv* env, const std::string& s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) { env->DeleteLocalRef(bytes); return nullptr; }
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    if (!ctor) { env->DeleteLocalRef(bytes); env->DeleteLocalRef(stringClass); return nullptr; }
    jstring charset = env->NewStringUTF("UTF-8");
    if (!charset) { env->DeleteLocalRef(bytes); env->DeleteLocalRef(stringClass); return nullptr; }
    jstring result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

// Basename for logs: full paths are the user's photo library layout.
std::string baseName(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

// Two required path arguments; false (with a pending Java exception or an
// error already prepared) when either is unusable.
bool readPaths(JNIEnv* env, jstring inputPath, jstring outputPath, std::string& in, std::string& out) {
    in = jstringToUtf8(env, inputPath);
    if (env->ExceptionCheck()) return false;
    out = jstringToUtf8(env, outputPath);
    if (env->ExceptionCheck()) return false;
    return true;
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
    std::string in, out;
    if (!readPaths(env, inputPath, outputPath, in, out)) return nullptr;
    LOGD("convertToDNG %s -> %s", baseName(in).c_str(), baseName(out).c_str());

    raw2dng::DngConversionSettings settings;
    settings.embedOriginalRaw = embedOriginal == JNI_TRUE;
    settings.preview = preview == JNI_TRUE;
    settings.thumbnail = thumbnail == JNI_TRUE;
    settings.lossless = lossless == JNI_TRUE;

    std::string errorMessage;
    bool success = raw2dng::backend().convertToDNG(in, out, settings, errorMessage);
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
    std::string in, out;
    if (!readPaths(env, inputPath, outputPath, in, out)) return nullptr;
    LOGD("convertToJPEG %s -> %s (quality=%d, subsampling=%d, optimize=%d, embeddedPolicy=%d)",
         baseName(in).c_str(), baseName(out).c_str(), quality, chromaSubsampling, optimizeCoding, embeddedPolicy);

    raw2dng::JpegEncodingSettings settings;
    settings.quality = quality;
    settings.subsampling = static_cast<raw2dng::ChromaSubsampling>(chromaSubsampling);
    settings.optimizeCoding = optimizeCoding == JNI_TRUE;
    settings.embeddedPolicy = static_cast<raw2dng::EmbeddedJpegPolicy>(embeddedPolicy);
    settings.embedExif = embedExif == JNI_TRUE;

    raw2dng::JpegResult result;
    std::string errorMessage;
    bool success = raw2dng::backend().convertToJPEG(in, out, settings, result, errorMessage);
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
    std::string in, out;
    if (!readPaths(env, inputPath, outputPath, in, out)) return nullptr;
    LOGD("extractPreview %s -> %s (maxDimension=%d)", baseName(in).c_str(), baseName(out).c_str(), maxDimension);

    if (maxDimension < 0) {
        return toJString(env, errorResult("maxDimension must not be negative"));
    }

    raw2dng::PreviewResult result;
    std::string errorMessage;
    bool success = raw2dng::backend().extractPreview(in, out, maxDimension, result, errorMessage);
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
    std::string in = jstringToUtf8(env, inputPath);
    if (env->ExceptionCheck()) return nullptr;
    LOGD("extractMetadata %s", baseName(in).c_str());

    std::string json, errorMessage;
    bool success = raw2dng::backend().extractMetadataJson(in, json, errorMessage);
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
