# Android Build Environment for Raw2DNG
# This Dockerfile creates a complete Android build environment with NDK and CMake

FROM ubuntu:22.04

# Avoid interactive prompts during build
ENV DEBIAN_FRONTEND=noninteractive

# Set environment variables for Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_VERSION=25.1.8937393
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/34.0.0

# Install system dependencies
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    openjdk-17-jdk \
    build-essential \
    file \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install .NET SDK for GitVersion
RUN wget https://dot.net/v1/dotnet-install.sh -O dotnet-install.sh \
    && chmod +x dotnet-install.sh \
    && ./dotnet-install.sh --channel 8.0 \
    && rm dotnet-install.sh

ENV DOTNET_ROOT=/root/.dotnet
ENV PATH=${PATH}:/root/.dotnet:/root/.dotnet/tools

# Install GitVersion as a .NET global tool
RUN /root/.dotnet/dotnet tool install --global GitVersion.Tool --version 5.*

# Set Java environment
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Create Android SDK directory
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools

# Download and install Android command line tools
RUN cd ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip && \
    unzip commandlinetools-linux-9477386_latest.zip && \
    rm commandlinetools-linux-9477386_latest.zip && \
    mv cmdline-tools latest

# Accept Android SDK licenses
RUN yes | sdkmanager --licenses || true

# Install required Android SDK components
RUN sdkmanager --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;33.0.1" \
    "ndk;${ANDROID_NDK_VERSION}" \
    "cmake;3.22.1"

# Configure git to trust the workspace directory (for GitVersion)
RUN git config --global --add safe.directory /workspace

# Set working directory
WORKDIR /workspace

# Create output directory for APK
RUN mkdir -p /workspace/output

# Default command: build the APK
CMD ["./build-apk.sh"]
