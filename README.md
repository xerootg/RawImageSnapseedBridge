# Raw2DNG - Android RAW to DNG/JPEG Converter

An Android application that converts proprietary RAW camera files (CR2, CR3, ARW, NEF, ORF, RAF, RW2, PEF, SRW, etc.) to Adobe DNG format and/or JPEG. RAW decoding, DNG authoring, JPEG export and metadata come from [dnglab/rawler](https://github.com/xerootg/dnglab) (Rust), embedded through a small C ABI (`dnglab-ffi`).

Most RAW files carry the full-resolution JPEG the camera rendered. JPEG export reuses it directly (instant, in-camera look, fresh EXIF) whenever it is full size, and only demosaics the sensor data for formats that embed a reduced preview (Sony ARW, Panasonic RW2, older Olympus/Pentax) or when you ask for a re-render.

## Features

### Core Conversion
- ✅ Convert RAW files to Adobe DNG format (lossless JPEG-92, camera profiles and lens corrections from the dnglab camera database)
- ✅ Convert RAW files to high-quality JPEG: embedded camera JPEG passthrough or full render, configurable per conversion
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
- ✅ Configurable JPEG quality, chroma subsampling, Huffman optimization and camera-JPEG policy
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

# 3. Build the APK (one command!)
sudo make build
# or: ./docker-build.sh

# 4. Your APK is ready!
ls output/app-debug.apk

# 5. Install on connected device
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

2. **Android NDK** (r25 - the project pins 25.1.8937393)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → NDK (Side by side)

3. **CMake** (3.22.1 or newer)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → CMake

4. **Rust** (stable, 1.88 or newer) with the Android targets
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
   ```
   CMake runs `cargo` for every ABI and links the resulting static library into the JNI module. If `cargo` is not on the PATH Android Studio uses, set `CARGO=/path/to/cargo` in the environment or `CARGO_HOME`.

### RAW engine (dnglab)

The RAW engine lives in the `dnglab` git submodule (`app/src/main/cpp/dnglab`, fork of [dnglab](https://github.com/dnglab/dnglab) with the `dnglab-ffi` C ABI crate). Fetch it once:

```bash
git submodule update --init --recursive
```

To hack on both repositories side by side, point the build at any dnglab checkout:

```bash
./gradlew assembleDebug -PdnglabSourceDir=/path/to/dnglab
# or
DNGLAB_SOURCE_DIR=/path/to/dnglab ./gradlew assembleDebug
```

No proprietary SDKs are needed: there is nothing to download from Adobe.

## Building the Project

### Option 1: Android Studio (Recommended)

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `raw2dng2` directory
4. Wait for Gradle sync to complete
5. Ensure Rust and the Android targets are installed (see above)
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
- **JPEG Quality Settings**: Quality (80-100), chroma subsampling, Huffman optimization, and the camera-JPEG policy (use embedded JPEG when full resolution / always / never)
- **View Open Source Licenses**: Attribution for dnglab/rawler and the Rust crates it bundles
- **RAW engine**: Shows the dnglab/rawler version compiled into the app

### JPEG export: camera JPEG vs. render

| Policy | Behaviour |
|--------|-----------|
| Use embedded JPEG when full resolution (default) | Copies the camera's JPEG when both edges are at least 90% of the sensor's default crop; otherwise renders |
| Always use embedded JPEG | Copies whatever JPEG is embedded, even a small preview |
| Never | Always demosaics and renders (quality/subsampling/Huffman settings apply) |

The embedded JPEG keeps the in-camera look (picture style, lens corrections, noise reduction) and takes milliseconds. Rendering uses rawler's pipeline: PPG demosaic (bilinear for X-Trans), camera matrix, dcraw-style auto brightness, sRGB. Either way the JPEG receives complete EXIF (camera, lens, exposure, GPS, orientation) from the RAW. The conversion log marks passthrough files with "(camera JPEG)".

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

Decoding is done by rawler; the formats offered in Settings are filtered by what the compiled engine supports:

| Manufacturer | Formats |
|-------------|---------|
| **Canon** | CR2, CR3, CRW |
| **Nikon** | NEF, NRW |
| **Sony** | ARW |
| **Fujifilm** | RAF (incl. X-Trans) |
| **Olympus/OM System** | ORF, ORI |
| **Panasonic/Lumix/Leica** | RW2, RWL, RAW |
| **Pentax** | PEF |
| **Samsung** | SRW |
| **Hasselblad** | 3FR, FFF |
| **Phase One/Leaf** | IIQ, MOS |
| **Kodak** | DCR, KDC |
| **Epson** | ERF |
| **Mamiya** | MEF |
| **Minolta** | MRW |
| **Sigma** | X3F |

See [SUPPORTED_CAMERAS.md](https://github.com/dnglab/dnglab/blob/main/SUPPORTED_CAMERAS.md) in dnglab for the camera list. DNG files are also readable (metadata, previews, re-authoring).

**Note**: You can enable/disable specific formats in Settings → Enabled RAW File Types.

## Known Limitations

1. **Scoped Storage**: Files created by previous app installations cannot be overwritten directly. Delete from Gallery first, then re-convert.
2. **DNG Thumbnail Orientation**: Android's `loadThumbnail()` doesn't apply EXIF orientation for DNG files; app manually rotates.
3. **Package Visibility**: AndroidManifest must declare Snapseed package for integration to work on Android 11+.
4. **Camera support**: Colour matrices, levels and crops come from the dnglab camera database; unsupported bodies need an entry there (`rawler/data/cameras/`).
5. **Memory**: Rendering a 24-45 MP file from sensor data needs several hundred MB; keep the conversion thread count low on small devices. Embedded-JPEG passthrough uses almost nothing.

## Troubleshooting

### Build Errors

**"dnglab-ffi not found"**
- Run `git submodule update --init --recursive` or pass `-PdnglabSourceDir=/path/to/dnglab`

**"cargo not found" / "target may not be installed"**
- Install Rust and run `rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`

**"NDK not configured"**
- Install Android NDK via Android Studio SDK Manager

**"CMake not found"**
- Install CMake via Android Studio SDK Manager

### Runtime Issues

**Install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE**
```bash
adb uninstall com.raw2dng && adb install output/app-debug.apk
```

**Wrong colors / brightness for a specific camera**
- Levels and matrices live in the dnglab camera database (`rawler/data/cameras/<make>/*.toml`); `adb logcat -s dnglab` shows what the engine picked

**"Internal decoder failure: ..."**
- A rawler panic was caught; reproduce on a desktop with `cargo run -p dnglab-ffi --example dnglab_ffi_cli -- jpeg <file> out.jpg` and file an issue against the dnglab fork

**File gets "(1)" suffix on overwrite**
- File was created by previous app installation; delete from Gallery first

**RAW files not appearing**
- Check Settings → Enabled RAW File Types

## License

This project uses multiple open-source components with various licenses:

- **dnglab / rawler** (fork at https://github.com/xerootg/dnglab): LGPL 2.1. The engine is linked statically; the complete corresponding source, including the `dnglab-ffi` bindings, is in the submodule.
- **Rust crates** bundled by the engine (image, zune-jpeg, jpeg-encoder, jxl-oxide, serde, rayon, chrono, ...): MIT and/or Apache 2.0 (jpeg-encoder additionally IJG)
- **Android/Kotlin Libraries**: Apache 2.0

View full license details in the app via Settings → View Open Source Licenses.

## Resources

- [dnglab](https://github.com/dnglab/dnglab) and the [rawler crate](https://docs.rs/rawler)
- [Adobe DNG Specification](https://helpx.adobe.com/photoshop/digital-negative.html)
- [Android NDK Documentation](https://developer.android.com/ndk)
- [JNI Guide](https://developer.android.com/training/articles/perf-jni)

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Review the dnglab documentation for decoder/camera questions
3. Check Android NDK documentation for native build issues

## Acknowledgments

- [raw2dng2](https://github.com/fwibisono87/raw2dng2) the skeleton for this project
- [dnglab](https://github.com/dnglab/dnglab) (Daniel Vogelbacher, Pedro Côrte-Real and contributors) for rawler, the RAW decoding and DNG authoring engine
- The Rust image ecosystem (image-rs, zune-jpeg, jpeg-encoder)
- Android Open Source Project
- JetBrains for Kotlin
