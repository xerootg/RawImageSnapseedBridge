cmake_minimum_required(VERSION 3.22.1)

project("raw2dng")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions -Wno-deprecated-declarations")

# Include directories
include_directories(${CMAKE_SOURCE_DIR}/dng_sdk)
include_directories(${CMAKE_SOURCE_DIR}/jpeglib)  # For libjpeg headers
include_directories(${CMAKE_SOURCE_DIR})  # For JXL stub headers
include_directories(${CMAKE_SOURCE_DIR}/LibRaw)  # LibRaw headers
include_directories(${CMAKE_SOURCE_DIR}/LibRaw/libraw)  # LibRaw internal headers
include_directories(${ANDROID_NDK}/sources/android/support/include)

# Collect all DNG SDK source files
file(GLOB DNG_SDK_SOURCES
    "${CMAKE_SOURCE_DIR}/dng_sdk/dng_*.cpp"
)

# Exclude JXL-related files since we have qDNGUseJXL=0
list(FILTER DNG_SDK_SOURCES EXCLUDE REGEX "dng_jxl\\.cpp$")

# Exclude XMP SDK implementation since we have qDNGUseXMP=0
list(FILTER DNG_SDK_SOURCES EXCLUDE REGEX "dng_xmp_sdk\\.cpp$")

# Collect LibRaw source files
file(GLOB_RECURSE LIBRAW_SOURCES
    "${CMAKE_SOURCE_DIR}/LibRaw/src/*.cpp"
)

# Exclude x3f sources (Sigma X3F format - not needed and may have dependencies)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "x3f\\.cpp$")
# Exclude integration glue files that require external libraries
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "dngsdk_glue\\.cpp$")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "rawspeed_glue\\.cpp$")
# Exclude placeholder files (they define stub implementations that conflict with real ones)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

# If DNG SDK files are not present, create a dummy list to allow CMake configuration
if(NOT DNG_SDK_SOURCES)
    message(WARNING "Adobe DNG SDK source files not found in ${CMAKE_SOURCE_DIR}/dng_sdk/")
    message(WARNING "Please download and extract the Adobe DNG SDK before building.")
    message(WARNING "See app/src/main/cpp/dng_sdk/README.md for instructions.")
    # Create a minimal dummy source to allow project configuration
    set(DNG_SDK_SOURCES "")
endif()

# XMP SDK - using simplified stubs for now
# In production, you'd want to include the full XMP SDK from Adobe
add_library(xmp_stub STATIC
    xmp_stub.cpp
)

# libjpeg library
add_library(jpeg STATIC
    jpeglib/jcapimin.c
    jpeglib/jcapistd.c
    jpeglib/jdapimin.c
    jpeglib/jdapistd.c
    jpeglib/jcomapi.c
    jpeglib/jcparam.c
    jpeglib/jctrans.c
    jpeglib/jdtrans.c
    jpeglib/jcinit.c
    jpeglib/jcmaster.c
    jpeglib/jcmainct.c
    jpeglib/jcprepct.c
    jpeglib/jccoefct.c
    jpeglib/jccolor.c
    jpeglib/jcsample.c
    jpeglib/jcdctmgr.c
    jpeglib/jfdctint.c
    jpeglib/jfdctfst.c
    jpeglib/jfdctflt.c
    jpeglib/jchuff.c
    jpeglib/jcarith.c
    jpeglib/jcmarker.c
    jpeglib/jdatadst.c
    jpeglib/jdmaster.c
    jpeglib/jdinput.c
    jpeglib/jdmainct.c
    jpeglib/jdcoefct.c
    jpeglib/jdpostct.c
    jpeglib/jdmarker.c
    jpeglib/jdhuff.c
    jpeglib/jdarith.c
    jpeglib/jddctmgr.c
    jpeglib/jidctint.c
    jpeglib/jidctfst.c
    jpeglib/jidctflt.c
    jpeglib/jdsample.c
    jpeglib/jdcolor.c
    jpeglib/jdmerge.c
    jpeglib/jquant1.c
    jpeglib/jquant2.c
    jpeglib/jdatasrc.c
    jpeglib/jaricom.c
    jpeglib/jerror.c
    jpeglib/jmemmgr.c
    jpeglib/jutils.c
    jpeglib/jmemansi.c
)

# DNG SDK library
if(DNG_SDK_SOURCES)
    add_library(dng_sdk STATIC
        ${DNG_SDK_SOURCES}
    )
    target_link_libraries(dng_sdk
        jpeg
        xmp_stub
        z
        log
    )
    target_compile_definitions(dng_sdk PRIVATE
        qDNGUseLibJPEG=1
        qDNGUseXMP=0
        qDNGUseJXL=0
        qDNGThreadSafe=1
        qDNGValidateTarget=1
        qAndroid=1
    )
endif()

# LibRaw library
if(LIBRAW_SOURCES)
    add_library(raw STATIC
        ${LIBRAW_SOURCES}
    )
    target_compile_definitions(raw PRIVATE
        LIBRAW_NOTHREADS=1
        NO_JASPER=1
        NO_LCMS=1
    )
    target_include_directories(raw PRIVATE
        ${CMAKE_SOURCE_DIR}/LibRaw
        ${CMAKE_SOURCE_DIR}/LibRaw/libraw
    )
    target_link_libraries(raw
        z
        log
    )
endif()

# Main JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
    raw2dng_jni.cpp
    dng_converter.cpp
    libraw_reader.cpp
    libraw_to_dng.cpp
)

# Link libraries
if(DNG_SDK_SOURCES)
    target_link_libraries(${CMAKE_PROJECT_NAME}
        dng_sdk
        raw
        jpeg
        xmp_stub
        android
        log
        z
        jnigraphics
    )
else()
    target_link_libraries(${CMAKE_PROJECT_NAME}
        raw
        android
        log
        z
    )
endif()

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    qDNGUseLibJPEG=1
    qDNGUseXMP=0
    qDNGUseJXL=0
    qDNGThreadSafe=1
    qDNGValidateTarget=1
    qAndroid=1
)
