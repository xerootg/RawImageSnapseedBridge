# Raw2DNG - Copilot Instructions

## Requirements for all changes made
update this file to reflect any architectural or workflow changes made in the codebase. Ensure that all new features, classes, methods, and workflows are thoroughly documented here. Include diagrams or flowcharts if they help clarify complex processes. Maintain a clear and organized structure for easy navigation.

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
├── ConversionQueue.kt        # Manages sequential conversion jobs
├── ConversionThumbnailAdapter.kt # Adapter for conversion progress thumbnails
├── ConvertedFilesHelper.kt   # Checks conversion status by file existence
├── OutputFormat.kt           # Enum: DNG, JPEG
│
├── ThumbnailCache.kt         # LRU cache for RAW thumbnails extracted via LibRaw
├── ThumbnailStripAdapter.kt  # Horizontal thumbnail strip in preview dialog
├── ThumbnailStripItem.kt     # Data class with uri and conversion status
├── DNGConverter.kt           # JNI bridge to native code
├── ImagePreviewDialog.kt     # Fullscreen preview dialog with selection
├── ImagePagerAdapter.kt      # ViewPager2 adapter for preview images
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
  → Partition selected files into:
    - readyToConvert: Files not yet converted to target format
    - needsConfirmation: Files already converted (require user tap)
  → Create ConversionThumbnailItem list:
    - readyToConvert items start as PENDING
    - needsConfirmation items start as NEEDS_CONFIRMATION
  → Show conversion overlay with thumbnail grid
  → Set up onOverwriteConfirmed callback for dynamic task creation
  → ConversionQueue.addTasks() for readyToConvert items only
  → ConversionQueue.start() begins processing
  → For each task:
    → onTaskStarting callback marks item as IN_PROGRESS
    → DNGConverter.convertToDNG() [JNI]
    → native dng_converter.cpp
      → libraw_reader reads RAW data
      → libraw_to_dng creates DNG (if format includes DNG)
      → jpeg_converter creates JPEG (if format includes JPEG)
    → onTaskComplete callback:
      → Mark item SUCCESS or ERROR in thumbnail grid
      → Increment global progress bar
      → File written to Pictures/Raw2DNG/ or Pictures/Raw2DNG/JPEG/
  → When user taps NEEDS_CONFIRMATION item:
    → onOverwriteConfirmed callback creates task
    → ConversionQueue.addTaskDynamic() adds to running queue
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
  → Thumbnail strip (gutter) at bottom shows all images
  → ThumbnailStripAdapter displays ThumbnailStripItem:
    - uri: Image URI
    - isConvertedToDng: Shows "D" badge
    - isConvertedToJpeg: Shows "J" badge
    - Both: Shows "D/J" badge
  → Badge styling: 14sp bold text, green on dark background, white shadow border
  → Selection border highlights current image
  → JPEG/DNG convert buttons with selection count
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
7. **Parallel Conversion**: ConversionQueue processes files in parallel with configurable parallelism (default: 2)
8. **Conversion Progress Grid**: Visual thumbnail grid showing per-file conversion status
9. **Overwrite Confirmation**: Previously converted files require tap to confirm before re-conversion
10. **Real-time Status Updates**: Conversion badges update immediately after conversion/deletion
11. **Preview Conversion Badges**: Thumbnail strip shows D/J/D+J badges per image
12. **Auto-Navigate to Gallery**: Optional 3-second countdown after successful conversion
13. **Log/Thumbnail Toggle**: Switch between visual progress grid and text log during conversion
14. **Tab Navigation Cleanup**: Conversion overlay clears when navigating away if done
15. **Screen Rotation Handling**: State preserved via configChanges (no activity recreation)

### Color Matrix Handling

The app includes hardcoded color matrices for cameras not fully supported by LibRaw:
- Olympus OM-1 (ORF files)
- More can be added in `libraw_to_dng.cpp`

### Important Implementation Details

1. **RawFileItem.isConvertedToDng/isConvertedToJpeg** are `var` (mutable) to allow refresh
2. **ConvertedFilesHelper** checks file existence on-demand (no caching)
3. **GalleryAdapter** supports both click (open) and long-press (multi-select)
4. **Android 11+ (R)** uses `MediaStore.createDeleteRequest()` for file deletion (skips app confirmation, uses system dialog)
5. **JPEG output** uses LibRaw's dcraw processing with libjpeg encoding
6. **MainActivity** holds references to both pickerFragment and galleryFragment for cross-tab communication
7. **ThumbnailStripItem** carries conversion status (dng/jpeg booleans) for badge display
8. **500ms delay** after deletion before refreshing Convert tab (ensures filesystem sync)
9. **ViewPager2.OnPageChangeCallback** clears conversion overlay when switching tabs (if done)
10. **SharedPreferences** persists: auto-navigate checkbox, log/thumbnail toggle preference
11. **conversionCompletedSuccessfully** flag enables checkbox to trigger countdown after completion
12. **configChanges** in manifest preserves state on screen rotation (no activity recreation)
13. **Button styling**: All buttons use Material filled style for consistency
14. **ConversionQueue.addTaskDynamic()**: Adds task to channel, workers pick it up immediately
15. **ConversionQueue.DEFAULT_PARALLELISM**: Set to 2, configurable via constructor parameter
16. **Thread-safe counters**: Uses AtomicInteger for startedCount, completedCount, successfulCount, failedCount
17. **Overwrite detection**: Based on RawFileItem.isConvertedToDng/Jpeg matching OutputFormat
18. **localtime_r()**: Thread-safe timestamp conversion in C++ (required for parallel JNI calls)

### Testing Checklist

When making changes, verify:
- [ ] RAW file thumbnails load correctly
- [ ] Fullscreen preview works
- [ ] DNG conversion produces valid files
- [ ] JPEG conversion produces valid files
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

### Common Issues

1. **Purple cast in dark images**: Check per-channel black level handling in `libraw_to_dng.cpp`
2. **Wrong colors**: Check color matrix application and camera model detection
3. **Badges not updating**: Ensure `refreshConversionStatus()` is called in `onResume()`
4. **Install fails**: Run `adb uninstall com.raw2dng` first if signing keys changed
