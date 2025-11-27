#!/bin/bash

# Build script for Raw2DNG Android APK
# This script builds both debug and release APKs (release only if CONFIGURATION=Release)

set -e  # Exit on error

echo "========================================="
echo "Raw2DNG Android APK Build Script"
echo "========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "settings.gradle" ]; then
    echo -e "${RED}Error: settings.gradle not found!${NC}"
    echo "Please run this script from the project root directory."
    exit 1
fi

# Get version from GitVersion or environment
echo "Determining version..."

# Find gitversion command (handle Docker PATH issues)
GITVERSION_CMD=""
if command -v dotnet-gitversion &> /dev/null; then
    GITVERSION_CMD="dotnet-gitversion"
elif [ -x "/root/.dotnet/tools/dotnet-gitversion" ]; then
    GITVERSION_CMD="/root/.dotnet/tools/dotnet-gitversion"
fi

if [ -n "$VERSION_NAME" ]; then
    echo -e "${GREEN}✓ Using provided VERSION_NAME: $VERSION_NAME${NC}"
elif [ -n "$GITVERSION_CMD" ]; then
    echo "Running GitVersion..."
    GITVERSION_OUTPUT=$($GITVERSION_CMD 2>/dev/null || echo '{}')
    export VERSION_NAME=$(echo "$GITVERSION_OUTPUT" | grep -o '"SemVer"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*: *"\([^"]*\)".*/\1/' || echo "0.1.0-local")
    export VERSION_CODE=$(echo "$GITVERSION_OUTPUT" | grep -o '"CommitsSinceVersionSource"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | sed 's/.*: *//' || echo "1")
    # Ensure VERSION_CODE is at least 1
    if [ -z "$VERSION_CODE" ] || [ "$VERSION_CODE" -eq 0 ] 2>/dev/null; then
        VERSION_CODE=1
    fi
    echo -e "${GREEN}✓ GitVersion: $VERSION_NAME (code: $VERSION_CODE)${NC}"
else
    export VERSION_NAME="0.1.0-local"
    export VERSION_CODE="1"
    echo -e "${YELLOW}GitVersion not found, using default: $VERSION_NAME${NC}"
fi

echo ""
echo "Starting build..."
echo ""

# Determine build type based on environment
if [ "$CI" = "true" ] || [ "$BUILD_RELEASE" = "true" ]; then
    BUILD_TYPE="release"
    echo "Building RELEASE APK..."
else
    BUILD_TYPE="debug"
    echo "Building DEBUG APK..."
fi

echo ""

# Build APK
if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease
else
    ./gradlew assembleDebug
fi

# Create output directory
mkdir -p output

# Copy APK to output directory
echo ""
echo "Copying APK to output directory..."

if [ "$BUILD_TYPE" = "release" ]; then
    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        cp app/build/outputs/apk/release/app-release.apk output/
        echo -e "${GREEN}✓ Release APK: output/app-release.apk${NC}"
        APK_SIZE=$(du -h output/app-release.apk | cut -f1)
        echo "  Size: $APK_SIZE"
    else
        echo -e "${RED}✗ Release APK not found!${NC}"
        exit 1
    fi
else
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        cp app/build/outputs/apk/debug/app-debug.apk output/
        echo -e "${GREEN}✓ Debug APK: output/app-debug.apk${NC}"
        APK_SIZE=$(du -h output/app-debug.apk | cut -f1)
        echo "  Size: $APK_SIZE"
    else
        echo -e "${RED}✗ Debug APK not found!${NC}"
        exit 1
    fi
fi

echo ""
echo "========================================="
echo -e "${GREEN}Build Complete!${NC}"
echo "========================================="
echo ""
echo "APK files are in the 'output/' directory:"
echo ""
ls -lh output/*.apk 2>/dev/null || echo "No APK files found in output/"
echo ""
echo "To install on a device:"
echo "  adb install output/app-debug.apk"
echo ""
echo "Or transfer the APK to your Android device and install manually."
echo ""
