// libraw_backend.h
// RawBackend implementation on top of LibRaw (decoding) and the Adobe DNG SDK
// (DNG authoring).

#ifndef RAW2DNG_LIBRAW_BACKEND_H
#define RAW2DNG_LIBRAW_BACKEND_H

#include "raw_backend.h"

namespace raw2dng {

class LibRawBackend final : public RawBackend {
public:
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

#endif // RAW2DNG_LIBRAW_BACKEND_H
