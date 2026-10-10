# Makefile for Raw2DNG Android App
# Provides convenient shortcuts for Docker-based builds

.PHONY: help build clean install shell setup-sdk

help:
	@echo "Raw2DNG Build Commands"
	@echo "======================"
	@echo ""
	@echo "  make build       - Build APK using Docker"
	@echo "  make clean       - Clean build artifacts"
	@echo "  make install     - Install debug APK on connected device"
	@echo "  make shell       - Open shell in Docker container"
	@echo "  make setup-sdk   - Show how the RAW engine (dnglab, Rust) is set up"
	@echo ""

build:
	@echo "Building APK with Docker..."
	./docker-build.sh

clean:
	@echo "Cleaning build artifacts..."
	rm -rf app/build/
	rm -rf build/
	rm -rf .gradle/
	rm -rf output/
	rm -rf app/src/main/cpp/dnglab/target/
	@echo "Clean complete!"

install:
	@if [ ! -f "output/app-debug.apk" ]; then \
		echo "Error: APK not found. Run 'make build' first."; \
		exit 1; \
	fi
	@echo "Installing APK on device..."
	adb install -r output/app-debug.apk

shell:
	@echo "Opening shell in Docker container..."
	docker-compose run --rm android-builder /bin/bash

setup-sdk:
	@echo ""
	@echo "RAW engine setup (dnglab / Rust)"
	@echo "================================="
	@echo ""
	@echo "The Docker image already contains everything. For a local build:"
	@echo ""
	@echo "1. Fetch the dnglab submodule:"
	@echo "   git submodule update --init --recursive"
	@echo ""
	@echo "2. Install Rust (https://rustup.rs) and the Android targets:"
	@echo "   rustup target add aarch64-linux-android armv7-linux-androideabi \\"
	@echo "                     i686-linux-android x86_64-linux-android"
	@echo ""
	@echo "3. Build the app (CMake invokes cargo for every ABI):"
	@echo "   ./gradlew assembleDebug"
	@echo ""
	@echo "To build against a dnglab checkout elsewhere:"
	@echo "   ./gradlew assembleDebug -PdnglabSourceDir=/path/to/dnglab"
	@echo ""
