# Raw2DNG - Copilot Instructions

## Project Overview

Raw2DNG is an Android application that converts RAW camera files (CR2, ARW, NEF, ORF, RAF, RW2, DNG, PEF, SRW, etc.) to Adobe DNG format and/or JPEG. It uses LibRaw for RAW file processing and the Adobe DNG SDK for DNG file creation.

## Build Instructions

### Prerequisites
- Docker and Docker Compose installed
- Android device connected via ADB (for installation)
- `make` utility

### Building the APK

```bash
# Build debug APK using Docker
sudo make build

# Output location: output/app-debug.apk
```

The Docker build handles all dependencies including:
- Android SDK/NDK
- Adobe DNG SDK
- LibRaw library
- libjpeg for JPEG export

### Installing on Device

```bash
# Check if previous version needs to be removed first
adb shell pm list packages | grep raw2dng

# If installed, uninstall first (required if signing keys differ)
adb uninstall com.raw2dng

# Install the new APK
adb install output/app-debug.apk
```

**Important**: If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, you must uninstall the existing app first:
```bash
adb uninstall com.raw2dng && adb install output/app-debug.apk
```

## Application Architecture

### Overview

The app follows a single-Activity architecture with Fragments managed by a ViewPager2 with TabLayout. It uses Material 3 theming with dynamic colors.

```
MainActivity
├── RawFilePickerFragment (Tab: Convert)
│   ├── File selection with thumbnails
│   ├── Filter chips (All / Not DNG / Not JPEG)
│   ├── Fullscreen image preview
│   └── Conversion controls (DNG/JPEG/Both)
│
└── GalleryFragment (Tab: Gallery)
    ├── Grid view of converted images
    ├── Filter chips (DNG / JPEG / All)
    ├── Multi-select with long-press
    ├── Clear folder (filter-specific)
    └── Open in external app
```

### Package Structure

```
com.raw2dng/
├── MainActivity.kt           # Main activity with ViewPager2 + TabLayout
├── MainPagerAdapter.kt       # ViewPager2 adapter for fragments
│
├── RawFilePickerFragment.kt  # RAW file browser and conversion UI
├── RawFileAdapter.kt         # RecyclerView adapter for RAW files
├── RawFileItem.kt            # Data class for RAW file metadata
│
├── GalleryFragment.kt        # Gallery of converted images
├── GalleryAdapter.kt         # RecyclerView adapter with multi-select
├── GalleryItem.kt            # Data class for gallery items
│
├── ConversionQueue.kt        # Manages parallel conversion jobs
├── ConvertedFilesHelper.kt   # Checks conversion status by file existence
├── OutputFormat.kt           # Enum: DNG, JPEG
│
├── DNGConverter.kt           # JNI bridge to native code
└── FullscreenImageActivity.kt # Fullscreen RAW preview
```

### Native Code (C++)

Located in `app/src/main/cpp/`:

```
cpp/
├── CMakeLists.txt            # CMake build configuration
├── native-lib.cpp            # JNI entry points
├── dng_converter.cpp/h       # Main DNG conversion orchestration
├── libraw_to_dng.cpp/h       # LibRaw → DNG SDK conversion
├── libraw_reader.cpp/h       # LibRaw wrapper for reading RAW files
├── jpeg_converter.cpp/h      # JPEG export using LibRaw + libjpeg
├── raw_metadata.h            # Metadata structure shared across modules
│
├── libraw/                   # LibRaw library source
├── dng_sdk/                  # Adobe DNG SDK source
└── libjpeg/                  # libjpeg-turbo source
```

### Key Data Flows

#### RAW File Discovery
```
RawFilePickerFragment.loadRawFiles()
  → MediaStore query for image/* files
  → Filter by RAW extensions (CR2, ARW, NEF, ORF, etc.)
  → ConvertedFilesHelper.isConvertedToDng/Jpeg()
  → RawFileItem list with conversion status
```

#### Conversion Flow
```
RawFilePickerFragment.startConversion()
  → ConversionQueue.addTask(uri, format)
  → DNGConverter.convertToDNG() [JNI]
  → native dng_converter.cpp
    → libraw_reader reads RAW data
    → libraw_to_dng creates DNG (if format includes DNG)
    → jpeg_converter creates JPEG (if format includes JPEG)
  → File written to Pictures/Raw2DNG/ or Pictures/Raw2DNG/JPEG/
  → MediaScanner notified
```

#### Gallery Refresh
```
GalleryFragment.loadImages()
  → Scan Pictures/Raw2DNG/*.dng
  → Scan Pictures/Raw2DNG/JPEG/*.jpg
  → Apply current filter (DNG_ONLY, JPEG_ONLY, ALL)
  → Update RecyclerView
```

### Conversion Status Sync

When files are deleted from the Gallery, the Convert tab needs to update its badges:

```
GalleryFragment.clearRaw2DNGFolder()
  → Delete files based on current filter
  → MediaScanner notified

RawFilePickerFragment.onResume()
  → refreshConversionStatus()
  → Re-check ConvertedFilesHelper for each cached RawFileItem
  → Update mutable isConvertedToDng/isConvertedToJpeg
  → Refresh UI
```

### Output Directories

```
/storage/emulated/0/Pictures/Raw2DNG/
├── *.dng                     # Converted DNG files
└── JPEG/
    └── *.jpg                 # Converted JPEG files
```

### Key Features

1. **Thumbnail Loading**: Uses LibRaw's embedded thumbnail extraction via JNI
2. **Fullscreen Preview**: Extracts full-resolution preview from RAW files
3. **Per-Channel Black Levels**: Uses DNG SDK's SetQuadBlacks() for accurate dark area rendering
4. **Camera Color Matrices**: Applies camera-specific color matrices for accurate colors
5. **Filter-Specific Clear**: Gallery clear button respects current filter (DNG/JPEG/All)
6. **Multi-Select**: Long-press enables multi-select for batch operations
7. **Parallel Conversion**: ConversionQueue processes files concurrently

### Color Matrix Handling

The app includes hardcoded color matrices for cameras not fully supported by LibRaw:
- Olympus OM-1 (ORF files)
- More can be added in `libraw_to_dng.cpp`

### Important Implementation Details

1. **RawFileItem.isConvertedToDng/isConvertedToJpeg** are `var` (mutable) to allow refresh
2. **ConvertedFilesHelper** checks file existence on-demand (no caching)
3. **GalleryAdapter** supports both click (open) and long-press (multi-select)
4. **Android 11+ (R)** uses `MediaStore.createDeleteRequest()` for file deletion
5. **JPEG output** uses LibRaw's dcraw processing with libjpeg encoding

### Testing Checklist

When making changes, verify:
- [ ] RAW file thumbnails load correctly
- [ ] Fullscreen preview works
- [ ] DNG conversion produces valid files
- [ ] JPEG conversion produces valid files
- [ ] Gallery shows correct files per filter
- [ ] Clear folder respects current filter
- [ ] Conversion badges update after gallery changes
- [ ] Multi-select works in gallery
- [ ] "Open in..." works for selected files

### Common Issues

1. **Purple cast in dark images**: Check per-channel black level handling in `libraw_to_dng.cpp`
2. **Wrong colors**: Check color matrix application and camera model detection
3. **Badges not updating**: Ensure `refreshConversionStatus()` is called in `onResume()`
4. **Install fails**: Run `adb uninstall com.raw2dng` first if signing keys changed
