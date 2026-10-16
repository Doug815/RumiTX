#!/usr/bin/env bash
# Generate COMPILE-ONLY Android 8.1 HIDL signature jar. NEVER package in APK.
# Real android.os.HwBinder/HwParcel/HwBlob and IBase come from S80 firmware.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="$ANDROID_HOME"
PLATFORM="$SDK/platforms/android-33/android.jar"
test -f "$PLATFORM" || { echo "Install Android SDK platform 33 first: $PLATFORM" >&2; exit 2; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" app/libs
find tools/hidl-compile-stubs/src -name '*.java' -print0 |
    xargs -0 javac -source 8 -target 8 -cp "$PLATFORM" -d "$TMP/classes"
jar cf app/libs/hidl-compile-stubs.jar -C "$TMP/classes" .
echo "Created compileOnly app/libs/hidl-compile-stubs.jar; never add it to APK runtime."
