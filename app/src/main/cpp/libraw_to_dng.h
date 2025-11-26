// libraw_to_dng.h
// Conversion bridge from LibRaw to DNG SDK

#ifndef LIBRAW_TO_DNG_H
#define LIBRAW_TO_DNG_H

#include <string>

namespace raw2dng {

// Convert a proprietary RAW file to DNG using LibRaw + DNG SDK
// Returns true on success, false on failure with error message
bool convertRawToDNG(const std::string& inputPath,
                     const std::string& outputPath,
                     std::string& errorMessage);

} // namespace raw2dng

#endif // LIBRAW_TO_DNG_H
