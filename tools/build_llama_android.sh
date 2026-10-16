#!/usr/bin/env bash
# Pin llama.cpp and build in-process Android 8.1 ARM64 shared libraries.
# No root. Never ship a standalone executable or the 1.1GB GGUF.
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "Missing Android NDK at $NDK" >&2; exit 2; }
NATIVE="${PWD}/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$NATIVE"
if [[ ! -d .ci-llama/.git ]]; then
  git clone --depth 1 --branch b11206 https://github.com/ggml-org/llama.cpp.git .ci-llama
fi
TAG_SHA="$(git -C .ci-llama rev-parse HEAD)"
echo "Pinned llama.cpp tag b11206 SHA: $TAG_SHA"
# We intentionally compile for generic ARMv8.0, not an advanced ISA.
cmake -S .ci-llama -B .ci-llama/build-s80 -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DCMAKE_C_FLAGS="-march=armv8-a" \
  -DCMAKE_CXX_FLAGS="-march=armv8-a" \
  -DBUILD_SHARED_LIBS=ON \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DGGML_VULKAN=OFF \
  -DGGML_CUDA=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF
cmake --build .ci-llama/build-s80 --target llama -j 3
for name in libllama.so libggml.so libggml-base.so libggml-cpu.so; do
  srcfile="$(find .ci-llama/build-s80 -type f -name "$name" -print -quit)"
  [[ -n "$srcfile" ]] || { echo "Required shared library missing: $name" >&2; exit 3; }
  cp -L "$srcfile" "$NATIVE/$name"
done
cmake -S app/src/main/cpp -B .ci-llama/build-jni -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DLLAMA_ROOT="$PWD/.ci-llama" \
  -DPREBUILT_DIR="$NATIVE"
cmake --build .ci-llama/build-jni -j 2
test -f "$NATIVE/librumitx_llama_jni.so"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for library in "$NATIVE"/*.so; do
  "$STRIP" --strip-unneeded "$library"
  file "$library"
  stat -c '%n %s bytes' "$library"
done
echo "Native ARM64 libraries ready. They must be packaged INSIDE app APK in lib/arm64-v8a."
