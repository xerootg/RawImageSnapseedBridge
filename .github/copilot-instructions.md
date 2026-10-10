# Raw2DNG - Copilot Instructions

## Requirements for all changes made
Update this file to reflect any architectural or workflow changes made in the codebase. Ensure that all new features, classes, methods, and workflows are thoroughly documented here. Include diagrams or flowcharts if they help clarify complex processes. Maintain a clear and organized structure for easy navigation.

Ensure all licenses are properly attributed and included in the documentation as well as the Licenses dialog in the app.

## Tools
1. **Mandatory** Using Sequential Thinking MCP if it is installed and available in your environment.

## Project Overview

Raw2DNG is an Android application that converts RAW camera files (CR2, CR3, ARW, NEF, ORF, RAF, RW2, PEF, SRW, etc.) to Adobe DNG format and/or JPEG. All RAW decoding, DNG authoring, JPEG export and metadata extraction is done by **dnglab/rawler** (Rust, LGPL-2.1, https://github.com/xerootg/dnglab) through the `dnglab-ffi` C ABI. The native layer is deliberately thin: a backend-neutral C++ interface (`RawBackend`) sits between JNI and the RAW library so the engine can be swapped without touching Kotlin.

Key behaviours of the dnglab backend:
- **Embedded camera JPEG reuse**: most RAW formats carry the full-size JPEG the camera rendered. JPEG export copies it verbatim (with fresh EXIF) when the user-selected policy allows, falling back to demosaicing/rendering (PPG demosaic, camera matrix, dcraw-style auto brightness) when the embedded image is too small (e.g. Sony ARW, Panasonic RW2, Olympus ORF) or the policy says "never".
- **DNG authoring** uses rawler's writer: lossless JPEG-92, camera profiles/opcodes from the dnglab camera database, embedded preview/thumbnail, full EXIF/GPS, XMP.
- **Thumbnails/previews** come from the embedded JPEG (resized to 3072px, rotated upright in Rust) with fallbacks to decoder previews and a full develop.

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
- Android SDK/NDK/CMake
- Rust toolchain with the four Android targets (builds `dnglab-ffi`)
- dnglab (git submodule at `app/src/main/cpp/dnglab`)
- GitVersion for semantic versioning

Building against a local dnglab checkout instead of the submodule:
```bash
./gradlew assembleDebug -PdnglabSourceDir=/path/to/dnglab
# or: DNGLAB_SOURCE_DIR=/path/to/dnglab ./gradlew assembleDebug
```

### Versioning

The project uses [GitVersion](https://gitversion.net/) for automatic semantic versioning based on Git history.

**Configuration**: `GitVersion.yml`
- Mode: ContinuousDeployment
- Tag prefix: `v` (e.g., `v1.0.0`)
- Branch strategies:
  - `main`: No suffix, patch increment
  - `develop`: `-beta` suffix
  - `feature/*`: `-alpha` suffix
  - `release/*`: `-rc` suffix
  - Pull requests: `-pr.X` suffix

**Creating a Release**:
```bash
# Tag a release (triggers version bump)
git tag v1.0.0
git push origin v1.0.0
```

**Version in Code**:
- `BuildConfig.VERSION_NAME` - Full semantic version (e.g., "1.0.0" or "1.0.1-alpha.3")
- `BuildConfig.VERSION_CODE` - Commits since last version source (for Play Store)

**Environment Variables** (for CI/CD):
- `VERSION_NAME` - Override semantic version
- `VERSION_CODE` - Override version code

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

### CI/CD Pipeline

GitHub Actions workflow (`.github/workflows/build.yml`) automatically:
1. Checks out code with submodules (dnglab)
2. Runs GitVersion to determine version
3. Builds Docker image with Android SDK/NDK
4. Builds debug APK with version embedded
5. Uploads APK as artifact (named `app-debug-{version}`)

Triggers:
- Push to `main` branch
- Pull requests to `main`
- Manual dispatch via GitHub Actions UI

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
├── ThumbnailCache.kt         # LRU cache for RAW previews extracted via the native backend (upright, max 3072px)
├── ThumbnailStripAdapter.kt  # Horizontal thumbnail strip in preview dialog
├── ThumbnailStripItem.kt     # Data class with uri and conversion status
├── DNGConverter.kt           # Typed Kotlin facade over the JNI entry points (JpegResult/PreviewResult, backend info, supported extensions)
├── ImagePreviewDialog.kt     # Fullscreen preview dialog with selection
├── ImagePagerAdapter.kt      # ViewPager2 adapter for preview images
├── SettingsFragment.kt       # Settings tab with all app preferences (auto-save)
├── RawTypesDialogFragment.kt # Hierarchical RAW format selection dialog
├── JpegSettingsDialog.kt     # JPEG conversion settings (quality, chroma, optimize, embedded camera JPEG policy)
├── LicensesDialog.kt         # Open source licenses display
├── RegenerateDialog.kt       # Format/settings dialog for regeneration (navigates to Convert tab)
├── ExifData.kt               # EXIF/metadata extraction: backend JSON for RAW/DNG, ExifInterface for JPEG
└── FullscreenImageActivity.kt # Fullscreen RAW preview
```

### Native Code (C++ + Rust)

Located in `app/src/main/cpp/`:

```
cpp/
├── CMakeLists.txt            # Builds dnglab-ffi via cargo (per ABI) and links it into libraw2dng.so
├── raw_backend.h             # RawBackend interface: the ONLY API the JNI layer uses
├── raw2dng_jni.cpp           # JNI entry points (com.raw2dng.DNGConverter.native*)
├── dnglab_backend.cpp/h      # RawBackend implementation over the dnglab-ffi C ABI
└── dnglab/                   # dnglab fork (git submodule, Rust workspace)
    └── dnglab-ffi/           # C ABI crate: include/dnglab_ffi.h, src/{lib,api,exif_jpeg,metadata}.rs,
                              # scripts/build-android.sh
```

`RawBackend` (raw_backend.h) models everything the app needs from a RAW library:

| Method | Purpose |
|--------|---------|
| `convertToDNG(in, out, DngConversionSettings)` | DNG authoring |
| `convertToJPEG(in, out, JpegEncodingSettings, JpegResult&)` | JPEG export; `EmbeddedJpegPolicy` {Never, FullResolutionOnly, Always} decides whether the camera JPEG is reused |
| `extractPreview(in, out, maxDimension, PreviewResult&)` | JPEG preview for thumbnails/viewer, reports EXIF orientation still to apply |
| `extractMetadataJson(in, json&)` | Backend-neutral flat JSON (+ `details` with the full rawler metadata) |
| `name() / version() / supportedExtensions()` | Backend self-description, drives the RAW type picker |

JNI result protocol (parsed by `DNGConverter.kt`, never by UI code):
- `nativeConvertToDNG` → `"ok"` or error message
- `nativeConvertToJPEG` → `"ok:<usedEmbedded>:<orientation>:<w>x<h>:<exifEmbedded>"` or error
- `nativeExtractPreview` → `"ok:<orientation>:<fromEmbedded>:<w>x<h>"` or error
- `nativeExtractMetadata` → JSON, `{"error": "..."}` on failure

#### Rust build integration

`CMakeLists.txt` maps `ANDROID_ABI` to the Rust triple (arm64-v8a→aarch64-linux-android, armeabi-v7a→armv7-linux-androideabi, x86→i686-linux-android, x86_64→x86_64-linux-android), runs `dnglab-ffi/scripts/build-android.sh` (sets the NDK clang as cargo linker, profile `android` = release + thin LTO + stripped) into `app/build/cargo-target/`, and links the resulting `libdnglab_ffi.a` statically. Requirements: `cargo` on PATH (or `CARGO`/`CARGO_HOME`) and `rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`.

#### Git Submodules

```bash
# dnglab fork (rawler + dnglab-ffi) - RAW decoding, DNG authoring, JPEG, metadata
app/src/main/cpp/dnglab -> https://github.com/xerootg/dnglab
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
    → DNGConverter.convertToDNG() / convertToJPEG() [JNI] - rawler is thread-safe, each call is independent
    → raw2dng_jni.cpp → raw2dng::backend() (DnglabBackend) → dnglab-ffi (Rust)
      → DNG: rawler::dng::convert_raw_file (lossless JPEG-92, previews, EXIF/GPS, XMP, camera profile)
      → JPEG: embedded camera JPEG passthrough (+ EXIF splice) when policy allows,
              else demosaic → camera matrix → auto brightness → sRGB → rotate upright → encode
    → ConversionResult.usedEmbeddedJpeg is shown in the log as "(camera JPEG)"
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
  → User selects gallery items and taps "Regenerate" button
  → For each selected gallery item:
    → Find original RAW file by matching base name (without extension)
    → Search across all enabled RAW extensions
  → If no RAW files found: Show error toast
  → If some RAW files not found: Show partial match toast
  → Show RegenerateDialog with format selection (DNG/JPEG) and JPEG settings
  → User configures settings and taps "Regenerate"
  → Clear gallery selection
  → Navigate to Convert tab via MainActivity.navigateToConvertAndStart()
  → RawFilePickerFragment.selectFilesAndStartConversion():
    → Pre-selects the RAW files
    → Automatically starts conversion with the chosen format/settings
    → Shows conversion progress UI (thumbnail grid, progress bar, log)
  → User sees full conversion progress in Convert tab
```

### Output Directories

```
/storage/emulated/0/Pictures/Raw2DNG/
├── *.dng                     # Converted DNG files
└── JPEG/
    └── *.jpg                 # Converted JPEG files
```

### Key Features

1. **Thumbnail Loading**: dnglab-ffi extracts the embedded camera JPEG (or decoder preview, or a full develop as last resort), resizes to max 3072px and rotates upright; ThumbnailCache stores it once per file
2. **Fullscreen Preview**: Same cached 2048px preview, subsampled by BitmapFactory
3. **Embedded Camera JPEG Export**: JPEG export reuses the camera's full-size JPEG when available (configurable policy), keeping the in-camera look and finishing in milliseconds
4. **Camera Database**: Colour matrices, black/white levels, crops, baseline exposure, opcodes and lens corrections come from dnglab's camera database (cameras.toml) and DCP matching
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
18. **High-Quality JPEG Export**: Configurable JPEG encoding with quality, chroma subsampling, Huffman optimization and embedded camera JPEG policy
19. **Settings Dialog**: Gear icon in both tabs opens settings with configurable auto-navigate timeout
20. **Hide/Dim Converted Images**: Optional filter to hide or gray out already-converted images
21. **JPEG Settings Dialog**: Configure default JPEG quality, chroma subsampling, and Huffman optimization
22. **File Size Display**: Gallery thumbnails show file size overlay; selection summary shows total size
23. **Preview File Size**: Gallery preview shows current image file size and total selected size
24. **EXIF/Metadata Overlay**: Info button in preview shows detailed image metadata in scrollable overlay
25. **Configurable Conversion Parallelism**: Adjust number of concurrent conversion threads (1 to CPU cores)

### JPEG Encoding Settings

JPEG export is fully parameterized from Rust through the C ABI, C++ and JNI to Kotlin:

```
DNGConverter.kt
  → convertToJPEG(inputPath, outputPath, quality, chromaSubsampling, optimizeCoding, embeddedPolicy, embedExif): JpegResult
  → Constants:
    - CHROMA_SUBSAMPLING_444 (0): No subsampling - best quality, largest file
    - CHROMA_SUBSAMPLING_422 (1): Horizontal subsampling - medium quality
    - CHROMA_SUBSAMPLING_420 (2): H+V subsampling - smallest file, most artifacts
    - EMBEDDED_JPEG_NEVER (0): always render from sensor data
    - EMBEDDED_JPEG_FULL_RESOLUTION_ONLY (1, default): reuse the camera JPEG when both edges are >= 90% of the default crop
    - EMBEDDED_JPEG_ALWAYS (2): reuse any embedded JPEG
  → Defaults for export: quality 95, 4:4:4, Huffman optimization, FullResolutionOnly

dnglab-ffi (api.rs convert_to_jpeg)
  → Fast path: decoder.preview_jpeg() bytes → EXIF APP1 (built from rawler RawMetadata, MakerNotes stripped,
    orientation = sensor orientation) spliced after SOI/APP0 → written verbatim (quality settings do not apply)
  → Slow path: RawDevelop (rescale, PPG/bilinear demosaic, Fuji rotate, active area crop, WB, camera matrix, default crop)
    → dcraw-style auto brightness (1% clip) → sRGB gamma → rotated upright → jpeg-encoder with quality,
      sampling factor (4:4:4/4:2:2/4:2:0), optimized Huffman tables, EXIF with orientation 1
  → JpegResult reports usedEmbedded, dimensions, orientation and whether EXIF was embedded
    (ConversionQueue only falls back to ExifData.writeExifToJpeg() when a backend did not embed EXIF)
```

Which formats carry a full-resolution embedded JPEG (rawler `preview_jpeg`): NEF, CR2, CR3, most ORF bodies, PEF (newer), DNG (preview sub-IFD), RAF (varies by body). ARW, RW2 and older ORF/PEF embed reduced previews and therefore render.

Previews use `extractPreview(maxDimension)`: embedded JPEG copied verbatim when it already fits, otherwise decoded/resized/rotated and re-encoded at quality 90.

### Colour Handling

Colour matrices, calibration and opcodes come from dnglab's camera database (`rawler/data/cameras/*.toml`) and DCP matching; the app has no hardcoded matrices. Add camera support in the dnglab fork, not in this repository.

### Settings Fragment

Settings is a dedicated tab in the app (not a modal dialog). Settings auto-save when changed:

```
SettingsFragment.kt (Tab: Settings)
├── Auto-Navigate Timeout (0-30 seconds)
│   ├── SeekBar with value display
│   ├── 0 seconds shows warning (orange text)
│   └── Auto-saved via KEY_AUTONAV_TIMEOUT
│
├── Conversion Threads (1 to CPU cores)
│   ├── SeekBar with "X of Y cores" display
│   ├── Default: 2 threads
│   ├── Max: Runtime.getRuntime().availableProcessors()
│   └── Auto-saved via KEY_CONVERSION_PARALLELISM
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
│   ├── Quality: SeekBar 80-100 (default: 95)
│   ├── Chroma Subsampling: RadioGroup
│   │   ├── 4:4:4 - Best quality (default)
│   │   ├── 4:2:2 - Balanced
│   │   └── 4:2:0 - Smallest file
│   ├── Camera JPEG: RadioGroup (embedded JPEG policy)
│   │   ├── Use embedded JPEG when full resolution (default)
│   │   ├── Always use embedded JPEG
│   │   └── Never (always render)
│   ├── Optimize Huffman: Checkbox (default: true)
│   └── Persisted via KEY_JPEG_QUALITY, KEY_JPEG_CHROMA, KEY_JPEG_OPTIMIZE, KEY_JPEG_EMBEDDED_POLICY
│
├── View Open Source Licenses (button → LicensesDialog)
│   ├── DNGLab / rawler (LGPL-2.1, statically linked, source in the dnglab fork)
│   ├── Rust libraries (image, zune-jpeg, jpeg-encoder, serde, rayon, ... MIT/Apache-2.0)
│   └── Android/Kotlin Libraries (Apache 2.0)
│
└── RAW engine line (txtBackendInfo): "RAW engine: dnglab/rawler <version>" from DNGConverter.backendName/backendVersion

RawTypesDialogFragment.kt
├── MANUFACTURERS: LinkedHashMap<String, List<String>> (lazy)
│   Maps manufacturer display names to extension lists, filtered by DNGConverter.supportedExtensions
│   (what the native backend can actually decode); manufacturers without supported formats are dropped
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
  KEY_CONVERSION_PARALLELISM = "conversion_parallelism" (Int, default: 2, range: 1 to numCores)
  KEY_ENABLED_RAW_TYPES = "enabled_raw_types" (StringSet, default: all)
  KEY_HIDE_CONVERTED = "hide_converted_images" (Boolean, default: true)
  KEY_OPEN_IN_SNAPSEED = "open_single_dng_in_snapseed" (Boolean, default: false)
  KEY_SHARE_SINGLE_JPEG = "share_single_jpeg_on_completion" (Boolean, default: false)
  KEY_JPEG_QUALITY = "jpeg_quality" (Int, default: 95)
  KEY_JPEG_CHROMA = "jpeg_chroma_subsampling" (Int, default: 0 = 4:4:4)
  KEY_JPEG_OPTIMIZE = "jpeg_optimize_coding" (Boolean, default: true)
  KEY_JPEG_EMBEDDED_POLICY = "jpeg_embedded_policy" (Int, default: 1 = full resolution only)
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
6. **JPEG output** reuses the embedded camera JPEG when the policy allows, otherwise renders via rawler and encodes with jpeg-encoder (quality 95, 4:4:4 by default)
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
19. **Panic safety**: every dnglab-ffi entry point wraps rawler in catch_unwind; decoder panics surface as error strings, never as native crashes
20. **Thread-safety**: rawler has no global mutable state; conversions run concurrently, rayon's global pool is shared by all workers
21. **Unique cache file IDs**: UUID-based filenames prevent parallel file access conflicts (e.g., DSC_0001_a1b2c3d4.NEF)
22. **Original filename for public storage**: saveToPublicStorage() uses original filename, not UUID-based cache filename
23. **Cache cleanup**: Both input and output cache files are deleted after each conversion task completes
24. **Gallery filter clears selection**: Changing filters calls clearSelectionOnFilterChange() to prevent stale item references
25. **JPEG quality defaults**: 95 quality, 4:4:4 chroma (no subsampling), Huffman optimization enabled
26. **Double-tap preview**: Both GalleryAdapter and RawFileAdapter use GestureDetector for double-tap to open preview
27. **Settings gear icon**: Both fragments have headerRow with settings button that opens SettingsDialog
28. **RAW type filtering**: RawFilePickerFragment.getEnabledRawExtensions() reads from SharedPreferences
29. **FlexboxLayout**: Google library for responsive chip layout in settings dialog
30. **ExifData**: Hybrid helper class - backend JSON (dnglab-ffi) for RAW/DNG files, ExifInterface for JPEG
31. **EXIF categories**: Camera, Lens, Exposure, Shooting, Dimensions, GPS, Date - displayed in scrollable overlay
32. **extractMetadataJson()**: RawBackend method (dnglab-ffi metadata.rs) returns backend-neutral JSON with complete metadata
33. **EXIF transfer to DNG**: Complete EXIF written by rawler's DNG writer including GPS, lens, XMP
34. **EXIF transfer to JPEG**: dnglab-ffi embeds EXIF itself (JpegResult.exifEmbedded); ExifData.writeExifToJpeg() is only a fallback for backends that cannot
35. **Overwrite on conversion**: saveToMediaStore() tries to delete existing file via findExistingFile(), then overwrites in place if delete fails. Only works for files created by current app installation (Android Scoped Storage limitation). Files from previous installations must be deleted from Gallery first.
36. **Conversion warnings**: SaveResult sealed class tracks success/warning/failure. Warnings (e.g., couldn't overwrite) are logged, progress bar turns orange, auto-navigate is cancelled, and status shows "Completed with X warning(s). See log for details."

### EXIF Data Transfer

The app preserves EXIF metadata when converting RAW files to DNG and JPEG formats:

```
DNG EXIF (rawler dng/convert.rs):
  → Full EXIF + GPS IFDs from the RAW, lens description from the lens database,
    MakerNotes kept only when the decoder marks them relocation-safe, XMP packet (+ camera recipe),
    computed APEX values, FocalLengthIn35mmFormat, DCP profile tags when available

JPEG EXIF (dnglab-ffi exif_jpeg.rs):
  → build_exif_tiff(): Make/Model/Software + RawMetadata.write_exif_tags() (root, Exif and GPS IFDs, lens tags),
    MakerNotes stripped, Orientation overridden (sensor orientation for passthrough, 1 for rendered output)
  → splice_exif_app1(): inserts the APP1 after SOI (after a leading JFIF APP0), replacing any existing EXIF APP1
  → ExifData.writeExifToJpeg(raw, jpeg, orientation) remains as a fallback (ExifInterface) and is only
    invoked when JpegResult.exifEmbedded is false

extractMetadataJson() output (dnglab-ffi metadata.rs) - backend-neutral contract documented in raw_backend.h:
  → Flat keys: backend, backend_version, format, make, model, body_serial, lens_make, lens_model, lens_serial,
    min_focal, max_focal, focal_length, focal_length_35mm, aperture, shutter ("1/250"), shutter_raw, iso,
    exposure_bias, exposure_program, metering_mode, flash, white_balance, timestamp ("YYYY-MM-DD HH:MM:SS"),
    timestamp_raw, width, height (default crop), raw_width, raw_height, orientation (EXIF 1..8), colors,
    bayer_pattern ("RGGB", "X-Trans"), bits_per_sample, clean_make, clean_model, baseline_exposure,
    has_gps, gps_lat_deg/min/sec, gps_lat_ref, gps_lon_deg/min/sec, gps_lon_ref, gps_altitude, gps_alt_ref,
    gps_time_hour/min/sec, description, artist, copyright, user_comment, rating
  → preview: { embedded_jpeg, width, height, bytes, full_resolution } - what the JPEG export decision sees
  → details: the complete rawler RawMetadata (exif, lens, ...) minus the MakerNotes blob
  → Dimensions come from rawler's structure-only ("dummy") decode, no pixel data is decompressed
  → Only present/non-empty values are emitted; keys are omitted otherwise
```

### EXIF/Metadata Overlay

```
ImagePreviewDialog Info Button Flow:
  → User taps info button (ℹ) in zoom controls
  → exifOverlay visibility toggles (GONE ↔ VISIBLE)
  → If becoming visible:
    → loadExifData() coroutine launches
    → ExifData.extractFromUri() called (checks file extension)
    → For RAW/DNG: dnglab-ffi reads metadata via JNI extractMetadata()
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
    → Checks if file is RAW based on extension (DNGConverter.supportedExtensions + dng)
    → Copies file to temp cache location
    → For RAW/DNG: Calls extractFromRaw() using the native backend
    → For JPEG: Calls extractFromNonRaw() using ExifInterface
    → Deletes temp file after extraction
  → extractFromRaw(filePath, fileName, fileSize): native backend path
    → Calls DNGConverter.extractMetadata() (JNI)
    → Parses JSON into ExifData data class
  → extractFromNonRaw(filePath, fileName, fileSize): ExifInterface path
    → Uses Android ExifInterface to read JPEG EXIF tags
    → Returns ExifData data class
  → writeExifToJpeg(rawFilePath, jpegFilePath, orientation): fallback EXIF writer
    → Extracts metadata from source via the native backend
    → Writes to JPEG using Android ExifInterface (not used with the dnglab backend, which embeds EXIF itself)
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

dnglab-ffi metadata.rs:
  → metadata_json(path): opens the file once, reads RawMetadata, structure-only decode for dimensions,
    embedded preview summary; serialized with serde_json
  → GPS validation: Only outputs GPS if coordinates are non-zero
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
- [ ] Conversion parallelism setting (1 to N cores) shows correct values
- [ ] Conversion parallelism setting persists across app restarts
- [ ] Conversion uses configured number of threads
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
- [ ] Regenerate finds original RAW files and shows format dialog
- [ ] Regenerate dialog shows format selection and JPEG settings
- [ ] Regenerate navigates to Convert tab after confirming
- [ ] Regenerate auto-starts conversion with selected settings
- [ ] Regenerate shows conversion progress UI in Convert tab
- [ ] Regenerate reports when some RAW files not found
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
- [ ] DNG thumbnails in gallery are correctly rotated
- [ ] JPEG export of a NEF/CR2/CR3 with policy "full resolution" logs "(camera JPEG)" and finishes almost instantly
- [ ] JPEG export of an ARW/RW2 (small embedded preview) renders and matches the camera brightness roughly
- [ ] JPEG export with policy "never" renders for every format
- [ ] Portrait RAW files: exported JPEG and cached previews display upright
- [ ] Settings shows "RAW engine: dnglab/rawler <version>"

### Common Issues

1. **Purple/green cast or wrong brightness in rendered JPEG/DNG**: black/white levels and matrices come from the dnglab camera database (`rawler/data/cameras/<make>/<model>.toml`); fix them in the dnglab fork
2. **Wrong colors**: Check the camera entry / DCP matching in dnglab, `adb logcat -s dnglab` shows what rawler picked
3. **Badges not updating**: Ensure `refreshConversionStatus()` is called in `onResume()`
4. **Install fails**: Run `adb uninstall com.raw2dng` first if signing keys changed
5. **"Internal decoder failure" errors**: a rawler panic was caught by dnglab-ffi; the message names the panic, reproduce with `cargo run -p dnglab-ffi --example dnglab_ffi_cli -- jpeg <file> out.jpg` on a desktop
6. **Files saved with UUID in name**: Check that `saveToPublicStorage()` receives the original filename, not the cache filename
7. **RAW files not appearing**: Check settings for enabled RAW types; the list only offers extensions the backend supports (DNGConverter.supportedExtensions)
8. **Missing EXIF in JPEG**: check `JpegResult.exifEmbedded` in the log; dnglab-ffi drops the EXIF block when it exceeds 64 KiB (MakerNotes are already stripped)
9. **File gets "(1)" suffix on overwrite**: File was created by previous app installation. Android Scoped Storage prevents modifying files not owned by the current app. Delete from Gallery first using the clear/delete feature (uses `MediaStore.createDeleteRequest()` for system confirmation), then re-convert.
10. **DNG thumbnails not rotated**: Android's `loadThumbnail()` doesn't apply EXIF orientation for DNG files. GalleryAdapter, ThumbnailStripAdapter, and ImagePagerAdapter manually read EXIF orientation and apply rotation.
