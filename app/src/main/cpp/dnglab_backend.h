// dnglab_backend.h
// RawBackend implementation on top of dnglab/rawler (via the dnglab-ffi C ABI).

#ifndef RAW2DNG_DNGLAB_BACKEND_H
#define RAW2DNG_DNGLAB_BACKEND_H

#include "raw_backend.h"

namespace raw2dng {

class DnglabBackend final : public RawBackend {
public:
    DnglabBackend();

    std::string name() const override;
    std::string version() const override;
    std::vector<std::string> supportedExtensions() const override;

    bool convertToDNG(const std::string& inputPath,
                      const std::string& outputPath,
                      const DngConversionSettings& settings,
                      std::string& errorMessage) override;

    bool convertToJPEG(const std::string& inputPath,
                       const std::string& outputPath,
                       const JpegEncodingSettings& settings,
                       JpegResult& result,
                       std::string& errorMessage) override;

    bool extractPreview(const std::string& inputPath,
                        const std::string& outputPath,
                        int maxDimension,
                        PreviewResult& result,
                        std::string& errorMessage) override;

    bool extractMetadataJson(const std::string& inputPath,
                             std::string& jsonOutput,
                             std::string& errorMessage) override;
};

} // namespace raw2dng

#endif // RAW2DNG_DNGLAB_BACKEND_H
