cmake_minimum_required(VERSION 3.22.1)

project("raw2dng")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions")

# ---------------------------------------------------------------------------
# dnglab-ffi (Rust) - the RAW backend
#
# The dnglab checkout defaults to the git submodule at app/src/main/cpp/dnglab.
# Override with -DDNGLAB_SOURCE_DIR=<path> (see app/build.gradle: the Gradle
# property `dnglabSourceDir` is forwarded) to build against a local checkout.
# ---------------------------------------------------------------------------
set(DNGLAB_SOURCE_DIR "${CMAKE_SOURCE_DIR}/dnglab" CACHE PATH "Path to the dnglab repository")
set(DNGLAB_FFI_DIR "${DNGLAB_SOURCE_DIR}/dnglab-ffi")
if(NOT EXISTS "${DNGLAB_FFI_DIR}/Cargo.toml")
    message(FATAL_ERROR
        "dnglab-ffi not found at ${DNGLAB_FFI_DIR}. "
        "Run 'git submodule update --init --recursive' or pass -DDNGLAB_SOURCE_DIR=<path to dnglab checkout>.")
endif()

# cargo: honour $CARGO (full path to the binary), then $CARGO_HOME/bin, PATH and the usual rustup location.
if(DEFINED ENV{CARGO} AND EXISTS "$ENV{CARGO}")
    set(CARGO_EXECUTABLE "$ENV{CARGO}" CACHE FILEPATH "cargo executable used to build dnglab-ffi")
else()
    find_program(CARGO_EXECUTABLE
        NAMES cargo
        HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" "/root/.cargo/bin" "/opt/rust/cargo/bin" "/usr/local/cargo/bin"
        DOC "cargo executable used to build dnglab-ffi")
endif()
if(NOT CARGO_EXECUTABLE)
    message(FATAL_ERROR "cargo not found. Install Rust (https://rustup.rs) and the Android targets: "
        "rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android")
endif()

# Rust debug builds are far too slow for image processing; always use the
# optimized "android" profile defined in the dnglab workspace Cargo.toml.
set(DNGLAB_CARGO_PROFILE "android")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DNGLAB_RUST_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DNGLAB_RUST_TRIPLE "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
    set(DNGLAB_RUST_TRIPLE "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(DNGLAB_RUST_TRIPLE "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for dnglab-ffi: ${ANDROID_ABI}")
endif()

# One cargo target directory shared by all ABIs (host build scripts are cached once).
set(DNGLAB_CARGO_TARGET_DIR "${CMAKE_SOURCE_DIR}/../../../build/cargo-target" CACHE PATH "cargo target directory for dnglab-ffi")
get_filename_component(DNGLAB_CARGO_TARGET_DIR "${DNGLAB_CARGO_TARGET_DIR}" ABSOLUTE)
set(DNGLAB_FFI_LIB "${DNGLAB_CARGO_TARGET_DIR}/${DNGLAB_RUST_TRIPLE}/${DNGLAB_CARGO_PROFILE}/libdnglab_ffi.a")

if(NOT DEFINED ANDROID_PLATFORM_LEVEL)
    set(ANDROID_PLATFORM_LEVEL 24)
endif()

add_custom_target(dnglab_ffi_cargo
    COMMAND ${CMAKE_COMMAND} -E env "CARGO=${CARGO_EXECUTABLE}"
        bash "${DNGLAB_FFI_DIR}/scripts/build-android.sh"
            --ndk "${ANDROID_NDK}"
            --abi "${ANDROID_ABI}"
            --api "${ANDROID_PLATFORM_LEVEL}"
            --profile "${DNGLAB_CARGO_PROFILE}"
            --target-dir "${DNGLAB_CARGO_TARGET_DIR}"
            --manifest "${DNGLAB_FFI_DIR}/Cargo.toml"
    BYPRODUCTS "${DNGLAB_FFI_LIB}"
    WORKING_DIRECTORY "${DNGLAB_FFI_DIR}"
    COMMENT "Building dnglab-ffi (Rust) for ${ANDROID_ABI}"
    VERBATIM
)

add_library(dnglab_ffi STATIC IMPORTED)
set_target_properties(dnglab_ffi PROPERTIES IMPORTED_LOCATION "${DNGLAB_FFI_LIB}")
add_dependencies(dnglab_ffi dnglab_ffi_cargo)

# ---------------------------------------------------------------------------
# JNI library
# ---------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
    raw2dng_jni.cpp
    dnglab_backend.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    "${DNGLAB_FFI_DIR}/include"
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    dnglab_ffi
    android
    log
    dl
    m
)

# Only export the JNI entry points: hide the thousands of Rust symbols pulled
# in from the static library and let the linker drop unreferenced sections.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
)
