/*****************************************************************************/
// JXL Stub Header - Provides minimal type definitions when qDNGUseJXL=0
/*****************************************************************************/

#ifndef __jxl_stub__
#define __jxl_stub__

#include "dng_types.h"
#include "dng_auto_ptr.h"

#if !qDNGUseJXL

// Stub JXL types for compilation when JXL support is disabled

struct JxlColorEncoding
	{
	int dummy;
	};

// Forward declarations for DNG SDK JXL classes
class dng_jxl_encode_settings
	{
	public:
		real32 Distance() const { return 0.0f; }
		int32 Effort() const { return 0; }
		int32 DecodeSpeed() const { return 0; }
		
		void SetDistance(real32) {}
		void SetEffort(int32) {}
		void SetDecodeSpeed(int32) {}
		void SetUseOriginalColorEncoding(bool) {}
		void SetSingleThread(bool) {}
	};

class dng_jxl_color_space_info
	{
	public:
		AutoPtr<JxlColorEncoding> fJxlColorEncoding;
	};

class dng_host;
class dng_stream;
class dng_image;

class dng_jxl_decoder
	{
	public:
		dng_jxl_decoder() {}
		~dng_jxl_decoder() {}
	};

// Stub function declarations
inline bool ParseJXL(dng_host &, dng_stream &, dng_image * &, dng_jxl_decoder * = nullptr) { return false; }

#endif  // !qDNGUseJXL

#endif  // __jxl_stub__
