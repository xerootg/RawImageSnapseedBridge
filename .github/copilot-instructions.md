# Raw2DNG - Copilot Instructions

## Requirements for all changes made
Update this file to reflect any architectural or workflow changes made in the codebase. Ensure that all new features, classes, methods, and workflows are thoroughly documented here. Include diagrams or flowcharts if they help clarify complex processes. Maintain a clear and organized structure for easy navigation.

Ensure all licenses are properly attributed and included in the documentation as well as the Licenses dialog in the app.

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
│   ├── Single-tap to toggle selection
│   ├── Double-tap to open fullscreen preview
│   ├── Conversion controls (DNG/JPEG)
│   └── Conversion overlay with:
│       ├── Thumbnail grid / Log toggle
│       ├── Auto-navigate checkbox (persisted)
│       ├── Progress bar
│       └── Done button with countdown
│
└── GalleryFragment (Tab: Gallery)
    ├── Grid view of converted images
    ├── Filter chips (DNG / JPEG / All)
    ├── Single-tap opens in external app
    ├── Double-tap opens fullscreen preview
    ├── Long-press for multi-select mode
    ├── Preview button (multi-select mode)
    ├── Regenerate button (multi-select mode)
    ├── Clear folder (filter-specific)
    └── Open in external app

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
├── OutputFormat.kt           # Enum: DNG, JPEG
│
├── ThumbnailCache.kt         # LRU cache for RAW thumbnails extracted via LibRaw
├── ThumbnailStripAdapter.kt  # Horizontal thumbnail strip in preview dialog
├── ThumbnailStripItem.kt     # Data class with uri and conversion status
├── DNGConverter.kt           # JNI bridge to native code
├── ImagePreviewDialog.kt     # Fullscreen preview dialog with selection
├── ImagePagerAdapter.kt      # ViewPager2 adapter for preview images
├── SettingsDialog.kt         # Main settings dialog with all app preferences
├── JpegSettingsDialog.kt     # JPEG conversion settings (quality, chroma, optimize)
├── LicensesDialog.kt         # Open source licenses display
├── RegenerateDialog.kt       # Regeneration settings dialog (one-time settings)
├── ExifData.kt               # EXIF/metadata extraction helper (ExifInterface + JNI)
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
17. **File Size Display**: Gallery thumbnails show file size overlay; selection summary shows total size
18. **Preview File Size**: Gallery preview shows current image file size and total selected size
19. **EXIF/Metadata Overlay**: Info button in preview shows detailed image metadata in scrollable overlay

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

### Settings Dialog

The app includes a settings dialog accessible via gear icon in both fragments:

```
SettingsDialog.kt
├── Auto-Navigate Timeout (0-30 seconds)
│   ├── SeekBar with value display
│   ├── 0 seconds shows warning (orange text)
│   └── Persisted via KEY_AUTONAV_TIMEOUT
│
├── Enabled RAW File Types
│   ├── Multi-chip selection (FlexboxLayout)
│   ├── 20 formats: cr2, cr3, nef, nrw, arw, srf, sr2, orf, pef,
│   │   rw2, 3fr, iiq, dcr, k25, kdc, erf, mef, mos, raf, dng
│   ├── Warning if none selected
│   └── Persisted via KEY_ENABLED_RAW_TYPES (StringSet)
│
├── Hide Already Converted Images (checkbox)
│   ├── When checked: filter hides converted files completely
│   ├── When unchecked: converted files shown grayed out (35% opacity)
│   └── Persisted via KEY_HIDE_CONVERTED (Boolean, default: true)
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
    ├── Independent JPEG Group (IJG license)
    └── Android/Kotlin Libraries (Apache 2.0)

SharedPreferences Keys:
  PREFS_NAME = "raw2dng_prefs"
  KEY_AUTONAV_TIMEOUT = "autonav_timeout_seconds" (Int, default: 3)
  KEY_ENABLED_RAW_TYPES = "enabled_raw_types" (StringSet, default: all)
  KEY_HIDE_CONVERTED = "hide_converted_images" (Boolean, default: true)
  KEY_JPEG_QUALITY = "jpeg_quality" (Int, default: 95)
  KEY_JPEG_CHROMA = "jpeg_chroma_subsampling" (Int, default: 0 = 4:4:4)
  KEY_JPEG_OPTIMIZE = "jpeg_optimize_coding" (Boolean, default: true)
  KEY_AUTO_NAVIGATE = "auto_navigate" (Boolean)
  KEY_SHOW_LOG = "show_log" (Boolean)
```

### Important Implementation Details

1. **RawFileItem.isConvertedToDng/isConvertedToJpeg** are `var` (mutable) to allow refresh
2. **ConvertedFilesHelper** checks file existence on-demand (no caching)
3. **GalleryAdapter** supports both click (open) and long-press (multi-select)
4. **Android 11+ (R)** uses `MediaStore.createDeleteRequest()` for file deletion (skips app confirmation, uses system dialog)
5. **JPEG output** uses LibRaw's dcraw processing with libjpeg encoding at quality 95, 4:4:4 subsampling
6. **MainActivity** holds references to both pickerFragment and galleryFragment for cross-tab communication
7. **ThumbnailStripItem** carries conversion status (dng/jpeg booleans) for badge display
8. **500ms delay** after deletion before refreshing Convert tab (ensures filesystem sync)
9. **ViewPager2.OnPageChangeCallback** clears conversion overlay when switching tabs (if done)
10. **SharedPreferences** persists: auto-navigate checkbox, log/thumbnail toggle preference, timeout, RAW types
11. **conversionCompletedSuccessfully** flag enables checkbox to trigger countdown after completion
12. **configChanges** in manifest preserves state on screen rotation (no activity recreation)
13. **Button styling**: All buttons use Material filled style for consistency
14. **ConversionQueue.addTaskDynamic()**: Adds task to channel, workers pick it up immediately
15. **ConversionQueue.DEFAULT_PARALLELISM**: Set to 2, configurable via constructor parameter
16. **Thread-safe counters**: Uses AtomicInteger for startedCount, completedCount, successfulCount, failedCount
17. **Overwrite detection**: Based on RawFileItem.isConvertedToDng/Jpeg matching OutputFormat
18. **localtime_r()**: Thread-safe timestamp conversion in C++ (required for parallel JNI calls)
19. **LibRaw thread-safety**: CMakeLists.txt does NOT define LIBRAW_NOTHREADS, enabling LibRaw's thread-local storage (TLS)
20. **Unique cache file IDs**: UUID-based filenames prevent parallel file access conflicts (e.g., DSC_0001_a1b2c3d4.NEF)
21. **Original filename for public storage**: saveToPublicStorage() uses original filename, not UUID-based cache filename
22. **Cache cleanup**: Both input and output cache files are deleted after each conversion task completes
23. **Gallery filter clears selection**: Changing filters calls clearSelectionOnFilterChange() to prevent stale item references
24. **JPEG quality defaults**: 95 quality, 4:4:4 chroma (no subsampling), Huffman optimization enabled
25. **Double-tap preview**: Both GalleryAdapter and RawFileAdapter use GestureDetector for double-tap to open preview
26. **Settings gear icon**: Both fragments have headerRow with settings button that opens SettingsDialog
27. **RAW type filtering**: RawFilePickerFragment.getEnabledRawExtensions() reads from SharedPreferences
28. **FlexboxLayout**: Google library for responsive chip layout in settings dialog
29. **ExifData**: Helper class using ExifInterface (DNG/JPEG) or JNI extractMetadataJson (RAW)
30. **EXIF categories**: Camera, Exposure, Lens, Image, File - displayed in scrollable overlay
31. **extractMetadataJson()**: JNI method in libraw_reader.cpp returns JSON with RAW metadata

### EXIF/Metadata Overlay

```
ImagePreviewDialog Info Button Flow:
  → User taps info button (ℹ) in zoom controls
  → exifOverlay visibility toggles (GONE ↔ VISIBLE)
  → If becoming visible:
    → loadExifData() coroutine launches
    → PreviewMode determines extraction method:
      - GALLERY_VIEW: ExifInterface reads DNG/JPEG EXIF tags
      - RAW_CONVERSION: JNI extractMetadataJson() via LibRaw
    → Metadata parsed into categories:
      - Camera: Make, Model
      - Exposure: ISO, Shutter Speed, Aperture, Exposure Bias
      - Lens: Focal Length, Lens Model
      - Image: Resolution, Orientation
      - File: Size
    → Categories formatted as section headers with key-value pairs
    → exifContent TextView updated with formatted text
    → Overlay is scrollable via exifScrollView

ExifData.kt:
  → extractExifData(context, uri, mode): Main entry point
  → extractRawMetadata(inputPath): JNI path for RAW files
  → parseRawMetadataJson(json): Parses JSON from native code
  → Returns Map<String, Map<String, String>> (category → key/value pairs)

libraw_reader.cpp:
  → extractMetadataJson(inputPath, errorMessage): Static method
  → Returns JSON string with:
    - camera: {make, model}
    - exposure: {iso, shutter, aperture, exposure_bias}
    - lens: {focal_length, lens}
    - image: {width, height, orientation, timestamp}
    - sensor: {raw_width, raw_height}
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
- [ ] Changing gallery filter clears any active selection
- [ ] Gallery preview button opens fullscreen preview
- [ ] Gallery preview shows checkmarks on selected items
- [ ] Toggle selection from gallery preview updates adapter on dismiss
- [ ] Long-press thumbnail in gallery preview toggles selection
- [ ] "Open with" button in gallery preview works
- [ ] Settings dialog opens from gear icon in both tabs
- [ ] Auto-navigate timeout setting persists across app restarts
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

### Common Issues

1. **Purple cast in dark images**: Check per-channel black level handling in `libraw_to_dng.cpp`
2. **Wrong colors**: Check color matrix application and camera model detection
3. **Badges not updating**: Ensure `refreshConversionStatus()` is called in `onResume()`
4. **Install fails**: Run `adb uninstall com.raw2dng` first if signing keys changed
5. **Corrupt output during parallel conversion**: Ensure `LIBRAW_NOTHREADS` is NOT defined in CMakeLists.txt (LibRaw needs Thread Local Storage enabled)
6. **Files saved with UUID in name**: Check that `saveToPublicStorage()` receives the original filename, not the cache filename
7. **RAW files not appearing**: Check settings for enabled RAW types, verify extension is in ALL_RAW_EXTENSIONS
