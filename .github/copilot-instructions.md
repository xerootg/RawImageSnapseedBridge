# Raw2DNG - Copilot Instructions

## Requirements for all changes made
Update this file to reflect any architectural or workflow changes made in the codebase. Ensure that all new features, classes, methods, and workflows are thoroughly documented here. Include diagrams or flowcharts if they help clarify complex processes. Maintain a clear and organized structure for easy navigation.

Ensure all licenses are properly attributed and included in the documentation as well as the Licenses dialog in the app.

## Tools
1. **Mandatory** Using Sequential Thinking MCP if it is installed and available in your environment.

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
- LibRaw library (git submodule)
- cJSON library (git submodule)
- libjpeg for JPEG export

### Cloning the Repository

```bash
# Clone with submodules
git clone --recursive https://github.com/fwibisono87/raw2dng2.git

# Or if already cloned, initialize submodules
git submodule update --init --recursive
```

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
│   ├── Single-tap to toggle selection
│   ├── Double-tap to open fullscreen preview
│   ├── Conversion controls (DNG/JPEG)
│   └── Conversion overlay with:
│       ├── Thumbnail grid / Log toggle
│       ├── Auto-navigate checkbox (persisted)
│       ├── Progress bar
│       └── Done button with countdown
│
├── GalleryFragment (Tab: Gallery)
│   ├── Grid view of converted images
│   ├── Filter chips (DNG / JPEG / All)
│   ├── Single-tap opens in external app
│   ├── Double-tap opens fullscreen preview
│   ├── Long-press for multi-select mode
│   ├── Preview button (multi-select mode)
│   ├── Regenerate button (multi-select mode)
│   ├── Clear folder (filter-specific)
│   └── Open in external app
│
└── SettingsFragment (Tab: Settings)
    ├── Auto-navigate timeout slider (0-30s)
    ├── RAW file types selection
    ├── Hide/dim converted images checkbox
    ├── Open single DNG in Snapseed checkbox
    ├── JPEG quality settings button
    └── Open source licenses button

ImagePreviewDialog (shared)
├── ViewPager2 for image swiping
├── Thumbnail strip (gutter) with:
│   ├── RAW mode: D/J conversion badges
│   └── Gallery mode: Green selection checkmarks
├── Selection toggle button
├── Zoom controls with info button
├── EXIF/metadata overlay (toggle via info button)
├── File size display
├── RAW mode: Convert buttons (JPEG/DNG)
└── Gallery mode: Open with button
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
├── ConversionQueue.kt        # Manages parallel conversion jobs with configurable parallelism
├── ConversionThumbnailAdapter.kt # Adapter for conversion progress thumbnails
├── ConvertedFilesHelper.kt   # Checks conversion status by file existence
├── OutputFormat.kt           # Enum: DNG, JPEG
├── PreviewMode.kt            # Enum: RAW_CONVERSION, GALLERY_VIEW (in ImagePreviewDialog.kt)
│
├── ThumbnailCache.kt         # LRU cache for RAW thumbnails extracted via LibRaw
├── ThumbnailStripAdapter.kt  # Horizontal thumbnail strip in preview dialog
├── ThumbnailStripItem.kt     # Data class with uri and conversion status
├── DNGConverter.kt           # JNI bridge to native code
├── ImagePreviewDialog.kt     # Fullscreen preview dialog with selection
├── ImagePagerAdapter.kt      # ViewPager2 adapter for preview images
├── SettingsFragment.kt       # Settings tab with all app preferences (auto-save)
├── RawTypesDialogFragment.kt # Hierarchical RAW format selection dialog
├── JpegSettingsDialog.kt     # JPEG conversion settings (quality, chroma, optimize)
├── LicensesDialog.kt         # Open source licenses display
├── RegenerateDialog.kt       # Regeneration settings dialog (one-time settings)
├── ExifData.kt               # EXIF/metadata extraction via LibRaw JNI (unified for RAW/DNG/JPEG)
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
├── raw2dng_jni.cpp           # JNI entry points (extractMetadataJson, etc.)
│
├── LibRaw/                   # LibRaw library (git submodule)
├── cJSON/                    # cJSON library for JSON serialization (git submodule)
├── dng_sdk/                  # Adobe DNG SDK source
└── jpeglib/                  # libjpeg source
```

#### Git Submodules

The project uses git submodules for external dependencies:

```bash
# LibRaw - RAW file processing library
app/src/main/cpp/LibRaw -> https://github.com/LibRaw/LibRaw

# cJSON - Lightweight JSON parser/generator
app/src/main/cpp/cJSON -> https://github.com/DaveGamble/cJSON
```

To initialize submodules after cloning:
```bash
git submodule update --init --recursive
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
  → Partition selected files into:
    - readyToConvert: Files not yet converted to target format
    - needsConfirmation: Files already converted (require user tap)
  → Create ConversionThumbnailItem list:
    - readyToConvert items start as PENDING
    - needsConfirmation items start as NEEDS_CONFIRMATION
  → Show conversion overlay with thumbnail grid
  → Set up onOverwriteConfirmed callback for dynamic task creation
  → For each file, generate unique ID (UUID) for cache isolation
  → Copy files to cache with unique names (e.g., DSC_0001_a1b2c3d4.NEF)
  → ConversionQueue.addTasks() for readyToConvert items only
  → ConversionQueue.start() begins parallel processing (default: 2 workers)
  → Workers pull tasks from Channel and process in parallel:
    → Semaphore controls maximum concurrent conversions
    → onTaskStarting callback marks item as IN_PROGRESS
    → DNGConverter.convertToDNG() [JNI] - thread-safe with TLS
    → native dng_converter.cpp
      → libraw_reader reads RAW data (per-thread LibRaw instance)
      → libraw_to_dng creates DNG (if format includes DNG)
      → jpeg_converter creates JPEG (if format includes JPEG)
    → onTaskComplete callback:
      → Mark item SUCCESS or ERROR in thumbnail grid
      → Increment global progress bar (AtomicInteger counters)
      → Save to public storage with ORIGINAL filename (not UUID)
      → Clean up both input and output cache files
      → File written to Pictures/Raw2DNG/ or Pictures/Raw2DNG/JPEG/
  → When user taps NEEDS_CONFIRMATION item:
    → onOverwriteConfirmed callback creates task with unique ID
    → ConversionQueue.addTaskDynamic() sends to channel
    → Workers pick up task immediately
    → Item changes to PENDING, then IN_PROGRESS when processing starts
  → MediaScanner notified
  → onAllComplete:
    → If pendingOverwrites > 0: show "awaiting confirmation" message
    → If all done: enable Done button, trigger auto-navigate if applicable
```

#### Conversion Progress UI
```
ConversionThumbnailAdapter
  → ConversionThumbnailItem (uri, fileName, status, errorMessage, needsOverwrite)
  → ConversionItemStatus: PENDING, NEEDS_CONFIRMATION, IN_PROGRESS, SUCCESS, ERROR
  → Displays 3-column grid of thumbnails with status overlays:
    - PENDING: Dark overlay (50% opacity)
    - NEEDS_CONFIRMATION: Medium overlay (60%) + orange "Tap to overwrite" badge
    - IN_PROGRESS: Darker overlay (70%) + spinning progress indicator
    - SUCCESS: Light overlay (30%) + green checkmark
    - ERROR: Medium overlay (60%) + red error icon
  → Global progress bar at bottom with "X/Y" count
  → Thumbnail loading: OS ContentResolver → ThumbnailCache fallback
  → onOverwriteConfirmed callback: triggered when user taps NEEDS_CONFIRMATION item

Log View (toggle with "Log" button)
  → Shows conversion log messages in monospace font
  → Auto-scrolls to bottom on new messages
  → Toggle persisted via SharedPreferences (KEY_SHOW_LOG)
  → Messages: "Converting: filename...", "✓ filename", "✗ filename: error"

Auto-Navigate Feature
  → Checkbox: "Automatically go to Gallery on completion"
  → Preference persisted via SharedPreferences (KEY_AUTO_NAVIGATE)
  → If checked AND all conversions succeed:
    → 3-second countdown displayed on Done button: "Done (3)", "Done (2)", "Done (1)"
    → Auto-navigates to Gallery tab when countdown completes
  → Checking box after completion also triggers countdown
  → Unchecking cancels countdown
```

#### Gallery Refresh
```
GalleryFragment.loadImages()
  → Scan Pictures/Raw2DNG/*.dng
  → Scan Pictures/Raw2DNG/JPEG/*.jpg
  → Apply current filter (DNG_ONLY, JPEG_ONLY, ALL)
  → Update RecyclerView

GalleryFragment.setupFilterChips()
  → When filter changes to more restrictive (ALL→DNG, ALL→JPEG, DNG→JPEG, JPEG→DNG):
    → clearSelectionOnFilterChange() clears any active multi-select
    → Updates selection UI (hides "Open in..." button)
  → When filter changes to less restrictive (DNG→ALL, JPEG→ALL):
    → Selections are preserved
```

### Conversion Status Sync

When files are converted or deleted, the UI updates in real-time:

```
Conversion Complete:
  → onTaskComplete updates RawFileItem.isConvertedToDng/Jpeg immediately
  → updateItemConversionStatus(uri, format) sets the flag
  → applyFilter() refreshes the UI with updated badges

Gallery Deletion:
  → GalleryFragment.clearRaw2DNGFolder() or deleteRequestLauncher
  → After 500ms delay (wait for file system)
  → MainActivity.refreshConvertTab()
  → RawFilePickerFragment.refreshConversionStatus()
  → Re-check ConvertedFilesHelper for each cached RawFileItem
  → Update mutable isConvertedToDng/isConvertedToJpeg
  → adapter.notifyDataSetChanged() to refresh badges
```

### Preview Dialog with Thumbnail Strip

```
ImagePreviewDialog
  → Fullscreen image preview with ViewPager2
  → PreviewMode enum determines UI:
    - RAW_CONVERSION: Shows JPEG/DNG convert buttons
    - GALLERY_VIEW: Shows "Open with" button
  → Thumbnail strip (gutter) at bottom shows all images
  → ThumbnailStripAdapter displays ThumbnailStripItem:
    - uri: Image URI
    - isConvertedToDng: Shows "D" badge (RAW mode)
    - isConvertedToJpeg: Shows "J" badge (RAW mode)
    - isSelected: Shows green checkmark (Gallery mode)
  → Badge styling: 14sp bold text, green on dark background, white shadow border
  → Selection border highlights current viewing position
  → Long-press on thumbnail toggles selection (Gallery mode)
  → Selection syncs back to GalleryAdapter on dialog dismiss
```

### Gallery Preview Flow

```
GalleryFragment.previewSelectedImages()
  → Collects all gallery items and selected URIs
  → Opens ImagePreviewDialog with PreviewMode.GALLERY_VIEW
  → Dialog shows all images, navigates to first selected
  → Thumbnail strip shows green checkmarks on selected items
  → User can:
    - Swipe to navigate between images
    - Tap checkbox button to toggle current image selection
    - Long-press thumbnail to toggle that image's selection
    - Tap "Open with" to send selected images to external app
  → On dismiss, selection state syncs back to GalleryAdapter
```

### Regenerate Flow

```
GalleryFragment.showRegenerateDialog()
  → Opens RegenerateDialog with selected gallery items
  → Dialog shows:
    - Format selection: DNG or JPEG radio buttons
    - JPEG settings panel (visible only when JPEG selected):
      - Quality slider (1-100), defaults from app settings
      - Chroma subsampling options (4:4:4, 4:2:2, 4:2:0)
      - Optimize Huffman checkbox
    - Cancel and Regenerate buttons
  → Settings are NOT persisted (one-time use)
  → Defaults loaded from SharedPreferences via JpegSettingsDialog helpers

GalleryFragment.regenerateSelectedImages()
  → For each selected gallery item:
    → Find original RAW file by matching base name (without extension)
    → Search across all enabled RAW extensions in original directory
    → If RAW found:
      → Copy RAW file to app cache directory
      → Convert to selected format using DNGConverter
      → Save output to Pictures/Raw2DNG/ or Pictures/Raw2DNG/JPEG/
      → Clean up cache files
    → If RAW not found:
      → Log warning, skip file
  → Shows progress dialog during regeneration
  → On completion:
    → Shows toast with success/failure count
    → Clears multi-select mode
    → Refreshes gallery view
    → Refreshes convert tab
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
7. **Gallery Preview**: Preview button in multi-select mode opens fullscreen preview with selection management
8. **Parallel Conversion**: ConversionQueue processes files in parallel with configurable parallelism (default: 2)
9. **Conversion Progress Grid**: Visual thumbnail grid showing per-file conversion status
10. **Overwrite Confirmation**: Previously converted files require tap to confirm before re-conversion
11. **Real-time Status Updates**: Conversion badges update immediately after conversion/deletion
12. **Preview Conversion Badges**: Thumbnail strip shows D/J/D+J badges per image
13. **Auto-Navigate to Gallery**: Configurable countdown (0-30s) after successful conversion, persisted in settings
14. **Log/Thumbnail Toggle**: Switch between visual progress grid and text log during conversion
15. **Tab Navigation Cleanup**: Conversion overlay clears when navigating away if done
16. **Screen Rotation Handling**: State preserved via configChanges (no activity recreation)
17. **Gallery Filter Clear Selection**: Changing gallery filter clears any active selection
18. **High-Quality JPEG Export**: Configurable JPEG encoding with quality, chroma subsampling, and Huffman optimization
19. **Settings Dialog**: Gear icon in both tabs opens settings with configurable auto-navigate timeout
20. **Hide/Dim Converted Images**: Optional filter to hide or gray out already-converted images
21. **JPEG Settings Dialog**: Configure default JPEG quality, chroma subsampling, and Huffman optimization
22. **File Size Display**: Gallery thumbnails show file size overlay; selection summary shows total size
23. **Preview File Size**: Gallery preview shows current image file size and total selected size
24. **EXIF/Metadata Overlay**: Info button in preview shows detailed image metadata in scrollable overlay

### JPEG Encoding Settings

JPEG export is fully parameterized from C++ through JNI to Kotlin:

```
DNGConverter.kt
  → convertToJPEG(inputPath, outputPath, quality, chromaSubsampling, optimizeCoding)
  → Constants:
    - CHROMA_SUBSAMPLING_444 (0): No subsampling - best quality, largest file
    - CHROMA_SUBSAMPLING_422 (1): Horizontal subsampling - medium quality
    - CHROMA_SUBSAMPLING_420 (2): H+V subsampling - smallest file, most artifacts
  → Defaults for export:
    - quality: 95 (high quality)
    - chromaSubsampling: 4:4:4 (no color artifacts)
    - optimizeCoding: true (Huffman optimization)

libraw_reader.cpp
  → JpegEncodingSettings struct controls encoding
  → Uses libjpeg with configurable:
    - jpeg_set_quality() for quality level
    - comp_info sampling factors for chroma subsampling
    - optimize_coding flag for Huffman table optimization
    - jpeg_simple_progression() for progressive JPEG (not exposed to JNI)
```

Thumbnails use `extractThumbnail()` which extracts embedded camera thumbnails (already JPEG) or falls back to PPM generation - no re-encoding needed.

### Color Matrix Handling

The app includes hardcoded color matrices for cameras not fully supported by LibRaw:
- Olympus OM-1 (ORF files)
- More can be added in `libraw_to_dng.cpp`

### Settings Fragment

Settings is a dedicated tab in the app (not a modal dialog). Settings auto-save when changed:

```
SettingsFragment.kt (Tab: Settings)
├── Auto-Navigate Timeout (0-30 seconds)
│   ├── SeekBar with value display
│   ├── 0 seconds shows warning (orange text)
│   └── Auto-saved via KEY_AUTONAV_TIMEOUT
│
├── Enabled RAW File Types (button → RawTypesDialogFragment)
│   ├── ExpandableListView organized by manufacturer
│   ├── Manufacturers: Canon, Nikon, Sony, Fujifilm, Olympus/OM System,
│   │   Panasonic/Lumix, Pentax, Samsung, Hasselblad, Phase One/Leaf,
│   │   Kodak, Epson, Mamiya
│   ├── 19 formats across all manufacturers (no DNG - output format)
│   ├── Manufacturer group checkbox toggles all child formats
│   ├── Group shows X/Y count of selected formats
│   ├── Done button requires at least one selection
│   ├── Select All / Clear All buttons
│   └── Auto-saved via KEY_ENABLED_RAW_TYPES (StringSet)
│
├── Hide Already Converted Images (checkbox)
│   ├── When checked: filter hides converted files completely
│   ├── When unchecked: converted files shown grayed out (35% opacity)
│   └── Auto-saved via KEY_HIDE_CONVERTED (Boolean, default: true)
│
├── Open Single DNG in Snapseed (checkbox)
│   ├── When checked: single DNG conversions auto-open in Snapseed instead of Gallery
│   ├── Disabled if Snapseed not installed (shows warning)
│   ├── Only triggers for single-file DNG conversions that complete successfully
│   └── Auto-saved via KEY_OPEN_IN_SNAPSEED (Boolean, default: false)
│
├── Share Single JPEG on Completion (checkbox)
│   ├── When checked: single JPEG conversions prompt Android share sheet instead of Gallery
│   ├── Only triggers for single-file JPEG conversions that complete successfully
│   └── Auto-saved via KEY_SHARE_SINGLE_JPEG (Boolean, default: false)
│
├── JPEG Quality Settings (button → JpegSettingsDialog)
│   ├── Quality: SeekBar 1-100 (default: 95)
│   ├── Chroma Subsampling: RadioGroup
│   │   ├── 4:4:4 - Best quality (default)
│   │   ├── 4:2:2 - Balanced
│   │   └── 4:2:0 - Smallest file
│   ├── Optimize Huffman: Checkbox (default: true)
│   └── Persisted via KEY_JPEG_QUALITY, KEY_JPEG_CHROMA, KEY_JPEG_OPTIMIZE
│
└── View Open Source Licenses (button → LicensesDialog)
    ├── LibRaw (LGPL 2.1 / CDDL 1.0)
    ├── Adobe DNG SDK (Adobe license)
    ├── cJSON (MIT license)
    ├── Independent JPEG Group (IJG license)
    └── Android/Kotlin Libraries (Apache 2.0)

RawTypesDialogFragment.kt
├── MANUFACTURERS: LinkedHashMap<String, List<String>>
│   Maps manufacturer display names to extension lists
├── getAllExtensions(): Array<String>
│   Flattens all manufacturer extensions for preferences compatibility
├── ManufacturerExpandableListAdapter
│   Custom adapter for expandable list with checkboxes
├── Group header: Manufacturer name + checkbox + X/Y count + expand indicator
├── Child items: Format extension checkbox (e.g., CR2, NEF)
└── Layouts: dialog_raw_types.xml, item_manufacturer_group.xml, item_format_child.xml

SharedPreferences Keys:
  PREFS_NAME = "raw2dng_prefs"
  KEY_AUTONAV_TIMEOUT = "autonav_timeout_seconds" (Int, default: 3)
  KEY_ENABLED_RAW_TYPES = "enabled_raw_types" (StringSet, default: all)
  KEY_HIDE_CONVERTED = "hide_converted_images" (Boolean, default: true)
  KEY_OPEN_IN_SNAPSEED = "open_single_dng_in_snapseed" (Boolean, default: false)
  KEY_SHARE_SINGLE_JPEG = "share_single_jpeg_on_completion" (Boolean, default: false)
  KEY_JPEG_QUALITY = "jpeg_quality" (Int, default: 95)
  KEY_JPEG_CHROMA = "jpeg_chroma_subsampling" (Int, default: 0 = 4:4:4)
  KEY_JPEG_OPTIMIZE = "jpeg_optimize_coding" (Boolean, default: true)
  KEY_AUTO_NAVIGATE = "auto_navigate" (Boolean)
  KEY_SHOW_LOG = "show_log" (Boolean)
  
Constants:
  SNAPSEED_PACKAGE = "com.niksoftware.snapseed"
```

### Important Implementation Details

1. **RawFileItem.isConvertedToDng/isConvertedToJpeg** are `var` (mutable) to allow refresh
2. **ConvertedFilesHelper** checks file existence on-demand (no caching)
3. **GalleryAdapter** supports both click (open) and long-press (multi-select)
4. **Android 11+ (R)** uses `MediaStore.createDeleteRequest()` for file deletion (skips app confirmation, uses system dialog)
5. **Android 11+ package visibility**: AndroidManifest.xml must declare `<queries><package android:name="com.niksoftware.snapseed" /></queries>` to query if Snapseed is installed. Without this, `getPackageInfo()` throws `NameNotFoundException` even when the app is installed.
6. **JPEG output** uses LibRaw's dcraw processing with libjpeg encoding at quality 95, 4:4:4 subsampling
7. **MainActivity** holds references to both pickerFragment and galleryFragment for cross-tab communication
8. **ThumbnailStripItem** carries conversion status (dng/jpeg booleans) for badge display
9. **500ms delay** after deletion before refreshing Convert tab (ensures filesystem sync)
10. **ViewPager2.OnPageChangeCallback** clears conversion overlay when switching tabs (if done)
11. **SharedPreferences** persists: auto-navigate checkbox, log/thumbnail toggle preference, timeout, RAW types
12. **conversionCompletedSuccessfully** flag enables checkbox to trigger countdown after completion
13. **configChanges** in manifest preserves state on screen rotation (no activity recreation)
14. **Button styling**: All buttons use Material filled style for consistency
15. **ConversionQueue.addTaskDynamic()**: Adds task to channel, workers pick it up immediately
16. **ConversionQueue.DEFAULT_PARALLELISM**: Set to 2, configurable via constructor parameter
17. **Thread-safe counters**: Uses AtomicInteger for startedCount, completedCount, successfulCount, failedCount
18. **Overwrite detection**: Based on RawFileItem.isConvertedToDng/Jpeg matching OutputFormat
19. **localtime_r()**: Thread-safe timestamp conversion in C++ (required for parallel JNI calls)
20. **LibRaw thread-safety**: CMakeLists.txt does NOT define LIBRAW_NOTHREADS, enabling LibRaw's thread-local storage (TLS)
21. **Unique cache file IDs**: UUID-based filenames prevent parallel file access conflicts (e.g., DSC_0001_a1b2c3d4.NEF)
22. **Original filename for public storage**: saveToPublicStorage() uses original filename, not UUID-based cache filename
23. **Cache cleanup**: Both input and output cache files are deleted after each conversion task completes
24. **Gallery filter clears selection**: Changing filters calls clearSelectionOnFilterChange() to prevent stale item references
25. **JPEG quality defaults**: 95 quality, 4:4:4 chroma (no subsampling), Huffman optimization enabled
26. **Double-tap preview**: Both GalleryAdapter and RawFileAdapter use GestureDetector for double-tap to open preview
27. **Settings gear icon**: Both fragments have headerRow with settings button that opens SettingsDialog
28. **RAW type filtering**: RawFilePickerFragment.getEnabledRawExtensions() reads from SharedPreferences
29. **FlexboxLayout**: Google library for responsive chip layout in settings dialog
30. **ExifData**: Hybrid helper class - LibRaw JNI for RAW/DNG files, ExifInterface for JPEG
31. **EXIF categories**: Camera, Lens, Exposure, Shooting, Dimensions, GPS, Date - displayed in scrollable overlay
32. **extractMetadataJson()**: JNI method in libraw_reader.cpp returns JSON with complete metadata
33. **EXIF transfer to DNG**: Complete EXIF written via DNG SDK including GPS, exposure program, metering mode
34. **EXIF transfer to JPEG**: After JPEG conversion, ExifData.writeExifToJpeg() copies EXIF from source RAW
35. **Overwrite on conversion**: saveToMediaStore() tries to delete existing file via findExistingFile(), then overwrites in place if delete fails. Only works for files created by current app installation (Android Scoped Storage limitation). Files from previous installations must be deleted from Gallery first.
36. **Conversion warnings**: SaveResult sealed class tracks success/warning/failure. Warnings (e.g., couldn't overwrite) are logged, progress bar turns orange, auto-navigate is cancelled, and status shows "Completed with X warning(s). See log for details."

### EXIF Data Transfer

The app preserves EXIF metadata when converting RAW files to DNG and JPEG formats:

```
RawMetadata struct (libraw_reader.h):
  → Basic EXIF: make, model, iso_speed, shutter, aperture, focal_len, timestamp
  → Lens info: lens_make, lens_model, lens_serial, min_focal, max_focal, focal_len_35mm
  → Shooting info: exposure_program, metering_mode, description, artist, body_serial
  → GPS data: latitude[3], longitude[3], altitude, lat_ref, lon_ref, alt_ref, timestamp[3]

DNG EXIF (libraw_to_dng.cpp setExifData()):
  → Camera: Make, Model, Software ("Raw2DNG")
  → Exposure: ISO, ExposureTime, FNumber, ApertureValue, ShutterSpeedValue
  → Lens: LensMake, LensName, FocalLength, FocalLengthIn35mmFilm, LensInfo
  → Date/Time: DateTimeOriginal, DateTimeDigitized, DateTime
  → Shooting: ExposureProgram, MeteringMode
  → Artist: ImageDescription, Artist
  → GPS: Latitude, Longitude, Altitude, LatitudeRef, LongitudeRef, AltitudeRef, TimeStamp

JPEG EXIF (ExifData.kt writeExifToJpeg()):
  → After JPEG conversion completes, extracts metadata via extractMetadataJson()
  → Uses Android ExifInterface to write all available tags to JPEG file
  → Handles GPS data formatting (degrees, minutes, seconds as rationals)
  → Called automatically by ConversionQueue after successful JPEG conversion

extractMetadataJson() output (libraw_reader.cpp):
  → JSON includes all metadata fields for Kotlin consumption
  → Camera: make, model, software
  → Lens: lens_make, lens_model, lens_serial, min_focal, max_focal, focal_length_35mm
  → Exposure: focal_length, aperture, shutter, shutter_raw, iso
  → Shooting: exposure_program, metering_mode, description, artist, body_serial
  → Time: timestamp, timestamp_raw
  → Dimensions: width, height, raw_width, raw_height, orientation
  → Color: colors, bayer_pattern
  → GPS: has_gps, gps_lat_deg/min/sec, gps_lat_ref, gps_lon_deg/min/sec, gps_lon_ref,
         gps_altitude, gps_alt_ref, gps_time_hour/min/sec
```

### EXIF/Metadata Overlay

```
ImagePreviewDialog Info Button Flow:
  → User taps info button (ℹ) in zoom controls
  → exifOverlay visibility toggles (GONE ↔ VISIBLE)
  → If becoming visible:
    → loadExifData() coroutine launches
    → ExifData.extractFromUri() called (checks file extension)
    → For RAW/DNG: LibRaw reads metadata via JNI extractMetadataJson()
    → For JPEG: ExifInterface reads EXIF tags
    → Metadata displayed in formatted overlay:
      - File: Name, Size, Type
      - Camera: Make, Model, Body Serial
      - Lens: Model, Serial, Focal Range
      - Exposure: Focal Length, Aperture, Shutter, ISO, 35mm equiv
      - Shooting: Exposure Program, Metering Mode
      - Dimensions: Output size, Sensor size, Bayer pattern
      - GPS: Coordinates, Altitude (if available)
      - Date/Time
      - Artist, Description (if available)
    → exifContent TextView updated with formatted text
    → Overlay is scrollable via exifScrollView

ExifData.kt (hybrid RAW/non-RAW extraction):
  → extractFromUri(context, uri, fileName, fileSize): Main entry point
    → Checks if file is RAW based on extension (uses RAW_EXTENSIONS set)
    → Copies file to temp cache location
    → For RAW/DNG: Calls extractFromRaw() using LibRaw JNI
    → For JPEG: Calls extractFromNonRaw() using ExifInterface
    → Deletes temp file after extraction
  → extractFromRaw(filePath, fileName, fileSize): LibRaw JNI path
    → Calls DNGConverter.extractMetadata() (JNI)
    → Parses JSON into ExifData data class
  → extractFromNonRaw(filePath, fileName, fileSize): ExifInterface path
    → Uses Android ExifInterface to read JPEG EXIF tags
    → Returns ExifData data class
  → writeExifToJpeg(rawFilePath, jpegFilePath): Write EXIF to JPEG
    → Extracts metadata from source via LibRaw
    → Writes to JPEG using Android ExifInterface
  → Data class fields:
    - Camera: make, model, bodySerial, software
    - Lens: lensMake, lensModel, lensSerial, minFocal, maxFocal
    - Focal: focalLength, focalLength35mm
    - Exposure: aperture, shutterSpeed, shutterRaw, iso, exposureProgram, meteringMode
    - Time: dateTime, timestampRaw
    - Dimensions: width, height, rawWidth, rawHeight, orientation
    - Color: colors, bayerPattern
    - GPS: hasGps, gpsLatitude, gpsLongitude, gpsAltitude, gpsLatRef, gpsLonRef
    - Other: description, artist
    - File: fileSize, fileName, fileType, error

libraw_reader.cpp:
  → extractMetadataJson(inputPath, errorMessage): Static method
  → Returns JSON string with all metadata fields
  → Uses cJSON library for proper JSON serialization (handles escaping, formatting)
  → Uses LibRaw's imgdata structs: idata, other, sizes, lens, shootinginfo
  → GPS validation: Only outputs GPS if coordinates are non-zero
  → Thread-safe: Uses localtime_r for timestamp conversion
```

### Testing Checklist

When making changes, verify:
- [ ] RAW file thumbnails load correctly
- [ ] Fullscreen preview works
- [ ] Double-tap in Convert tab opens preview
- [ ] Double-tap in Gallery tab opens preview
- [ ] DNG conversion produces valid files
- [ ] JPEG conversion produces valid files
- [ ] JPEG quality is high (no visible compression artifacts)
- [ ] Gallery shows correct files per filter
- [ ] Clear folder respects current filter
- [ ] Conversion badges update after gallery changes
- [ ] Conversion progress grid shows correct status per file
- [ ] Multi-select works in gallery
- [ ] "Open in..." works for selected files
- [ ] Preview thumbnail strip shows D/J badges correctly
- [ ] Auto-navigate countdown works when checkbox is checked
- [ ] Checking auto-navigate after completion triggers countdown
- [ ] Log/Thumbnail toggle works during and after conversion
- [ ] Tab switch clears conversion overlay when done
- [ ] Screen rotation preserves selections and conversion state
- [ ] Screen rotation during conversion doesn't interrupt it
- [ ] Preview dialog survives screen rotation
- [ ] Overwrite confirmation shows for already-converted files
- [ ] Tapping overwrite confirmation queues and processes the file
- [ ] Tapping multiple overwrites during conversion processes all of them
- [ ] Done button disabled until all confirmations resolved or converted
- [ ] Parallel conversion of multiple files produces non-corrupt output
- [ ] Overwrite replaces existing file for files created by current app installation
- [ ] Files from previous installations: delete from Gallery first, then re-convert
- [ ] Changing gallery filter clears any active selection
- [ ] Gallery preview button opens fullscreen preview
- [ ] Gallery preview shows checkmarks on selected items
- [ ] Toggle selection from gallery preview updates adapter on dismiss
- [ ] Long-press thumbnail in gallery preview toggles selection
- [ ] "Open with" button in gallery preview works
- [ ] Settings dialog opens from gear icon in both tabs
- [ ] Auto-navigate timeout setting persists across app restarts
- [ ] RAW type selection dialog shows manufacturers as expandable groups
- [ ] Clicking manufacturer checkbox toggles all child format checkboxes
- [ ] RAW type selection shows X/Y count per manufacturer group
- [ ] RAW type selection persists and filters file list correctly
- [ ] Disabling all RAW types shows warning and prevents save
- [ ] Changing RAW types refreshes file list on dialog dismiss
- [ ] Hide converted images setting works (hides vs grays out)
- [ ] Grayed out images are not selectable
- [ ] JPEG settings dialog opens from settings
- [ ] JPEG quality, chroma, and optimize settings persist
- [ ] JPEG conversion uses saved settings
- [ ] Licenses dialog opens and displays all licenses
- [ ] Licenses dialog is scrollable
- [ ] Snapseed setting checkbox disabled if Snapseed not installed
- [ ] Snapseed setting persists across app restarts
- [ ] Single DNG conversion opens Snapseed when enabled and Snapseed installed
- [ ] Multi-file DNG conversion does NOT open Snapseed (navigates to Gallery instead)
- [ ] JPEG conversion does NOT trigger Snapseed opening
- [ ] Share JPEG setting persists across app restarts
- [ ] Single JPEG conversion opens share sheet when enabled
- [ ] Multi-file JPEG conversion does NOT trigger share (navigates to Gallery instead)
- [ ] DNG conversion does NOT trigger share sheet
- [ ] Regenerate button appears in Gallery multi-select mode
- [ ] Regenerate dialog shows format selection and JPEG settings
- [ ] JPEG settings panel shows/hides based on format selection
- [ ] Regenerate dialog defaults match app JPEG settings
- [ ] Regenerate finds original RAW files and converts successfully
- [ ] Regenerate shows progress dialog during conversion
- [ ] Regenerate clears selection and refreshes gallery on completion
- [ ] Regenerate reports count of successful/missing files
- [ ] Gallery thumbnails show file size overlay
- [ ] Gallery selection summary shows total size
- [ ] Gallery preview shows file size next to filename
- [ ] Gallery preview selection count shows total selected size
- [ ] Info button appears in preview zoom controls
- [ ] EXIF overlay shows metadata for DNG/JPEG files
- [ ] RAW metadata overlay shows camera info for RAW files
- [ ] EXIF overlay is scrollable for long content
- [ ] EXIF overlay toggle works (show/hide)
- [ ] EXIF Advanced button shows raw JSON (pretty-printed, green monospace)
- [ ] Tap on JSON view goes back to basic view
- [ ] Double-tap on JSON view copies JSON to clipboard
- [ ] Converted JPEG files contain complete EXIF data (camera, lens, exposure, GPS)
- [ ] Converted DNG files contain complete EXIF data (camera, lens, exposure, GPS)
- [ ] GPS coordinates are preserved in both JPEG and DNG output
- [ ] Date/time is correctly transferred to converted files

### Common Issues

1. **Purple cast in dark images**: Check per-channel black level handling in `libraw_to_dng.cpp`
2. **Wrong colors**: Check color matrix application and camera model detection
3. **Badges not updating**: Ensure `refreshConversionStatus()` is called in `onResume()`
4. **Install fails**: Run `adb uninstall com.raw2dng` first if signing keys changed
5. **Corrupt output during parallel conversion**: Ensure `LIBRAW_NOTHREADS` is NOT defined in CMakeLists.txt (LibRaw needs Thread Local Storage enabled)
6. **Files saved with UUID in name**: Check that `saveToPublicStorage()` receives the original filename, not the cache filename
7. **RAW files not appearing**: Check settings for enabled RAW types, verify extension is in ALL_RAW_EXTENSIONS
8. **Missing EXIF in JPEG**: Check that ExifData.writeExifToJpeg() is called after conversion in ConversionQueue
9. **File gets "(1)" suffix on overwrite**: File was created by previous app installation. Android Scoped Storage prevents modifying files not owned by the current app. Delete from Gallery first using the clear/delete feature (uses `MediaStore.createDeleteRequest()` for system confirmation), then re-convert.
