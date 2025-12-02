#!/usr/bin/env bash
set -euo pipefail

# Rebuild libopus.so for 16KB page size devices.

ROOT_DIR="$(cd "$(dirname "$0")/../../../.." && pwd)"
SDK_DIR="$(grep -oE '^sdk.dir=.*' "$ROOT_DIR/local.properties" | head -n1 | cut -d= -f2-)"
SDK_DIR="${SDK_DIR:-/Users/oleksandrnekrutenko/Library/Android/sdk}"

NDK_VERSION="${NDK_VERSION:-28.0.12916984}"
NDK_DIR="$SDK_DIR/ndk/$NDK_VERSION"
CMAKE_BIN="$SDK_DIR/cmake/3.22.1/bin/cmake"

OPUS_SRC="$ROOT_DIR/opus"
BUILD_ROOT="$ROOT_DIR/opus-build"
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

if [[ ! -d "$NDK_DIR" ]]; then
  echo "NDK not found at $NDK_DIR"
  exit 1
fi

mkdir -p "$BUILD_ROOT"

build_opus() {
  local abi="$1"
  local platform="$2"
  local build_dir="$BUILD_ROOT/build-$abi"
  local out_dir="$build_dir/out"

  rm -rf "$build_dir"
  mkdir -p "$build_dir"

  "$CMAKE_BIN" -S "$OPUS_SRC" -B "$build_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$platform" \
    -DANDROID_STL=c++_shared \
    -DBUILD_SHARED_LIBS=ON \
    -DOPUS_BUILD_PROGRAMS=OFF \
    -DOPUS_BUILD_TESTING=OFF \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ARM_NEON=TRUE \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out_dir" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,common-page-size=16384 -Wl,-z,max-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,common-page-size=16384 -Wl,-z,max-page-size=16384"

  "$CMAKE_BIN" --build "$build_dir" --config Release --target opus

  local lib_path="$out_dir/libopus.so"
  if [[ ! -f "$lib_path" ]]; then
    echo "libopus.so not produced for $abi"
    exit 1
  fi

  mkdir -p "$JNI_LIBS_DIR/$abi"
  cp "$lib_path" "$JNI_LIBS_DIR/$abi/libopus.so"
  echo "✅ Installed $lib_path -> $JNI_LIBS_DIR/$abi/libopus.so"
}

build_opus "arm64-v8a" "android-24"
build_opus "armeabi-v7a" "android-24"
