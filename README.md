# Raw2DNG - Android RAW to DNG/JPEG Converter

An Android application that converts proprietary RAW camera files (CR2, ARW, NEF, ORF, RAF, RW2, PEF, SRW, etc.) to Adobe DNG format and/or JPEG. It uses LibRaw for RAW file processing and the Adobe DNG SDK for DNG file creation.

## Features

### Core Conversion
- ✅ Convert RAW files to Adobe DNG format
- ✅ Convert RAW files to high-quality JPEG
- ✅ Parallel conversion with configurable thread count (1 to N CPU cores)
- ✅ Visual conversion progress with thumbnail grid and status overlays
- ✅ Overwrite confirmation for already-converted files
- ✅ Complete EXIF/metadata preservation in both DNG and JPEG output

### File Management
- ✅ Browse RAW files with thumbnail previews
- ✅ Filter chips: All / Not DNG / Not JPEG
- ✅ Hide or dim already-converted images
- ✅ Gallery view for converted files with DNG/JPEG/All filters
- ✅ Multi-select mode with batch operations
- ✅ Regenerate converted files from original RAW sources

### Preview & Metadata
- ✅ Fullscreen image preview with swipe navigation
- ✅ Thumbnail strip (gutter) with conversion status badges
- ✅ EXIF/metadata overlay with detailed camera, lens, exposure, and GPS info
- ✅ Zoom controls and file size display
- ✅ Double-tap to open fullscreen preview

### Integration
- ✅ Auto-open single DNG in Snapseed (optional)
- ✅ Share single JPEG via Android share sheet (optional)
- ✅ Auto-navigate to Gallery on conversion completion
- ✅ Open converted files in external apps

### User Experience
- ✅ Material 3 theming with dynamic colors
- ✅ Three-tab interface: Convert, Gallery, Settings
- ✅ Configurable JPEG quality, chroma subsampling, and Huffman optimization
- ✅ Hierarchical RAW format selection by manufacturer
- ✅ Screen rotation handling with state preservation
- ✅ Docker-based build system (no Android Studio required!)

## Quick Start with Docker 🐳

**Don't have Android Studio?** Use Docker to build the APK with zero setup!

```bash
# 1. Install Docker (if not already installed)
# See: https://docs.docker.com/get-docker/

# 2. Clone the repository with submodules
git clone --recursive <repo-url>
cd raw2dng2

# Or if already cloned, initialize submodules
git submodule update --init --recursive

# 3. (Optional) Add Adobe DNG SDK for real conversion
# Download from: https://helpx.adobe.com/camera-raw/digital-negative.html
# Then: cp /path/to/dng_sdk/source/*.{cpp,h} app/src/main/cpp/dng_sdk/

# 4. Build the APK (one command!)
sudo make build
# or: ./docker-build.sh

# 5. Your APK is ready!
ls output/app-debug.apk

# 6. Install on connected device
adb install output/app-debug.apk
```

**That's it!** No Android Studio, no SDK setup, just Docker and you're done.

For detailed Docker instructions, see **[DOCKER_BUILD.md](DOCKER_BUILD.md)**.

---

## Prerequisites

**Choose your build method:**

- **Option A: Docker Build** (Recommended - No Android tools needed!)
  - Only requires: Docker and Docker Compose
  - See: [DOCKER_BUILD.md](DOCKER_BUILD.md)

- **Option B: Android Studio Build** (For Android developers)
  - Requires: Android Studio, NDK, CMake
  - See instructions below

### Option B: Development Environment (Android Studio)

1. **Android Studio** (Arctic Fox or newer)
   - Download from: https://developer.android.com/studio

2. **Android NDK** (r21 or newer)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → NDK (Side by side)

3. **CMake** (3.22.1 or newer)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → CMake

### Adobe DNG SDK

**IMPORTANT**: You must download and install the Adobe DNG SDK before building this project.

#### Download Instructions:

1. Visit Adobe's DNG SDK page:
   - https://helpx.adobe.com/camera-raw/digital-negative.html
   - Or search for "Adobe DNG SDK download"

2. Download the latest version (1.6 or newer recommended)

3. Extract the downloaded ZIP file

4. Copy SDK files to the project:
   ```bash
   # Navigate to the extracted SDK directory
   cd /path/to/dng_sdk_x_x/

   # Copy all source files to the project
   cp dng_sdk/source/*.cpp /path/to/raw2dng2/app/src/main/cpp/dng_sdk/
   cp dng_sdk/source/*.h /path/to/raw2dng2/app/src/main/cpp/dng_sdk/
   ```

   Or use the provided helper script:
   ```bash
   # From the project root directory
   ./download_dng_sdk.sh
   # Follow the instructions, download the SDK, then:
   ./download_dng_sdk.sh --extract
   ```

5. Verify installation:
   ```bash
   ls app/src/main/cpp/dng_sdk/dng_*.cpp
   # Should show multiple DNG SDK source files
   ```

## Building the Project

### Option 1: Android Studio (Recommended)

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `raw2dng2` directory
4. Wait for Gradle sync to complete
5. Ensure the Adobe DNG SDK is installed (see above)
6. Click "Build" → "Make Project" or press `Ctrl+F9` (Windows/Linux) or `Cmd+F9` (Mac)
7. Connect an Android device or start an emulator
8. Click "Run" → "Run 'app'" or press `Shift+F10`

### Option 2: Command Line

```bash
# From project root directory
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or build and install in one command
./gradlew build installDebug
```

### Build Output

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk` (after `./gradlew assembleRelease`)

## Installation

### Install on Device

1. Enable "Developer Options" on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

2. Connect device via USB

3. Install the app:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Or use Android Studio's "Run" button.

## Usage

### Application Overview

The app uses a three-tab interface:

1. **Convert Tab** - Browse and convert RAW files
2. **Gallery Tab** - View and manage converted images
3. **Settings Tab** - Configure app preferences

### Converting RAW Files

1. **Launch the App**
   - Open "Raw2DNG" from your device's app drawer

2. **Browse RAW Files**
   - The Convert tab displays RAW files from your device with thumbnails
   - Use filter chips to show: All / Not DNG / Not JPEG
   - Already-converted files appear dimmed (or hidden, based on settings)

3. **Select Files**
   - Single-tap to toggle file selection
   - Double-tap to open fullscreen preview
   - Selected files show a checkmark overlay

4. **Convert Files**
   - Tap the DNG or JPEG button to start conversion
   - A progress overlay shows:
     - Thumbnail grid with per-file status (pending, in-progress, success, error)
     - Progress bar with X/Y count
     - Log view (toggle with Log button)
   - Previously converted files require tap to confirm overwrite
   - Conversion runs in parallel (configurable 1-N threads)

5. **After Conversion**
   - Auto-navigate to Gallery (optional, with countdown)
   - Or tap Done to dismiss the overlay
   - Single DNG can auto-open in Snapseed (if enabled)
   - Single JPEG can open share sheet (if enabled)

### Gallery Tab

- View converted DNG and JPEG files with thumbnails
- Filter by: DNG Only / JPEG Only / All
- Single-tap opens file in external app
- Double-tap opens fullscreen preview
- Long-press enables multi-select mode:
  - **Preview**: Open selected images in fullscreen viewer
  - **Regenerate**: Re-convert from original RAW files
  - **Open with**: Send to external app
- Clear folder button (respects current filter)
- File size displayed on thumbnails

### Fullscreen Preview

- Swipe left/right to navigate between images
- Thumbnail strip at bottom shows all images with:
  - D/J badges for conversion status (Convert tab)
  - Green checkmarks for selected items (Gallery)
- Tap info button to show EXIF/metadata overlay:
  - Camera, lens, exposure, shooting info
  - Dimensions, GPS, date/time
  - Advanced view shows raw JSON metadata
- Zoom controls available
- Toggle selection with checkbox button

### Settings Tab

- **Auto-Navigate Timeout**: 0-30 seconds countdown after conversion
- **Conversion Threads**: 1 to N CPU cores for parallel processing
- **Enabled RAW Types**: Select formats by manufacturer (Canon, Nikon, Sony, etc.)
- **Hide Already Converted**: Hide vs dim converted images in Convert tab
- **Open Single DNG in Snapseed**: Auto-open after single DNG conversion
- **Share Single JPEG**: Open share sheet after single JPEG conversion
- **JPEG Quality Settings**: Quality (1-100), chroma subsampling, Huffman optimization
- **View Open Source Licenses**: Attribution for LibRaw, DNG SDK, cJSON, libjpeg

### Output Directories

Converted files are saved to public storage:

```
/storage/emulated/0/Pictures/Raw2DNG/
├── *.dng                     # Converted DNG files
└── JPEG/
    └── *.jpg                 # Converted JPEG files
```

Access files via:
- Gallery app or any file manager
- ADB: `adb pull /storage/emulated/0/Pictures/Raw2DNG/`
- USB file transfer (MTP mode)

## Supported RAW Formats

The app uses LibRaw for RAW file reading, supporting formats organized by manufacturer:

| Manufacturer | Formats |
|-------------|---------|
| **Canon** | CR2, CR3, CRW |
| **Nikon** | NEF, NRW |
| **Sony** | ARW, SRF, SR2 |
| **Fujifilm** | RAF |
| **Olympus/OM System** | ORF |
| **Panasonic/Lumix** | RW2, RAW |
| **Pentax** | PEF |
| **Samsung** | SRW |
| **Hasselblad** | 3FR, FFF |
| **Phase One/Leaf** | IIQ |
| **Kodak** | DCR, KDC |
| **Epson** | ERF |
| **Mamiya** | MEF |

**Note**: You can enable/disable specific formats in Settings → Enabled RAW File Types.

## Known Limitations

1. **Scoped Storage**: Files created by previous app installations cannot be overwritten directly. Delete from Gallery first, then re-convert.
2. **DNG Thumbnail Orientation**: Android's `loadThumbnail()` doesn't apply EXIF orientation for DNG files; app manually rotates.
3. **Package Visibility**: AndroidManifest must declare Snapseed package for integration to work on Android 11+.
4. **Camera Color Matrices**: Some newer cameras may need hardcoded color matrices for accurate colors.

## Troubleshooting

### Build Errors

**"Adobe DNG SDK source files not found"**
- Download and install the Adobe DNG SDK as described in Prerequisites

**"NDK not configured"**
- Install Android NDK via Android Studio SDK Manager

**"CMake not found"**
- Install CMake via Android Studio SDK Manager

### Runtime Issues

**Install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE**
```bash
adb uninstall com.raw2dng && adb install output/app-debug.apk
```

**Purple cast in dark images**
- Check per-channel black level handling in `libraw_to_dng.cpp`

**Wrong colors on specific camera**
- May need camera-specific color matrix in `libraw_to_dng.cpp`

**Corrupt output during parallel conversion**
- Ensure `LIBRAW_NOTHREADS` is NOT defined in CMakeLists.txt

**File gets "(1)" suffix on overwrite**
- File was created by previous app installation; delete from Gallery first

**RAW files not appearing**
- Check Settings → Enabled RAW File Types

## License

This project uses multiple open-source components with various licenses:

- **LibRaw**: LGPL 2.1 / CDDL 1.0 dual license
- **Adobe DNG SDK**: Adobe license (must download separately)
- **cJSON**: MIT license
- **libjpeg**: Independent JPEG Group license
- **Android/Kotlin Libraries**: Apache 2.0

View full license details in the app via Settings → View Open Source Licenses.

**Important**: The Adobe DNG SDK has its own license agreement. You must comply with Adobe's licensing terms when using the DNG SDK.

## Resources

- [Adobe DNG SDK](https://helpx.adobe.com/camera-raw/digital-negative.html)
- [Adobe DNG Specification](https://helpx.adobe.com/photoshop/digital-negative.html)
- [LibRaw Documentation](https://www.libraw.org/docs)
- [Android NDK Documentation](https://developer.android.com/ndk)
- [JNI Guide](https://developer.android.com/training/articles/perf-jni)

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Review LibRaw and Adobe DNG SDK documentation
3. Check Android NDK documentation for native build issues

## Acknowledgments

- [raw2dng2] (https://github.com/fwibisono87/raw2dng2) the skeleton for this project
- [LibRaw](https://www.libraw.org/) for RAW file processing
- Adobe for the DNG SDK
- [cJSON](https://github.com/DaveGamble/cJSON) for JSON serialization
- Independent JPEG Group for libjpeg
- Android Open Source Project
- JetBrains for Kotlin
